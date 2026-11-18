/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A persisted user intent, deliberately separate from mpv's current `aid`.
 *
 * `aid` is only an ID in the current track list. It can also represent an automatic mpv
 * choice, so it must never be treated as a durable user preference.
 */
@Serializable
internal enum class AudioSelectionMode {
  AUTO,
  TRACK,
  OFF,
}

@Serializable
internal data class AudioTrackIdentity(
  @SerialName("src_id") val srcId: Long? = null,
  @SerialName("ff_index") val ffIndex: Long? = null,
  val language: String? = null,
  val title: String? = null,
  val codec: String? = null,
  val channels: Long? = null,
  @SerialName("external_filename") val externalFilename: String? = null,
  @SerialName("external_uri") val externalUri: String? = null,
  @SerialName("ytdl_format_id") val ytdlFormatId: String? = null,
  @SerialName("ytdl_format_selector") val ytdlFormatSelector: String? = null,
) {
  /**
   * Scores a candidate without relying on mpv's transient track ID.
   *
   * Strong native identifiers win. Metadata fallback requires multiple independent fields
   * and is accepted only when the best candidate is unique (the caller enforces uniqueness).
   */
  fun matchScore(candidate: AudioTrackIdentity): Int? {
    if (!ytdlFormatId.isNullOrBlank() && !candidate.ytdlFormatId.isNullOrBlank()) {
      return if (ytdlFormatId == candidate.ytdlFormatId) 10_000 else null
    }
    if (srcId != null && candidate.srcId != null) {
      return if (srcId == candidate.srcId) 9_000 else null
    }

    val expectedExternalPath = externalFilename
    if (!expectedExternalPath.isNullOrBlank() && !candidate.externalFilename.isNullOrBlank() &&
      normalizePath(expectedExternalPath) == normalizePath(candidate.externalFilename)
    ) {
      return 8_000
    }

    var score = 0
    var matchedFields = 0

    fun compareText(expected: String?, actual: String?, weight: Int, normalize: (String) -> String = ::normalizeText): Boolean {
      if (expected.isNullOrBlank() || actual.isNullOrBlank()) return true
      if (normalize(expected) != normalize(actual)) return false
      score += weight
      matchedFields++
      return true
    }

    if (!compareText(language, candidate.language, 100, ::normalizeLanguage)) return null
    if (!compareText(title, candidate.title, 80)) return null
    if (!compareText(codec, candidate.codec, 60)) return null

    if (channels != null && candidate.channels != null) {
      if (channels != candidate.channels) return null
      score += 40
      matchedFields++
    }
    if (ffIndex != null && candidate.ffIndex != null && ffIndex == candidate.ffIndex) {
      // A useful tie-breaker, but intentionally not a standalone identity: demuxer mappings
      // can change and mpv documents ff-index as potentially inaccurate for non-lavf demuxers.
      score += 10
    }

    // Metadata is a fallback only when at least two independent properties agree. If the
    // source exposes only one weak field, AUTO is safer than restoring a possibly different
    // stream that happens to share its language or codec.
    return score.takeIf { matchedFields >= 2 }
  }

  companion object {
    fun from(track: TrackNode): AudioTrackIdentity =
      AudioTrackIdentity(
        srcId = track.srcId,
        ffIndex = track.ffIndex,
        language = track.effectiveLang,
        title = track.effectiveTitle,
        codec = track.codec,
        channels = track.demuxChannelCount ?: track.audioChannels,
        externalFilename = track.externalFilename,
        ytdlFormatId = track.ytdlFormatId,
      )

    internal fun fromLoadedTrack(
      srcId: Long?,
      ffIndex: Long?,
      language: String?,
      title: String?,
      codec: String?,
      channels: Long?,
      externalFilename: String?,
    ): AudioTrackIdentity =
      AudioTrackIdentity(
        srcId = srcId,
        ffIndex = ffIndex,
        language = language,
        title = title,
        codec = codec,
        channels = channels,
        externalFilename = externalFilename,
        ytdlFormatId = extractYtdlFormatId(title),
      )
  }
}

@Serializable
internal data class PersistedAudioSelection(
  val mode: AudioSelectionMode = AudioSelectionMode.AUTO,
  val track: AudioTrackIdentity? = null,
) {
  companion object {
    val Automatic = PersistedAudioSelection()
    val Off = PersistedAudioSelection(mode = AudioSelectionMode.OFF)
    fun track(identity: AudioTrackIdentity) =
      PersistedAudioSelection(mode = AudioSelectionMode.TRACK, track = identity)
  }
}

