/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player


internal fun getTrackSelectionId(property: String): Int =
  runCatching { PlaybackSession.getPropertyString(property)?.toIntOrNull() ?: 0 }
    .getOrDefault(0)

internal fun setTrackSelectionId(
  property: String,
  id: Int?,
) {
  // Every caller that sets the audio track through here is a user-facing track list, so
  // the choice counts as a manual selection for the file it was made in, recorded with
  // the exact track it selects (id <= 0 or null means the user turned audio off).
  // Automatic steps (saved-state restore, TrackSelector) set the property through
  // generation-scoped commands instead and never reach this function.
  if (property == "aid") ManualAudioSelectionTracker.markCurrent(id?.takeIf { it > 0 } ?: 0)
  val value = id?.takeIf { it > 0 }?.toString() ?: "no"
  PlaybackSession.setPropertyString(property, value)
}

/**
 * Remembers which audio track the user last picked by hand, and in which file, so the
 * next playback-state save for that file can store the choice as a manual selection.
 *
 * The mark is bound to the picked track, not just to the file: if an automatic step
 * changes the audio afterwards (a video-quality change pairing a different audio track,
 * a format reload), the track found at save time no longer matches the mark and the
 * change is not stored as the user's own choice.
 *
 * Only one file can be playing at a time, so a single mark is enough: a save that runs
 * while the marked file is the loaded one picks the mark up, and
 * PlaybackStatePersistence keeps the stored flag alive on later saves for as long as the
 * saved track itself does not change.
 */
internal object ManualAudioSelectionTracker {
  @Volatile
  private var markedIdentifier: String? = null

  @Volatile
  private var markedTrackId: Int = -1

  /** Records a manual pick of [trackId] (0 = audio off) in the file now playing. */
  fun markCurrent(trackId: Int) {
    val identifier = PlaybackSession.state.value.currentItem?.stableId
    if (!identifier.isNullOrBlank()) {
      markedIdentifier = identifier
      markedTrackId = trackId
    }
  }

  /** True when the user picked exactly [trackId] by hand for [identifier] this session. */
  fun isMarkedFor(
    identifier: String,
    trackId: Int,
  ): Boolean =
    identifier.isNotBlank() && markedIdentifier == identifier && markedTrackId == trackId

  /** True when the user picked any audio track by hand for [identifier] this session. */
  fun hasMarkFor(identifier: String): Boolean =
    identifier.isNotBlank() && markedIdentifier == identifier
}
