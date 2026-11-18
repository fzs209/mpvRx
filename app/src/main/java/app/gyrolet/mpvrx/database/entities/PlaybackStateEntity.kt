/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class PlaybackStateEntity(
  @PrimaryKey val mediaTitle: String,
  val lastPosition: Int, // in seconds
  val playbackSpeed: Double,
  val videoZoom: Float = 0f,
  val sid: Int,
  val secondarySid: Int = -1, // Secondary subtitle track ID (-1 means disabled)
  val subDelay: Int,
  val subSpeed: Double,
  val aid: Int, // Audio track ID: >0 = mpv track ID, 0 = user disabled audio, -1 = no saved selection
  val audioDelay: Int,
  val timeRemaining: Int = 0, // in seconds (duration - lastPosition)
  val externalSubtitles: String = "", // Comma-separated list of external subtitle URIs
  val hasBeenWatched: Boolean = false, // Persistent flag: true if video has ever reached the watched threshold
  val newLabelOverride: Boolean? = null,
  // True when [aid] holds a selection the user made by hand in this file's track list, as
  // opposed to a track an automatic step (mpv's language choice, TrackSelector) picked.
  // Only a manual selection is restored ahead of the preferred-language rules on the next
  // open; an automatic result is re-decided on every open.
  val hasManualAudioSelection: Boolean = false,
)
