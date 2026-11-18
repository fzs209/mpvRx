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
  // the choice counts as a manual selection for the file it was made in. Automatic steps
  // (saved-state restore, TrackSelector) set the property through generation-scoped
  // commands instead and never reach this function.
  if (property == "aid") ManualAudioSelectionTracker.markCurrent()
  val value = id?.takeIf { it > 0 }?.toString() ?: "no"
  PlaybackSession.setPropertyString(property, value)
}

/**
 * Remembers which file the user last picked an audio track for by hand, so the next
 * playback-state save for that file can store the choice as a manual selection.
 *
 * Only one file can be playing at a time, so a single identifier is enough: a save that
 * runs while the marked file is the loaded one picks the mark up, and
 * PlaybackStatePersistence keeps the stored flag alive on later saves for as long as the
 * saved track itself does not change.
 */
internal object ManualAudioSelectionTracker {
  @Volatile
  private var markedIdentifier: String? = null

  fun markCurrent() {
    val identifier = PlaybackSession.state.value.currentItem?.stableId
    if (!identifier.isNullOrBlank()) markedIdentifier = identifier
  }

  fun isMarkedFor(identifier: String): Boolean =
    identifier.isNotBlank() && markedIdentifier == identifier
}
