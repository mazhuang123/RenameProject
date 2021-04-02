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
package com.migu.player.trackselection;

import android.os.SystemClock;
import android.support.annotation.Nullable;

import com.migu.player.C;
import com.migu.player.source.TrackGroup;
import com.migu.player.source.chunk.MediaChunk;
import com.migu.player.source.chunk.MediaChunkIterator;
import com.migu.player.upstream.BandwidthMeter;

import java.util.List;
import java.util.Random;


/**
 * A {@link TrackSelection} whose selected track is updated randomly.
 */
public final class RandomTrackSelection extends BaseTrackSelection {

  /**
   * Factory for {@link RandomTrackSelection} instances.
   */
  public static final class Factory implements TrackSelection.Factory {

    private final Random random;

    public Factory() {
      random = new Random();
    }

    /**
     * @param seed A seed for the {@link Random} instance used by the factory.
     */
    public Factory(int seed) {
      random = new Random(seed);
    }

    @Override
    public  TrackSelection[] createTrackSelections(
         Definition[] definitions, BandwidthMeter bandwidthMeter) {
      return TrackSelectionUtil.createTrackSelectionsForDefinitions(
          definitions,
          definition -> new RandomTrackSelection(definition.group, definition.tracks, random));
    }
  }

  private final Random random;

  private int selectedIndex;

  /**
   * @param group The {@link TrackGroup}. Must not be null.
   * @param tracks The indices of the selected tracks within the {@link TrackGroup}. Must not be
   *     null or empty. May be in any order.
   */
  public RandomTrackSelection(TrackGroup group, int... tracks) {
    super(group, tracks);
    random = new Random();
    selectedIndex = random.nextInt(length);
  }

  /**
   * @param group The {@link TrackGroup}. Must not be null.
   * @param tracks The indices of the selected tracks within the {@link TrackGroup}. Must not be
   *     null or empty. May be in any order.
   * @param seed A seed for the {@link Random} instance used to update the selected track.
   */
  public RandomTrackSelection(TrackGroup group, int[] tracks, long seed) {
    this(group, tracks, new Random(seed));
  }

  /**
   * @param group The {@link TrackGroup}. Must not be null.
   * @param tracks The indices of the selected tracks within the {@link TrackGroup}. Must not be
   *     null or empty. May be in any order.
   * @param random A source of random numbers.
   */
  public RandomTrackSelection(TrackGroup group, int[] tracks, Random random) {
    super(group, tracks);
    this.random = random;
    selectedIndex = random.nextInt(length);
  }

  @Override
  public void updateSelectedTrack(
      long playbackPositionUs,
      long bufferedDurationUs,
      long availableDurationUs,
      List<? extends MediaChunk> queue,
      MediaChunkIterator[] mediaChunkIterators) {
    // Count the number of allowed formats.
    long nowMs = SystemClock.elapsedRealtime();
    int allowedFormatCount = 0;
    for (int i = 0; i < length; i++) {
      if (!isBlacklisted(i, nowMs)) {
        allowedFormatCount++;
      }
    }

    selectedIndex = random.nextInt(allowedFormatCount);
    if (allowedFormatCount != length) {
      // Adjust the format index to account for excluded formats.
      allowedFormatCount = 0;
      for (int i = 0; i < length; i++) {
        if (!isBlacklisted(i, nowMs) && selectedIndex == allowedFormatCount++) {
          selectedIndex = i;
          return;
        }
      }
    }
  }

  @Override
  public int getSelectedIndex() {
    return selectedIndex;
  }

  @Override
  public int getSelectionReason() {
    return C.SELECTION_REASON_ADAPTIVE;
  }

  @Override
  @Nullable
  public Object getSelectionData() {
    return null;
  }

}