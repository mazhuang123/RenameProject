/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.migu.player.extractor.mp4;

import android.support.annotation.IntDef;
import android.support.annotation.Nullable;

import com.google.common.base.Function;
import com.migu.player.C;
import com.migu.player.Format;
import com.migu.player.ParserException;
import com.migu.player.audio.Ac4Util;
import com.migu.player.extractor.Extractor;
import com.migu.player.extractor.ExtractorInput;
import com.migu.player.extractor.ExtractorOutput;
import com.migu.player.extractor.ExtractorsFactory;
import com.migu.player.extractor.GaplessInfoHolder;
import com.migu.player.extractor.PositionHolder;
import com.migu.player.extractor.SeekMap;
import com.migu.player.extractor.SeekPoint;
import com.migu.player.extractor.TrackOutput;
import com.migu.player.extractor.mp4.Atom.ContainerAtom;
import com.migu.player.metadata.Metadata;
import com.migu.player.util.Assertions;
import com.migu.player.util.MimeTypes;
import com.migu.player.util.NalUnitUtil;
import com.migu.player.util.ParsableByteArray;

import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static com.migu.player.extractor.mp4.AtomParsers.parseTraks;
import static com.migu.player.util.Assertions.checkNotNull;
import static com.migu.player.util.Util.castNonNull;
import static java.lang.Math.max;
import static java.lang.Math.min;


/**
 * Extracts data from the MP4 container format.
 */
public final class Mp4Extractor implements Extractor, SeekMap {

  /** Factory for {@link Mp4Extractor} instances. */
//  public static final ExtractorsFactory FACTORY = () -> new Extractor[] {new Mp4Extractor()};
    public static final ExtractorsFactory FACTORY = new ExtractorsFactory() {
        @Override
        public Extractor[] createExtractors() {
            return new Extractor[] {new Mp4Extractor()};
        }
    };
  /**
   * Flags controlling the behavior of the extractor. Possible flag value is {@link
   * #FLAG_WORKAROUND_IGNORE_EDIT_LISTS}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @IntDef(
      flag = true,
      value = {FLAG_WORKAROUND_IGNORE_EDIT_LISTS})
  public @interface Flags {}
  /**
   * Flag to ignore any edit lists in the stream.
   */
  public static final int FLAG_WORKAROUND_IGNORE_EDIT_LISTS = 1;

  /** Parser states. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({STATE_READING_ATOM_HEADER, STATE_READING_ATOM_PAYLOAD, STATE_READING_SAMPLE})
  private @interface State {}

  private static final int STATE_READING_ATOM_HEADER = 0;
  private static final int STATE_READING_ATOM_PAYLOAD = 1;
  private static final int STATE_READING_SAMPLE = 2;

  /** Brand stored in the ftyp atom for QuickTime media. */
  private static final int BRAND_QUICKTIME = 0x71742020;

  /**
   * When seeking within the source, if the offset is greater than or equal to this value (or the
   * offset is negative), the source will be reloaded.
   */
  private static final long RELOAD_MINIMUM_SEEK_DISTANCE = 256 * 1024;

  /**
   * For poorly interleaved streams, the maximum byte difference one track is allowed to be read
   * ahead before the source will be reloaded at a new position to read another track.
   */
  private static final long MAXIMUM_READ_AHEAD_BYTES_STREAM = 10 * 1024 * 1024;

  private final @Flags int flags;

  // Temporary arrays.
  private final ParsableByteArray nalStartCode;
  private final ParsableByteArray nalLength;
  private final ParsableByteArray scratch;

  private final ParsableByteArray atomHeader;
  private final ArrayDeque<ContainerAtom> containerAtoms;

  @State private int parserState;
  private int atomType;
  private long atomSize;
  private int atomHeaderBytesRead;
  @Nullable private ParsableByteArray atomData;

  private int sampleTrackIndex;
  private int sampleBytesRead;
  private int sampleBytesWritten;
  private int sampleCurrentNalBytesRemaining;

  // Extractor outputs.
  private  ExtractorOutput extractorOutput;
  private Mp4Track  [] tracks;
  private long  [][] accumulatedSampleSizes;
  private int firstVideoTrackIndex;
  private long durationUs;
  private boolean isQuickTime;

  /**
   * Creates a new extractor for unfragmented MP4 streams.
   */
  public Mp4Extractor() {
    this(0);
  }