internal object AudioTrackSelectionCodec {
  private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
  }

  fun encodeSelection(selection: PersistedAudioSelection): String =
    if (selection.mode == AudioSelectionMode.AUTO) "" else json.encodeToString(selection)

  fun decodeSelection(value: String?): PersistedAudioSelection? =
    value?.takeIf(String::isNotBlank)?.let { runCatching { json.decodeFromString<PersistedAudioSelection>(it) }.getOrNull() }

  fun encodeUris(uris: Collection<String>): String =
    if (uris.isEmpty()) "" else json.encodeToString(uris.toList())

  fun decodeUris(value: String?): List<String> =
    value?.takeIf(String::isNotBlank)
      ?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
      .orEmpty()
      .filter(String::isNotBlank)
      .distinct()
}

private val WHITESPACE_REGEX = Regex("\\s+")

private fun normalizeText(value: String): String =
  value.trim().lowercase(java.util.Locale.ROOT).replace(WHITESPACE_REGEX, " ")

private fun normalizeLanguage(value: String): String =
  normalizeText(value).replace('_', '-')

private fun normalizePath(value: String): String =
  value.trim().replace('\\', '/').trimEnd('/')

private val YTDL_FORMAT_ID_REGEX = Regex("""^\s*([^\s]+)\s+-\s+""")
private val YTDL_MUXED_FORMAT_ID_REGEX = Regex("""(?:^|\s)muxed-([^\s]+)(?:\s|$)""")

private fun extractYtdlFormatId(title: String?): String? =
  title?.let { value ->
    YTDL_FORMAT_ID_REGEX.find(value)?.groupValues?.getOrNull(1)
      ?: YTDL_MUXED_FORMAT_ID_REGEX.find(value)?.groupValues?.getOrNull(1)
  }

/**
 * Runtime half of the persisted audio intent.
 *
 * It is process-wide because PlaybackSession can outlive PlayerActivity and be handed to
 * MediaPlaybackService. All state is keyed by the stable media identifier and load generation;
 * title changes and delayed callbacks therefore cannot move external tracks between files.
 */
internal object AudioTrackSelectionState {
  private enum class Origin {
    NONE,
    RESTORED,
    SESSION,
  }

  private val lock = Any()
  private var mediaId: String = ""
  private var generation: Long = 0L
  private var selection: PersistedAudioSelection = PersistedAudioSelection.Automatic
  private var origin: Origin = Origin.NONE
  private var selectionResolved: Boolean = false
  private val externalUris = linkedSetOf<String>()
  private var externalUrisResolved: Boolean = false
  private val attachedExternalUris = linkedSetOf<String>()
  private val externalPaths = linkedMapOf<String, String>()

  fun beginLoad(
    nextMediaId: String,
    nextGeneration: Long,
  ) {
    synchronized(lock) {
      if (mediaId != nextMediaId) {
        selection = PersistedAudioSelection.Automatic
        origin = Origin.NONE
        selectionResolved = false
        externalUrisResolved = false
        externalUris.clear()
      }
      mediaId = nextMediaId
      generation = nextGeneration
      // The URI list belongs to the media and survives a format reload. Which tracks are
      // currently attached does not: a new load starts with a fresh mpv track list.
      attachedExternalUris.clear()
      externalPaths.clear()
    }
  }

  fun restoreSavedSelection(
    expectedMediaId: String,
    expectedGeneration: Long,
    encoded: String?,
  ) {
    val saved = AudioTrackSelectionCodec.decodeSelection(encoded)
    synchronized(lock) {
      if (!matchesLocked(expectedMediaId, expectedGeneration)) return
      if (origin == Origin.NONE) {
        selection = saved ?: PersistedAudioSelection.Automatic
        origin = Origin.RESTORED
      }
      selectionResolved = true
    }
  }

  fun selectionFor(
    expectedMediaId: String,
    expectedGeneration: Long,
  ): PersistedAudioSelection? =
    synchronized(lock) {
      selection.takeIf { matchesLocked(expectedMediaId, expectedGeneration) }
    }

