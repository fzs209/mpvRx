/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.util.Log
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.MpvConfigControlledFeatures
import app.gyrolet.mpvrx.preferences.MpvConfigOverridePolicy
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import java.util.Locale
import java.util.MissingResourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Selects tracks using ordered language or title preferences, with saved selections as fallback.
 * A saved subtitles-off choice remains off. mpv.conf-owned language selection is left to mpv.
 *
 * Based on https://github.com/Chinna95P/mpv-anime-build/blob/main/scripts/track-selector.lua.
 */
class TrackSelector(
  private val audioPreferences: AudioPreferences,
  private val subtitlesPreferences: SubtitlesPreferences,
) {
  private data class Track(
    val id: Int,
    val type: String,
    val lang: String,
    val title: String,
    val isDefault: Boolean,
    val forced: Boolean,
    val hearing: Boolean,
    val external: Boolean,
    val image: Boolean,
    val srcId: Long?,
    val ffIndex: Long?,
    val codec: String,
    val channels: Long?,
    val externalFilename: String,
    val visualImpaired: Boolean,
  )

  suspend fun onFileLoaded(
    hasState: Boolean = false,
    expectedGeneration: Long = PlaybackSession.state.value.generation,
    savedSubtitlesOff: Boolean = false,
    savedAudioSelection: String = "",
  ) = withContext(Dispatchers.IO) {
    var trackCount = 0
    for (attempt in 0 until 20) {
      if (!PlaybackSession.isCurrentGeneration(expectedGeneration)) return@withContext
      trackCount = PlaybackSession.getPropertyInt("track-list/count") ?: 0
      if (trackCount > 0) break
      delay(50)
    }
    if (trackCount == 0) return@withContext
    val tracks = readTracks(expectedGeneration) ?: return@withContext
    // Audio-only media has no video track, but it still needs the same language and manual
    // selection policy. Do not gate audio selection on the presence of a video stream.
    val mediaId = PlaybackSession.state.value.currentItem?.stableId.orEmpty()

    if (!MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AUDIO_TRACK_SELECTION)) {
      AudioTrackSelectionState.restoreSavedSelection(mediaId, expectedGeneration, savedAudioSelection)
      ensureAudioTrackSelected(tracks, mediaId, expectedGeneration)
    }
    // Audio selection also runs for audio-only media, but subtitle autoselection retains
    // its original video-only scope (music files can expose lyrics as subtitle-like tracks).
    if (tracks.any { it.type == "video" && !it.image } &&
      !MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.SUBTITLE_TRACK_SELECTION)
    ) {
      ensureSubtitleTrackSelected(tracks, hasState, expectedGeneration, savedSubtitlesOff)
    }
  }

  private fun readTracks(generation: Long): List<Track>? =
    PlaybackSession.readLoadedMedia(generation) { reader ->
      buildList {
        val count = reader.int("track-list/count") ?: 0
        for (index in 0 until count) {
          val prefix = "track-list/$index"
          val id = reader.int("$prefix/id") ?: continue
          val type = reader.string("$prefix/type") ?: continue
          val isAudio = type == "audio"
          add(
            Track(
              id = id,
              type = type,
              lang = reader.string("$prefix/lang").orEmpty().lowercase(Locale.ROOT),
              title = reader.string("$prefix/title").orEmpty().lowercase(Locale.ROOT),
              isDefault = reader.string("$prefix/default") == "yes",
              forced = reader.string("$prefix/forced") == "yes",
              hearing = reader.string("$prefix/hearing-impaired") == "yes",
              external = reader.string("$prefix/external") == "yes",
              image = reader.string("$prefix/image") == "yes",
              // Identity metadata is only consumed by audio selection. Avoid extra mpv
              // property reads for video and subtitle tracks on every media load.
              srcId = if (isAudio) reader.string("$prefix/src-id")?.toLongOrNull() else null,
              ffIndex = if (isAudio) reader.string("$prefix/ff-index")?.toLongOrNull() else null,
              codec = if (isAudio) reader.string("$prefix/codec").orEmpty() else "",
              channels =
                if (isAudio) {
                  reader.string("$prefix/demux-channel-count")?.toLongOrNull()
                    ?: reader.string("$prefix/audio-channels")?.toLongOrNull()
                } else {
                  null
                },
              externalFilename = if (isAudio) reader.string("$prefix/external-filename").orEmpty() else "",
              visualImpaired = isAudio && reader.string("$prefix/visual-impaired") == "yes",
            ),
          )
        }
      }
    }

  private fun ensureAudioTrackSelected(
    tracks: List<Track>,
    mediaId: String,
    generation: Long,
  ) {
    val audioTracks = tracks.filter { it.type == "audio" }
    if (audioTracks.isEmpty()) return

    val current = getTrackSelectionId("aid")
    val selection = AudioTrackSelectionState.selectionFor(mediaId, generation)
      ?: PersistedAudioSelection.Automatic

    when (selection.mode) {
      AudioSelectionMode.OFF -> {
        selectTrack("aid", 0, generation, "Manual audio disabled")
        return
      }
      AudioSelectionMode.TRACK -> {
        val identity = selection.track
        if (identity != null) {
          val matches = if (!identity.externalUri.isNullOrBlank()) {
            // An external choice may only resolve to the exact re-attached external file.
            // Never fall back to an embedded stream with similar language/title metadata.
            audioTracks.asSequence()
              .filter { it.external }
              .filter { track ->
                AudioTrackSelectionState.matchesExternalAudioPath(
                  mediaId,
                  generation,
                  identity.externalUri,
                  track.externalFilename,
                )
              }
              .map { it to 10_000 }
              .toList()
          } else {
            audioTracks.mapNotNull { track ->
              identity.matchScore(track.identity())?.let { score -> track to score }
            }
          }
          val bestScore = matches.maxOfOrNull { it.second }
          val bestMatches = matches.filter { it.second == bestScore }
          // Ambiguous metadata must not silently restore a different track. Fall back to AUTO.
          val match = bestMatches.singleOrNull()
          if (match != null) {
            if (current != match.id) {
              selectTrack("aid", match.id, generation, "Restored manual audio selection")
            }
            AudioTrackSelectionState.updateResolvedManualTrack(
              mediaId,
              generation,
              match.identity().copy(externalUri = identity.externalUri),
            )
            return
          }
        }
        Log.i(TAG, "Saved manual audio track is unavailable or ambiguous; using automatic selection")
        AudioTrackSelectionState.clearManualSelection(mediaId, generation)
      }
      AudioSelectionMode.AUTO -> Unit
    }

    val cleanTracks = audioTracks.filter(::isCleanAudio)
    // Prefer a clean stream, but don't leave a media item silent when the file only contains
    // commentary/description tracks.
    val candidates = cleanTracks.ifEmpty { audioTracks }
    val preferredLanguages = preferences(audioPreferences.preferredLanguages.get())

    // The preference list is ordered. A match for a later language must never prevent trying
    // an earlier language, and defaults only break ties within the same preference/rank.
    for (preference in preferredLanguages) {
      val matches = candidates.mapNotNull { track ->
        matchRank(track, preference)?.let { rank -> track to rank }
      }
      if (matches.isEmpty()) continue
      val bestRank = matches.minOf { it.second }
      val bestMatches = matches.filter { it.second == bestRank }.map { it.first }
      val match = bestMatches.firstOrNull { it.id == current }
        ?: bestMatches.firstOrNull { it.isDefault }
        ?: bestMatches.first()
      if (current != match.id) {
        selectTrack("aid", match.id, generation, "Preferred audio: $preference")
      }
      return
    }

    // With no preference match, retain mpv's automatic choice when it is usable. Otherwise
    // prefer a file-default stream, then the first available stream.
    if (candidates.any { it.id == current }) return
    val fallback = candidates.firstOrNull { it.isDefault } ?: candidates.firstOrNull()
    if (fallback != null && current != fallback.id) {
      selectTrack("aid", fallback.id, generation, "Audio default fallback")
    }
  }

  private fun isCleanAudio(track: Track): Boolean =
    !track.visualImpaired && !ignoredAudioTitleRegex.containsMatchIn(track.title)

  private fun Track.identity(): AudioTrackIdentity =
    AudioTrackIdentity.fromLoadedTrack(
      srcId = srcId,
      ffIndex = ffIndex,
      language = lang,
      title = title,
      codec = codec,
      channels = channels,
      externalFilename = externalFilename,
    )

  private fun ensureSubtitleTrackSelected(
    tracks: List<Track>,
    hasState: Boolean,
    generation: Long,
    savedSubtitlesOff: Boolean,
  ) {
    val current = getTrackSelectionId("sid")
    if (!subtitlesPreferences.autoEnableSubtitles.get()) {
      if (current > 0) selectTrack("sid", 0, generation, "Automatic subtitles disabled")
      return
    }
    if (hasState && savedSubtitlesOff) {
      if (current > 0) selectTrack("sid", 0, generation, "Saved subtitles-off choice")
      return
    }

    val subTracks = tracks.filter { it.type == "sub" }
    val configuredPreferences = preferences(subtitlesPreferences.preferredLanguages.get())
    if (hasState && configuredPreferences.isEmpty() && subTracks.any { it.id == current }) return
    val preferred = configuredPreferences.ifEmpty {
      preferences(PlaybackSession.getPropertyString("slang").orEmpty()).ifEmpty { listOf("en") }
    }
    val anime = detectAnimeContext(tracks, generation)
    val externalTracks = subTracks.filter { it.external }
    val externalFallback =
      externalTracks.firstOrNull { languageTag(it.lang) == null } ?: externalTracks.firstOrNull()
    val nativeFallback =
      if (anime) {
        subTracks.singleOrNull { it.isDefault }?.takeIf { languageTag(it.lang)?.substringBefore('-') == "ja" }
      } else {
        null
      }
    if (configuredPreferences.isEmpty()) {
      (externalFallback ?: nativeFallback)?.let {
        selectTrack("sid", it.id, generation, "Unconfigured subtitle default")
        return
      }
    }

    // Resolve each preference across both embedded and external tracks before trying the next.
    for (preference in preferred) {
      val match = subTracks.mapNotNull { track -> matchRank(track, preference)?.let { track to it } }
        .minWithOrNull(
          compareBy<Pair<Track, Int>> { if (isCleanSubtitle(it.first)) 0 else 1 }
            .thenBy { it.second }
            .thenBy { if (it.first.external) 0 else 1 }
            .thenBy {
              if (anime && dialogueTitleKeywords.any { keyword -> it.first.title.contains(keyword) }) 0 else 1
            }
            .thenBy { if (it.first.isDefault) 0 else 1 },
        )?.first
      if (match != null) {
        selectTrack("sid", match.id, generation, "Preferred subtitle: $preference")
        return
      }
    }
    if (hasState && subTracks.any { it.id == current }) return

    (externalFallback ?: nativeFallback)?.let {
      selectTrack("sid", it.id, generation, "Subtitle default fallback")
      return
    }
    subTracks.firstOrNull { track ->
      languageTag(track.lang) == null && isCleanSubtitle(track) &&
        dialogueTitleKeywords.any { track.title.contains(it) }
    }?.let {
      selectTrack("sid", it.id, generation, "Untitled-language dialogue fallback")
      return
    }
    subTracks.filter(::isCleanSubtitle).singleOrNull()?.let {
      selectTrack("sid", it.id, generation, "Single clean subtitle fallback")
    }
  }

  private fun selectTrack(property: String, id: Int, generation: Long, reason: String) {
    if (!PlaybackSession.isCurrentGeneration(generation) || getTrackSelectionId(property) == id) return
    val value = if (id > 0) id.toString() else "no"
    if (PlaybackSession.commandForGeneration(generation, "set", property, value)) {
      Log.d(TAG, "$reason ($property=$value)")
    }
  }

  private fun isCleanSubtitle(track: Track): Boolean =
    !track.forced && !track.hearing && ignoredSubtitleTitles.none { track.title.contains(it) }

  private fun detectAnimeContext(tracks: List<Track>, generation: Long): Boolean {
    val metadata = PlaybackSession.readLoadedMedia(generation) { reader ->
      listOf("path", "media-title", "filename").map { reader.string(it).orEmpty().lowercase(Locale.ROOT) }
    } ?: return false
    val (path, title, filename) = metadata
    val description = "$path $title"
    if (listOf("live action", "live-action", "liveaction", "drama", "real person").any { description.contains(it) }) {
      return false
    }
    return listOf("/anime/", "\\anime\\", "donghua", "cartoon", "animation", "3d_anime").any { path.contains(it) } ||
      Regex("\\[.*\\]").containsMatchIn(title) ||
      Regex("\\[[0-9a-f]{8}\\]").containsMatchIn(filename) ||
      tracks.any { it.type == "audio" && languageTag(it.lang)?.substringBefore('-') == "ja" }
  }

  private fun matchRank(track: Track, preference: String): Int? {
    if (preference == "*") return 2
    val preferredTag = languageTag(preference)
    if (preferredTag == null) {
      return 2.takeIf {
        SubtitleTitleMatcher.findBestMatchIndex(listOf(track.title), listOf(preference)) != null
      }
    }
    val trackTag = languageTag(track.lang)
    if (trackTag != null) {
      return when {
        trackTag == preferredTag -> 0
        trackTag.substringBefore('-') == preferredTag.substringBefore('-') -> 1
        else -> null
      }
    }
    val locale = Locale.forLanguageTag(preferredTag)
    val titleKeywords = buildList {
      add(preference)
      add(preferredTag)
      add(preferredTag.substringBefore('-'))
      add(locale.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT))
      add(locale.getDisplayLanguage(locale).lowercase(Locale.ROOT))
      try {
        add(locale.isO3Language)
      } catch (_: MissingResourceException) {
        // Unknown locale names remain eligible for literal title matching above.
      }
    }
    return 2.takeIf { SubtitleTitleMatcher.findBestMatchIndex(listOf(track.title), titleKeywords) != null }
  }

  private fun preferences(value: String): List<String> =
    value.split(',').map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotEmpty).distinct()

  private fun languageTag(value: String): String? {
    val tag = value.trim().lowercase(Locale.ROOT).replace('_', '-')
    val base = languageCodes[tag.substringBefore('-')] ?: return null
    return base + tag.substringAfter('-', "").takeIf(String::isNotEmpty)?.let { "-$it" }.orEmpty()
  }

  private companion object {
    const val TAG = "TrackSelector"
    val ignoredAudioTitleRegex = Regex("""(?:^|[\s\p{Punct}])(commentary|description|audio description|visual impaired|descriptive|adh|comment|extra)(?:$|[\s\p{Punct}])""")
    val ignoredSubtitleTitles = listOf("signs", "songs", "lyrics", "forced", "sdh", "colored", "karaoke")
    val dialogueTitleKeywords = listOf(
      "subtitle", "dialogue", "dialog", "translation", "english", "main", "script", "full", "caption",
    )
    val languageCodes: Map<String, String> by lazy {
      buildMap {
        for (code in Locale.getISOLanguages()) {
          val locale = Locale.forLanguageTag(code)
          put(code, code)
          put(locale.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT), code)
          try {
            put(locale.isO3Language.lowercase(Locale.ROOT), code)
          } catch (_: MissingResourceException) {
            // Some Android locales do not expose an ISO 639-2 code.
          }
        }
        putAll(
          mapOf(
            "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh",
            "cze" to "cs", "dut" to "nl", "fre" to "fr", "geo" to "ka", "ger" to "de",
            "gre" to "el", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms",
            "per" to "fa", "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy",
            "jp" to "ja", "iw" to "he", "in" to "id", "ji" to "yi",
          ),
        )
      }
    }
  }
}