  /**
   * Creates a new extractor for unfragmented MP4 streams, using the specified flags to control the
   * extractor's behavior.
   *
   * @param flags Flags that control the extractor's behavior.
   */
  public Mp4Extractor(@Flags int flags) {
    this.flags = flags;
    atomHeader = new ParsableByteArray(Atom.LONG_HEADER_SIZE);
    containerAtoms = new ArrayDeque<>();
    nalStartCode = new ParsableByteArray(NalUnitUtil.NAL_START_CODE);
    nalLength = new ParsableByteArray(4);
    scratch = new ParsableByteArray();
    sampleTrackIndex = C.INDEX_UNSET;
  }

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    return Sniffer.sniffUnfragmented(input);
  }

  @Override
  public void init(ExtractorOutput output) {
    extractorOutput = output;
  }

  @Override
  public void seek(long position, long timeUs) {
    containerAtoms.clear();
    atomHeaderBytesRead = 0;
    sampleTrackIndex = C.INDEX_UNSET;
    sampleBytesRead = 0;
    sampleBytesWritten = 0;
    sampleCurrentNalBytesRemaining = 0;
    if (position == 0) {
      enterReadingAtomHeaderState();
    } else if (tracks != null) {
      updateSampleIndices(timeUs);
    }
  }

  @Override
  public void release() {
    // Do nothing
  }

  @Override
  public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
    while (true) {
      switch (parserState) {
        case STATE_READING_ATOM_HEADER:
          if (!readAtomHeader(input)) {
            return RESULT_END_OF_INPUT;
          }
          break;
        case STATE_READING_ATOM_PAYLOAD:
          if (readAtomPayload(input, seekPosition)) {
            return RESULT_SEEK;
          }
          break;
        case STATE_READING_SAMPLE:
          return readSample(input, seekPosition);
        default:
          throw new IllegalStateException();
      }
    }
  }

  // SeekMap implementation.

  @Override
  public boolean isSeekable() {
    return true;
  }

  @Override
  public long getDurationUs() {
    return durationUs;
  }

  @Override
  public SeekPoints getSeekPoints(long timeUs) {
    if (checkNotNull(tracks).length == 0) {
      return new SeekPoints(SeekPoint.START);
    }

    long firstTimeUs;
    long firstOffset;
    long secondTimeUs = C.TIME_UNSET;
    long secondOffset = C.POSITION_UNSET;

    // If we have a video track, use it to establish one or two seek points.
    if (firstVideoTrackIndex != C.INDEX_UNSET) {
      TrackSampleTable sampleTable = tracks[firstVideoTrackIndex].sampleTable;
      int sampleIndex = getSynchronizationSampleIndex(sampleTable, timeUs);
      if (sampleIndex == C.INDEX_UNSET) {
        return new SeekPoints(SeekPoint.START);
      }
      long sampleTimeUs = sampleTable.timestampsUs[sampleIndex];
      firstTimeUs = sampleTimeUs;
      firstOffset = sampleTable.offsets[sampleIndex];
      if (sampleTimeUs < timeUs && sampleIndex < sampleTable.sampleCount - 1) {
        int secondSampleIndex = sampleTable.getIndexOfLaterOrEqualSynchronizationSample(timeUs);
        if (secondSampleIndex != C.INDEX_UNSET && secondSampleIndex != sampleIndex) {
          secondTimeUs = sampleTable.timestampsUs[secondSampleIndex];
          secondOffset = sampleTable.offsets[secondSampleIndex];
        }
      }
    } else {
      firstTimeUs = timeUs;
      firstOffset = Long.MAX_VALUE;
    }

    // Take into account other tracks.
    for (int i = 0; i < tracks.length; i++) {
      if (i != firstVideoTrackIndex) {
        TrackSampleTable sampleTable = tracks[i].sampleTable;
        firstOffset = maybeAdjustSeekOffset(sampleTable, firstTimeUs, firstOffset);
        if (secondTimeUs != C.TIME_UNSET) {
          secondOffset = maybeAdjustSeekOffset(sampleTable, secondTimeUs, secondOffset);
        }
      }
    }

    SeekPoint firstSeekPoint = new SeekPoint(firstTimeUs, firstOffset);
    if (secondTimeUs == C.TIME_UNSET) {
      return new SeekPoints(firstSeekPoint);
    } else {
      SeekPoint secondSeekPoint = new SeekPoint(secondTimeUs, secondOffset);
      return new SeekPoints(firstSeekPoint, secondSeekPoint);
    }
  }

  // Private methods.

  private void enterReadingAtomHeaderState() {
    parserState = STATE_READING_ATOM_HEADER;
    atomHeaderBytesRead = 0;
  }

  private boolean readAtomHeader(ExtractorInput input) throws IOException {
    if (atomHeaderBytesRead == 0) {
      // Read the standard length atom header.
      if (!input.readFully(atomHeader.getData(), 0, Atom.HEADER_SIZE, true)) {
        return false;
      }
      atomHeaderBytesRead = Atom.HEADER_SIZE;
      atomHeader.setPosition(0);
      atomSize = atomHeader.readUnsignedInt();
      atomType = atomHeader.readInt();
    }

    if (atomSize == Atom.DEFINES_LARGE_SIZE) {
      // Read the large size.
      int headerBytesRemaining = Atom.LONG_HEADER_SIZE - Atom.HEADER_SIZE;
      input.readFully(atomHeader.getData(), Atom.HEADER_SIZE, headerBytesRemaining);
      atomHeaderBytesRead += headerBytesRemaining;
      atomSize = atomHeader.readUnsignedLongToLong();
    } else if (atomSize == Atom.EXTENDS_TO_END_SIZE) {
      // The atom extends to the end of the file. Note that if the atom is within a container we can
      // work out its size even if the input length is unknown.
      long endPosition = input.getLength();
      if (endPosition == C.LENGTH_UNSET) {
        @Nullable ContainerAtom containerAtom = containerAtoms.peek();
        if (containerAtom != null) {
          endPosition = containerAtom.endPosition;
        }
      }
      if (endPosition != C.LENGTH_UNSET) {
        atomSize = endPosition - input.getPosition() + atomHeaderBytesRead;
      }
    }

    if (atomSize < atomHeaderBytesRead) {
      throw new ParserException("Atom size less than header length (unsupported).");
    }

    if (shouldParseContainerAtom(atomType)) {
      long endPosition = input.getPosition() + atomSize - atomHeaderBytesRead;
      if (atomSize != atomHeaderBytesRead && atomType == Atom.TYPE_meta) {
        maybeSkipRemainingMetaAtomHeaderBytes(input);
      }
      containerAtoms.push(new ContainerAtom(atomType, endPosition));
      if (atomSize == atomHeaderBytesRead) {
        processAtomEnded(endPosition);
      } else {
        // Start reading the first child atom.
        enterReadingAtomHeaderState();
      }
    } else if (shouldParseLeafAtom(atomType)) {
      // We don't support parsing of leaf atoms that define extended atom sizes, or that have
      // lengths greater than Integer.MAX_VALUE.
      Assertions.checkState(atomHeaderBytesRead == Atom.HEADER_SIZE);
      Assertions.checkState(atomSize <= Integer.MAX_VALUE);
      ParsableByteArray atomData = new ParsableByteArray((int) atomSize);
      System.arraycopy(atomHeader.getData(), 0, atomData.getData(), 0, Atom.HEADER_SIZE);
      this.atomData = atomData;
      parserState = STATE_READING_ATOM_PAYLOAD;
    } else {
      atomData = null;
      parserState = STATE_READING_ATOM_PAYLOAD;
    }

    return true;
  }

  /**
   * Processes the atom payload. If {@link #atomData} is null and the size is at or above the
   * threshold {@link #RELOAD_MINIMUM_SEEK_DISTANCE}, {@code true} is returned and the caller should
   * restart loading at the position in {@code positionHolder}. Otherwise, the atom is read/skipped.
   */
  private boolean readAtomPayload(ExtractorInput input, PositionHolder positionHolder)
      throws IOException {
    long atomPayloadSize = atomSize - atomHeaderBytesRead;
    long atomEndPosition = input.getPosition() + atomPayloadSize;
    boolean seekRequired = false;
    @Nullable ParsableByteArray atomData = this.atomData;
    if (atomData != null) {
      input.readFully(atomData.getData(), atomHeaderBytesRead, (int) atomPayloadSize);
      if (atomType == Atom.TYPE_ftyp) {
        isQuickTime = processFtypAtom(atomData);
      } else if (!containerAtoms.isEmpty()) {
        containerAtoms.peek().add(new Atom.LeafAtom(atomType, atomData));
      }
    } else {
      // We don't need the data. Skip or seek, depending on how large the atom is.
      if (atomPayloadSize < RELOAD_MINIMUM_SEEK_DISTANCE) {
        input.skipFully((int) atomPayloadSize);
      } else {
        positionHolder.position = input.getPosition() + atomPayloadSize;
        seekRequired = true;
      }
    }
    processAtomEnded(atomEndPosition);
    return seekRequired && parserState != STATE_READING_SAMPLE;
  }

  private void processAtomEnded(long atomEndPosition) throws ParserException {
    while (!containerAtoms.isEmpty() && containerAtoms.peek().endPosition == atomEndPosition) {
      Atom.ContainerAtom containerAtom = containerAtoms.pop();
      if (containerAtom.type == Atom.TYPE_moov) {
        // We've reached the end of the moov atom. Process it and prepare to read samples.
        processMoovAtom(containerAtom);
        containerAtoms.clear();
        parserState = STATE_READING_SAMPLE;
      } else if (!containerAtoms.isEmpty()) {
        containerAtoms.peek().add(containerAtom);
      }
    }
    if (parserState != STATE_READING_SAMPLE) {
      enterReadingAtomHeaderState();
    }
  }

  /**
   * Updates the stored track metadata to reflect the contents of the specified moov atom.
   */
  private void processMoovAtom(ContainerAtom moov) throws ParserException {
    int firstVideoTrackIndex = C.INDEX_UNSET;
    long durationUs = C.TIME_UNSET;
    List<Mp4Track> tracks = new ArrayList<>();

    // Process metadata.
    @Nullable Metadata udtaMetadata = null;
    GaplessInfoHolder gaplessInfoHolder = new GaplessInfoHolder();
    @Nullable Atom.LeafAtom udta = moov.getLeafAtomOfType(Atom.TYPE_udta);
    if (udta != null) {
      udtaMetadata = AtomParsers.parseUdta(udta, isQuickTime);
      if (udtaMetadata != null) {
        gaplessInfoHolder.setFromMetadata(udtaMetadata);
      }
    }
    @Nullable Metadata mdtaMetadata = null;
    @Nullable Atom.ContainerAtom meta = moov.getContainerAtomOfType(Atom.TYPE_meta);
    if (meta != null) {
      mdtaMetadata = AtomParsers.parseMdtaFromMeta(meta);
    }

    boolean ignoreEditLists = (flags & FLAG_WORKAROUND_IGNORE_EDIT_LISTS) != 0;
//    List<TrackSampleTable> trackSampleTables =
//        parseTraks(
//            moov,
//            gaplessInfoHolder,
//            /* duration= */ C.TIME_UNSET,
//            /* drmInitData= */ null,
//            ignoreEditLists,
//            isQuickTime,
//            /* modifyTrackFunction= */ track -> track);
      List<TrackSampleTable> trackSampleTables =
              parseTraks(
                      moov,
                      gaplessInfoHolder,
                      /* duration= */ C.TIME_UNSET,
                      /* drmInitData= */ null,
                      ignoreEditLists,
                      isQuickTime, new Function<Track, Track>() {
                          @Override
                          public Track apply(Track track) {
                              return track;
                          }
                      });

    ExtractorOutput extractorOutput = checkNotNull(this.extractorOutput);
    int trackCount = trackSampleTables.size();
    for (int i = 0; i < trackCount; i++) {
      TrackSampleTable trackSampleTable = trackSampleTables.get(i);
      if (trackSampleTable.sampleCount == 0) {
        continue;
      }
      Track track = trackSampleTable.track;
      long trackDurationUs =
          track.durationUs != C.TIME_UNSET ? track.durationUs : trackSampleTable.durationUs;
      durationUs = max(durationUs, trackDurationUs);
      Mp4Track mp4Track = new Mp4Track(track, trackSampleTable,
          extractorOutput.track(i, track.type));

      // Each sample has up to three bytes of overhead for the start code that replaces its length.
      // Allow ten source samples per output sample, like the platform extractor.
      int maxInputSize = trackSampleTable.maximumSize + 3 * 10;
      Format.Builder formatBuilder = track.format.buildUpon();
      formatBuilder.setMaxInputSize(maxInputSize);
      if (track.type == C.TRACK_TYPE_VIDEO
          && trackDurationUs > 0
          && trackSampleTable.sampleCount > 1) {
        float frameRate = trackSampleTable.sampleCount / (trackDurationUs / 1000000f);
        formatBuilder.setFrameRate(frameRate);
      }
      MetadataUtil.setFormatMetadata(
          track.type, udtaMetadata, mdtaMetadata, gaplessInfoHolder, formatBuilder);
      mp4Track.trackOutput.format(formatBuilder.build());

      if (track.type == C.TRACK_TYPE_VIDEO && firstVideoTrackIndex == C.INDEX_UNSET) {
        firstVideoTrackIndex = tracks.size();
      }
      tracks.add(mp4Track);
    }
    this.firstVideoTrackIndex = firstVideoTrackIndex;
    this.durationUs = durationUs;
    this.tracks = tracks.toArray(new Mp4Track[0]);
    accumulatedSampleSizes = calculateAccumulatedSampleSizes(this.tracks);

    extractorOutput.endTracks();
    extractorOutput.seekMap(this);
  }

  /**
   * Attempts to extract the next sample in the current mdat atom for the specified track.
   *
   * <p>Returns {@link #RESULT_SEEK} if the source should be reloaded from the position in {@code
   * positionHolder}.
   *
   * <p>Returns {@link #RESULT_END_OF_INPUT} if no samples are left. Otherwise, returns {@link
   * #RESULT_CONTINUE}.
   *
   * @param input The {@link ExtractorInput} from which to read data.
   * @param positionHolder If {@link #RESULT_SEEK} is returned, this holder is updated to hold the
   *     position of the required data.
   * @return One of the {@code RESULT_*} flags in {@link Extractor}.
   * @throws IOException If an error occurs reading from the input.
   */
  private int readSample(ExtractorInput input, PositionHolder positionHolder) throws IOException {
    long inputPosition = input.getPosition();
    if (sampleTrackIndex == C.INDEX_UNSET) {
      sampleTrackIndex = getTrackIndexOfNextReadSample(inputPosition);
      if (sampleTrackIndex == C.INDEX_UNSET) {
        return RESULT_END_OF_INPUT;
      }
    }
    Mp4Track track = castNonNull(tracks)[sampleTrackIndex];
    TrackOutput trackOutput = track.trackOutput;
    int sampleIndex = track.sampleIndex;
    long position = track.sampleTable.offsets[sampleIndex];
    int sampleSize = track.sampleTable.sizes[sampleIndex];
    long skipAmount = position - inputPosition + sampleBytesRead;
    if (skipAmount < 0 || skipAmount >= RELOAD_MINIMUM_SEEK_DISTANCE) {
      positionHolder.position = position;
      return RESULT_SEEK;
    }
    if (track.track.sampleTransformation == Track.TRANSFORMATION_CEA608_CDAT) {
      // The sample information is contained in a cdat atom. The header must be discarded for
      // committing.
      skipAmount += Atom.HEADER_SIZE;
      sampleSize -= Atom.HEADER_SIZE;
    }
    input.skipFully((int) skipAmount);
    if (track.track.nalUnitLengthFieldLength != 0) {
      // Zero the top three bytes of the array that we'll use to decode nal unit lengths, in case
      // they're only 1 or 2 bytes long.
      byte[] nalLengthData = nalLength.getData();
      nalLengthData[0] = 0;
      nalLengthData[1] = 0;
      nalLengthData[2] = 0;
      int nalUnitLengthFieldLength = track.track.nalUnitLengthFieldLength;
      int nalUnitLengthFieldLengthDiff = 4 - track.track.nalUnitLengthFieldLength;
      // NAL units are length delimited, but the decoder requires start code delimited units.
      // Loop until we've written the sample to the track output, replacing length delimiters with
      // start codes as we encounter them.
      while (sampleBytesWritten < sampleSize) {
        if (sampleCurrentNalBytesRemaining == 0) {
          // Read the NAL length so that we know where we find the next one.
          input.readFully(nalLengthData, nalUnitLengthFieldLengthDiff, nalUnitLengthFieldLength);
          sampleBytesRead += nalUnitLengthFieldLength;
          nalLength.setPosition(0);
          int nalLengthInt = nalLength.readInt();
          if (nalLengthInt < 0) {
            throw new ParserException("Invalid NAL length");
          }
          sampleCurrentNalBytesRemaining = nalLengthInt;
          // Write a start code for the current NAL unit.
          nalStartCode.setPosition(0);
          trackOutput.sampleData(nalStartCode, 4);
          sampleBytesWritten += 4;
          sampleSize += nalUnitLengthFieldLengthDiff;
        } else {
          // Write the payload of the NAL unit.
          int writtenBytes = trackOutput.sampleData(input, sampleCurrentNalBytesRemaining, false);
          sampleBytesRead += writtenBytes;
          sampleBytesWritten += writtenBytes;
          sampleCurrentNalBytesRemaining -= writtenBytes;
        }
      }
    } else {
      if (MimeTypes.AUDIO_AC4.equals(track.track.format.sampleMimeType)) {
        if (sampleBytesWritten == 0) {
          Ac4Util.getAc4SampleHeader(sampleSize, scratch);
          trackOutput.sampleData(scratch, Ac4Util.SAMPLE_HEADER_SIZE);
          sampleBytesWritten += Ac4Util.SAMPLE_HEADER_SIZE;
        }
        sampleSize += Ac4Util.SAMPLE_HEADER_SIZE;
      }
      while (sampleBytesWritten < sampleSize) {
        int writtenBytes = trackOutput.sampleData(input, sampleSize - sampleBytesWritten, false);
        sampleBytesRead += writtenBytes;
        sampleBytesWritten += writtenBytes;
        sampleCurrentNalBytesRemaining -= writtenBytes;
      }
    }
    trackOutput.sampleMetadata(track.sampleTable.timestampsUs[sampleIndex],
        track.sampleTable.flags[sampleIndex], sampleSize, 0, null);
    track.sampleIndex++;
    sampleTrackIndex = C.INDEX_UNSET;
    sampleBytesRead = 0;
    sampleBytesWritten = 0;
    sampleCurrentNalBytesRemaining = 0;
    return RESULT_CONTINUE;
  }

  /**
   * Returns the index of the track that contains the next sample to be read, or {@link
   * C#INDEX_UNSET} if no samples remain.
   *
   * <p>The preferred choice is the sample with the smallest offset not requiring a source reload,
   * or if not available the sample with the smallest overall offset to avoid subsequent source
   * reloads.
   *
   * <p>To deal with poor sample interleaving, we also check whether the required memory to catch up
   * with the next logical sample (based on sample time) exceeds {@link
   * #MAXIMUM_READ_AHEAD_BYTES_STREAM}. If this is the case, we continue with this sample even
   * though it may require a source reload.
   */
  private int getTrackIndexOfNextReadSample(long inputPosition) {
    long preferredSkipAmount = Long.MAX_VALUE;
    boolean preferredRequiresReload = true;
    int preferredTrackIndex = C.INDEX_UNSET;
    long preferredAccumulatedBytes = Long.MAX_VALUE;
    long minAccumulatedBytes = Long.MAX_VALUE;
    boolean minAccumulatedBytesRequiresReload = true;
    int minAccumulatedBytesTrackIndex = C.INDEX_UNSET;
    for (int trackIndex = 0; trackIndex < castNonNull(tracks).length; trackIndex++) {
      Mp4Track track = tracks[trackIndex];
      int sampleIndex = track.sampleIndex;
      if (sampleIndex == track.sampleTable.sampleCount) {
        continue;
      }
      long sampleOffset = track.sampleTable.offsets[sampleIndex];
      long sampleAccumulatedBytes = castNonNull(accumulatedSampleSizes)[trackIndex][sampleIndex];
      long skipAmount = sampleOffset - inputPosition;
      boolean requiresReload = skipAmount < 0 || skipAmount >= RELOAD_MINIMUM_SEEK_DISTANCE;
      if ((!requiresReload && preferredRequiresReload)
          || (requiresReload == preferredRequiresReload && skipAmount < preferredSkipAmount)) {
        preferredRequiresReload = requiresReload;
        preferredSkipAmount = skipAmount;
        preferredTrackIndex = trackIndex;
        preferredAccumulatedBytes = sampleAccumulatedBytes;
      }
      if (sampleAccumulatedBytes < minAccumulatedBytes) {
        minAccumulatedBytes = sampleAccumulatedBytes;
        minAccumulatedBytesRequiresReload = requiresReload;
        minAccumulatedBytesTrackIndex = trackIndex;
      }
    }
    return minAccumulatedBytes == Long.MAX_VALUE
            || !minAccumulatedBytesRequiresReload
            || preferredAccumulatedBytes < minAccumulatedBytes + MAXIMUM_READ_AHEAD_BYTES_STREAM
        ? preferredTrackIndex
        : minAccumulatedBytesTrackIndex;
  }

  /**
   * Updates every track's sample index to point its latest sync sample before/at {@code timeUs}.
   */
  private void updateSampleIndices(long timeUs) {
    for (Mp4Track track : tracks) {
      TrackSampleTable sampleTable = track.sampleTable;
      int sampleIndex = sampleTable.getIndexOfEarlierOrEqualSynchronizationSample(timeUs);
      if (sampleIndex == C.INDEX_UNSET) {
        // Handle the case where the requested time is before the first synchronization sample.
        sampleIndex = sampleTable.getIndexOfLaterOrEqualSynchronizationSample(timeUs);
      }
      track.sampleIndex = sampleIndex;
    }
  }

  /**
   * Possibly skips the version and flags fields (1+3 byte) of a full meta atom of the {@code
   * input}.
   *
   * <p>Atoms of type {@link Atom#TYPE_meta} are defined to be full atoms which have four additional
   * bytes for a version and a flags field (see 4.2 'Object Structure' in ISO/IEC 14496-12:2005).
   * QuickTime do not have such a full box structure. Since some of these files are encoded wrongly,
   * we can't rely on the file type though. Instead we must check the 8 bytes after the common
   * header bytes ourselves.
   */
  private void maybeSkipRemainingMetaAtomHeaderBytes(ExtractorInput input) throws IOException {
    scratch.reset(8);
    // Peek the next 8 bytes which can be either
    // (iso) [1 byte version + 3 bytes flags][4 byte size of next atom]
    // (qt)  [4 byte size of next atom      ][4 byte hdlr atom type   ]
    // In case of (iso) we need to skip the next 4 bytes.
    input.peekFully(scratch.getData(), 0, 8);
    scratch.skipBytes(4);
    if (scratch.readInt() == Atom.TYPE_hdlr) {
      input.resetPeekPosition();
    } else {
      input.skipFully(4);
    }
  }

  /**
   * For each sample of each track, calculates accumulated size of all samples which need to be read
   * before this sample can be used.
   */
  private static long[][] calculateAccumulatedSampleSizes(Mp4Track[] tracks) {
    long[][] accumulatedSampleSizes = new long[tracks.length][];
    int[] nextSampleIndex = new int[tracks.length];
    long[] nextSampleTimesUs = new long[tracks.length];
    boolean[] tracksFinished = new boolean[tracks.length];
    for (int i = 0; i < tracks.length; i++) {
      accumulatedSampleSizes[i] = new long[tracks[i].sampleTable.sampleCount];
      nextSampleTimesUs[i] = tracks[i].sampleTable.timestampsUs[0];
    }
    long accumulatedSampleSize = 0;
    int finishedTracks = 0;
    while (finishedTracks < tracks.length) {
      long minTimeUs = Long.MAX_VALUE;
      int minTimeTrackIndex = -1;
      for (int i = 0; i < tracks.length; i++) {
        if (!tracksFinished[i] && nextSampleTimesUs[i] <= minTimeUs) {
          minTimeTrackIndex = i;
          minTimeUs = nextSampleTimesUs[i];
        }
      }
      int trackSampleIndex = nextSampleIndex[minTimeTrackIndex];
      accumulatedSampleSizes[minTimeTrackIndex][trackSampleIndex] = accumulatedSampleSize;
      accumulatedSampleSize += tracks[minTimeTrackIndex].sampleTable.sizes[trackSampleIndex];
      nextSampleIndex[minTimeTrackIndex] = ++trackSampleIndex;
      if (trackSampleIndex < accumulatedSampleSizes[minTimeTrackIndex].length) {
        nextSampleTimesUs[minTimeTrackIndex] =
            tracks[minTimeTrackIndex].sampleTable.timestampsUs[trackSampleIndex];
      } else {
        tracksFinished[minTimeTrackIndex] = true;
        finishedTracks++;
      }
    }
    return accumulatedSampleSizes;
  }

  /**
   * Adjusts a seek point offset to take into account the track with the given {@code sampleTable},
   * for a given {@code seekTimeUs}.
   *
   * @param sampleTable The sample table to use.
   * @param seekTimeUs The seek time in microseconds.
   * @param offset The current offset.
   * @return The adjusted offset.
   */
  private static long maybeAdjustSeekOffset(
      TrackSampleTable sampleTable, long seekTimeUs, long offset) {
    int sampleIndex = getSynchronizationSampleIndex(sampleTable, seekTimeUs);
    if (sampleIndex == C.INDEX_UNSET) {
      return offset;
    }
    long sampleOffset = sampleTable.offsets[sampleIndex];
    return min(sampleOffset, offset);
  }

  /**
   * Returns the index of the synchronization sample before or at {@code timeUs}, or the index of
   * the first synchronization sample if located after {@code timeUs}, or {@link C#INDEX_UNSET} if
   * there are no synchronization samples in the table.
   *
   * @param sampleTable The sample table in which to locate a synchronization sample.
   * @param timeUs A time in microseconds.
   * @return The index of the synchronization sample before or at {@code timeUs}, or the index of
   *     the first synchronization sample if located after {@code timeUs}, or {@link C#INDEX_UNSET}
   *     if there are no synchronization samples in the table.
   */
  private static int getSynchronizationSampleIndex(TrackSampleTable sampleTable, long timeUs) {
    int sampleIndex = sampleTable.getIndexOfEarlierOrEqualSynchronizationSample(timeUs);
    if (sampleIndex == C.INDEX_UNSET) {
      // Handle the case where the requested time is before the first synchronization sample.
      sampleIndex = sampleTable.getIndexOfLaterOrEqualSynchronizationSample(timeUs);
    }
    return sampleIndex;
  }

  /**
   * Process an ftyp atom to determine whether the media is QuickTime.
   *
   * @param atomData The ftyp atom data.
   * @return Whether the media is QuickTime.
   */
  private static boolean processFtypAtom(ParsableByteArray atomData) {
    atomData.setPosition(Atom.HEADER_SIZE);
    int majorBrand = atomData.readInt();
    if (majorBrand == BRAND_QUICKTIME) {
      return true;
    }
    atomData.skipBytes(4); // minor_version
    while (atomData.bytesLeft() > 0) {
      if (atomData.readInt() == BRAND_QUICKTIME) {
        return true;
      }
    }
    return false;
  }

  /** Returns whether the extractor should decode a leaf atom with type {@code atom}. */
  private static boolean shouldParseLeafAtom(int atom) {
    return atom == Atom.TYPE_mdhd
        || atom == Atom.TYPE_mvhd
        || atom == Atom.TYPE_hdlr
        || atom == Atom.TYPE_stsd
        || atom == Atom.TYPE_stts
        || atom == Atom.TYPE_stss
        || atom == Atom.TYPE_ctts
        || atom == Atom.TYPE_elst
        || atom == Atom.TYPE_stsc
        || atom == Atom.TYPE_stsz
        || atom == Atom.TYPE_stz2
        || atom == Atom.TYPE_stco
        || atom == Atom.TYPE_co64
        || atom == Atom.TYPE_tkhd
        || atom == Atom.TYPE_ftyp
        || atom == Atom.TYPE_udta
        || atom == Atom.TYPE_keys
        || atom == Atom.TYPE_ilst;
  }

  /** Returns whether the extractor should decode a container atom with type {@code atom}. */
  private static boolean shouldParseContainerAtom(int atom) {
    return atom == Atom.TYPE_moov
        || atom == Atom.TYPE_trak
        || atom == Atom.TYPE_mdia
        || atom == Atom.TYPE_minf
        || atom == Atom.TYPE_stbl
        || atom == Atom.TYPE_edts
        || atom == Atom.TYPE_meta;
  }

  private static final class Mp4Track {

    public final Track track;
    public final TrackSampleTable sampleTable;
    public final TrackOutput trackOutput;

    public int sampleIndex;

    public Mp4Track(Track track, TrackSampleTable sampleTable, TrackOutput trackOutput) {
      this.track = track;
      this.sampleTable = sampleTable;
      this.trackOutput = trackOutput;
    }

  }

}