  fun markManualTrack(
    expectedMediaId: String,
    expectedGeneration: Long,
    identity: AudioTrackIdentity,
  ) {
    synchronized(lock) {
      if (!matchesLocked(expectedMediaId, expectedGeneration)) return
      selection = PersistedAudioSelection.track(identity)
      origin = Origin.SESSION
      selectionResolved = true
    }
  }

  fun markManualOff(
    expectedMediaId: String,
    expectedGeneration: Long,
  ) {
    synchronized(lock) {
      if (!matchesLocked(expectedMediaId, expectedGeneration)) return
      selection = PersistedAudioSelection.Off
      origin = Origin.SESSION
      selectionResolved = true
    }
  }

  fun updateResolvedManualTrack(
    expectedMediaId: String,
    expectedGeneration: Long,
    identity: AudioTrackIdentity,
  ) {
    synchronized(lock) {
      if (!matchesLocked(expectedMediaId, expectedGeneration) ||
        selection.mode != AudioSelectionMode.TRACK
      ) return
      selection = PersistedAudioSelection.track(
        identity.copy(
          externalUri = selection.track?.externalUri,
          ytdlFormatSelector = selection.track?.ytdlFormatSelector,
        ),
      )
    }
  }

  fun clearManualSelection(
    expectedMediaId: String,
    expectedGeneration: Long,
  ) {
    synchronized(lock) {
      if (!matchesLocked(expectedMediaId, expectedGeneration)) return
      selection = PersistedAudioSelection.Automatic
      origin = Origin.RESTORED
      selectionResolved = true
    }
  }

  /** A video/program change is automatic; don't preserve a different paired audio track as manual. */
  fun clearManualTrackWhenAudioChanges(
    expectedMediaId: String,
    expectedGeneration: Long,
    oldAudioId: Int,
    newAudioId: Int,
  ) {
    synchronized(lock) {
      if (!matchesLocked(expectedMediaId, expectedGeneration) ||
        selection.mode != AudioSelectionMode.TRACK ||
        oldAudioId == newAudioId
      ) return
      selection = PersistedAudioSelection.Automatic
      origin = Origin.RESTORED
      selectionResolved = true
    }
  }

  fun rememberExternalUris(
    expectedMediaId: String,
    expectedGeneration: Long,
    uris: Collection<String>,
  ) {
    synchronized(lock) {
      if (!matchesLocked(expectedMediaId, expectedGeneration)) return
      uris.filter(String::isNotBlank).forEach(externalUris::add)
      externalUrisResolved = true
    }
  }

  fun registerExternalAudio(
    expectedMediaId: String,
    expectedGeneration: Long,
    uri: String,
    path: String,
  ) {
    synchronized(lock) {
      if (!matchesLocked(expectedMediaId, expectedGeneration)) return
      externalUris += uri
      externalUrisResolved = true
      attachedExternalUris += uri
      if (path.isNotBlank()) externalPaths[uri] = path
    }
  }

  fun isExternalAudioAttached(
    expectedMediaId: String,
    expectedGeneration: Long,
    uri: String,
  ): Boolean =
    synchronized(lock) {
      matchesLocked(expectedMediaId, expectedGeneration) && uri in attachedExternalUris
    }

  fun matchesExternalAudioPath(
    expectedMediaId: String,
    expectedGeneration: Long,
    uri: String?,
    candidatePath: String,
  ): Boolean =
    synchronized(lock) {
      if (!matchesLocked(expectedMediaId, expectedGeneration) || uri.isNullOrBlank()) return@synchronized false
      val expectedPath = externalPaths[uri] ?: return@synchronized false
      normalizePath(expectedPath) == normalizePath(candidatePath)
    }

  fun externalUriForPath(
    expectedMediaId: String,
    expectedGeneration: Long,
    path: String?,
  ): String? =
    synchronized(lock) {
      if (!matchesLocked(expectedMediaId, expectedGeneration) || path.isNullOrBlank()) return@synchronized null
      externalPaths.entries.firstOrNull { normalizePath(it.value) == normalizePath(path) }?.key
    }

  fun selectionForSave(
    expectedMediaId: String,
    expectedGeneration: Long,
  ): String =
    synchronized(lock) {
      if (!matchesLocked(expectedMediaId, expectedGeneration)) "" else AudioTrackSelectionCodec.encodeSelection(selection)
    }

  fun isSelectionResolved(
    expectedMediaId: String,
    expectedGeneration: Long,
  ): Boolean =
    synchronized(lock) {
      matchesLocked(expectedMediaId, expectedGeneration) && selectionResolved
    }

  /**
   * Includes URIs introduced in this live session, even when a same-media yt-dlp reload
   * happens before the periodic playback-state write reaches Room.
   */
  fun externalUrisForRestore(
    expectedMediaId: String,
    expectedGeneration: Long,
    persistedUris: Collection<String>,
  ): List<String> =
    synchronized(lock) {
      if (!matchesLocked(expectedMediaId, expectedGeneration)) return@synchronized persistedUris.distinct()
      (persistedUris + externalUris).filter(String::isNotBlank).distinct()
    }

  fun areExternalUrisResolved(
    expectedMediaId: String,
    expectedGeneration: Long,
  ): Boolean =
    synchronized(lock) {
      matchesLocked(expectedMediaId, expectedGeneration) && externalUrisResolved
    }

  fun externalUrisForSave(
    expectedMediaId: String,
    expectedGeneration: Long,
  ): String =
    synchronized(lock) {
      if (!matchesLocked(expectedMediaId, expectedGeneration)) "" else AudioTrackSelectionCodec.encodeUris(externalUris)
    }

  private fun matchesLocked(expectedMediaId: String, expectedGeneration: Long): Boolean =
    expectedMediaId.isNotBlank() && mediaId == expectedMediaId && generation == expectedGeneration
}

/** Reads an internal or external audio track descriptor from the current generation. */
internal fun readLoadedAudioTrackIdentity(
  expectedGeneration: Long,
  trackId: Int,
): AudioTrackIdentity? {
  if (trackId <= 0) return null
  return PlaybackSession.readLoadedMedia(expectedGeneration) { reader ->
    val count = reader.int("track-list/count") ?: 0
    for (index in 0 until count) {
      val prefix = "track-list/$index"
      if (reader.int("$prefix/id") != trackId || reader.string("$prefix/type") != "audio") continue
      return@readLoadedMedia AudioTrackIdentity.fromLoadedTrack(
        srcId = reader.string("$prefix/src-id")?.toLongOrNull(),
        ffIndex = reader.string("$prefix/ff-index")?.toLongOrNull(),
        language = reader.string("$prefix/lang"),
        title = reader.string("$prefix/title"),
        codec = reader.string("$prefix/codec"),
        channels = reader.string("$prefix/demux-channel-count")?.toLongOrNull()
          ?: reader.string("$prefix/audio-channels")?.toLongOrNull(),
        externalFilename = reader.string("$prefix/external-filename"),
      )
    }
    null
  }
}

/**
 * Finds the external track created by audio-add using its resolved source path.
 * Prefer the currently selected track when one of several streams shares that path.
 */
internal fun readLoadedExternalAudioTrackIdentity(
  expectedGeneration: Long,
  sourcePath: String,
): Pair<Int, AudioTrackIdentity>? {
  if (sourcePath.isBlank()) return null
  return PlaybackSession.readLoadedMedia(expectedGeneration) { reader ->
    val count = reader.int("track-list/count") ?: 0
    val currentId = reader.string("aid")?.toIntOrNull() ?: 0
    var firstMatch: Pair<Int, AudioTrackIdentity>? = null
    for (index in 0 until count) {
      val prefix = "track-list/$index"
      if (reader.string("$prefix/type") != "audio" ||
        reader.string("$prefix/external") != "yes"
      ) continue
      val externalFilename = reader.string("$prefix/external-filename").orEmpty()
      if (externalFilename.isBlank() ||
        normalizePath(externalFilename) != normalizePath(sourcePath)
      ) continue
      val id = reader.int("$prefix/id") ?: continue
      val identity =
        AudioTrackIdentity.fromLoadedTrack(
          srcId = reader.string("$prefix/src-id")?.toLongOrNull(),
          ffIndex = reader.string("$prefix/ff-index")?.toLongOrNull(),
          language = reader.string("$prefix/lang"),
          title = reader.string("$prefix/title"),
          codec = reader.string("$prefix/codec"),
          channels = reader.string("$prefix/demux-channel-count")?.toLongOrNull()
            ?: reader.string("$prefix/audio-channels")?.toLongOrNull(),
          externalFilename = externalFilename,
        )
      val match = id to identity
      if (id == currentId) return@readLoadedMedia match
      if (firstMatch == null) firstMatch = match
    }
    firstMatch
  }
}
