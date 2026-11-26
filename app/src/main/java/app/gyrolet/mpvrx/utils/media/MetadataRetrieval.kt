/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.gyrolet.mpvrx.database.repository.VideoMetadataCacheRepository
import app.gyrolet.mpvrx.domain.archive.ZipArchiveMedia
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.utils.logging.Log
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import app.gyrolet.mpvrx.utils.storage.VideoScanUtils
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Lazy metadata retrieval system
 * Only extracts detailed metadata (duration, framerate, resolution) when chips are enabled
 *
 * This improves app startup performance by deferring expensive metadata extraction
 * until the user actually needs to see the information.
 */
object MetadataRetrieval {
  private const val TAG = "MetadataRetrieval"

  /** How many content-URI probes may run at once during a folder enrichment pass. */
  private const val CONTENT_PROBE_PARALLELISM = 4

  /** How many file paths to bind into one MediaStore IN query. */
  private const val MEDIASTORE_QUERY_CHUNK = 400

  /**
   * Checks if video-specific metadata is needed
   */
  fun isVideoMetadataNeeded(browserPreferences: BrowserPreferences): Boolean =
    browserPreferences.showDurationField.get() ||
      browserPreferences.showResolutionChip.get() ||
      browserPreferences.showFramerateInResolution.get() ||
      browserPreferences.showSubtitleIndicator.get() ||
      browserPreferences.showCodecSupportIndicator.get()

  /**
   * Checks if folder-specific metadata is needed
   */
  fun isFolderMetadataNeeded(browserPreferences: BrowserPreferences): Boolean =
    browserPreferences.showTotalDurationChip.get()

  fun needsVideoMetadata(video: Video, preferences: BrowserPreferences): Boolean {
    if (ZipArchiveMedia.isPlaybackUri(video.uri.toString())) return false
    return (preferences.showDurationField.get() && video.duration <= 0L) ||
      (!video.isAudio && (
        (preferences.showResolutionChip.get() && (video.width == 0 || video.height == 0)) ||
          (preferences.showFramerateInResolution.get() && (!video.fps.isFinite() || video.fps <= 0f)) ||
          (preferences.showSubtitleIndicator.get() && video.subtitleCodec.isEmpty()) ||
          (preferences.showCodecSupportIndicator.get() && video.videoCodec.isBlank())
        ))
  }

  internal fun withMetadata(video: Video, metadata: MediaInfoOps.VideoMetadata): Video {
    val duration = metadata.durationMs.takeIf { it > 0L } ?: video.duration
    // MediaStore's dimensions are what the library and resolution chip report; a probe only
    // fills them in when the index has none, so enrichment never changes a shown resolution.
    val hasIndexedSize = video.width > 0 && video.height > 0
    val width = if (hasIndexedSize) video.width else metadata.width.takeIf { it > 0 } ?: video.width
    val height = if (hasIndexedSize) video.height else metadata.height.takeIf { it > 0 } ?: video.height
    val fps = metadata.fps.takeIf { it.isFinite() && it > 0f } ?: video.fps
    return video.copy(
      duration = duration,
      durationFormatted = if (duration > 0L) formatDuration(duration) else "",
      width = width,
      height = height,
      fps = fps,
      resolution = VideoScanUtils.formatResolutionWithFps(width, height, fps),
      hasEmbeddedSubtitles = metadata.hasEmbeddedSubtitles || video.hasEmbeddedSubtitles,
      subtitleCodec = metadata.subtitleCodec.ifBlank { video.subtitleCodec },
      videoCodec = metadata.videoCodec.ifBlank { video.videoCodec },
      videoCodecMimeType = metadata.videoCodecMimeType.ifBlank { video.videoCodecMimeType },
    )
  }

  suspend fun restoreCachedContentMetadata(
    videos: List<Video>,
    metadataCache: VideoMetadataCacheRepository,
  ): List<Video> {
    val cached = metadataCache.getCachedContentMetadata(videos)
    return videos.map { video -> cached[video.path]?.let { withMetadata(video, it) } ?: video }
  }

  /**
   * Enriches a list of videos with metadata only if needed
   * Processes in batches for better performance
   */
  suspend fun enrichVideosIfNeeded(
    context: Context,
    videos: List<Video>,
    browserPreferences: BrowserPreferences,
    metadataCache: VideoMetadataCacheRepository,
  ): List<Video> =
    withContext(Dispatchers.IO) {
      val metadataChipsEnabled = isVideoMetadataNeeded(browserPreferences)
      if (!metadataChipsEnabled) {
        return@withContext videos
      }

      // Filter videos that need metadata. Duration/resolution are filled from the
      // database cache and MediaStore first; only fields neither source has
      // (FPS, subtitle and codec information) still require a per-file probe.
      val videosNeedingMetadata = videos.filter { needsVideoMetadata(it, browserPreferences) }

      if (videosNeedingMetadata.isEmpty()) {
        return@withContext videos
      }

      Log.d(TAG, "Enriching ${videosNeedingMetadata.size} videos with metadata")

      val metadataMap =
        extractMetadataByVideoPath(
          context = context,
          videos = videosNeedingMetadata,
          metadataCache = metadataCache,
          browserPreferences = browserPreferences,
        )

      // Update videos with metadata
      videos.map { video ->
        metadataMap[video.path]?.let { withMetadata(video, it) } ?: video
      }
    }

  private suspend fun extractMetadataByVideoPath(
    context: Context,
    videos: List<Video>,
    metadataCache: VideoMetadataCacheRepository,
    browserPreferences: BrowserPreferences,
  ): Map<String, MediaInfoOps.VideoMetadata> {
    val metadataByVideoPath = mutableMapOf<String, MediaInfoOps.VideoMetadata>()
    val includeVideoCodec = browserPreferences.showCodecSupportIndicator.get()

    // 1) Serve everything the database already knows with one batch query.
    val contentVideos = videos.filter { it.uri.scheme == "content" }
    if (contentVideos.isNotEmpty()) {
      metadataByVideoPath.putAll(metadataCache.getCachedContentMetadata(contentVideos))
    }

    // 2) Fill duration/resolution/size from MediaStore with one batch query. The
    // system media scanner has already indexed these fields, so this replaces a
    // per-file open + parse for every video whose only missing fields are ones
    // MediaStore carries (the common case: duration/resolution chips). MediaStore
    // has no FPS, subtitle or codec columns, so videos needing those still fall
    // through to probing below.
    val mediaStoreMetadata = queryMediaStoreMetadata(context, videos)
    if (mediaStoreMetadata.isNotEmpty()) {
      Log.d(TAG, "MediaStore provided metadata for ${mediaStoreMetadata.size} videos")
      videos.forEach { video ->
        mergeMetadata(metadataByVideoPath[video.path], mediaStoreMetadata[video.path])
          ?.let { merged -> metadataByVideoPath[video.path] = merged }
      }
    }

    // 3) Probe only the videos that still miss a field an enabled chip needs.
    val videosNeedingProbe =
      videos.filter { video ->
        stillNeedsProbe(video, metadataByVideoPath[video.path], browserPreferences)
      }
    if (videosNeedingProbe.isEmpty()) {
      return metadataByVideoPath
    }
    Log.d(TAG, "Probing ${videosNeedingProbe.size} of ${videos.size} videos for remaining fields")

    val contentNeedingProbe = videosNeedingProbe.filter { it.uri.scheme == "content" }
    // Probing is the slow part of a folder scan and used to run strictly one file at a
    // time for content URIs; run a few probes concurrently, like the local-file batch.
    contentNeedingProbe.chunked(CONTENT_PROBE_PARALLELISM).forEach { batch ->
      coroutineScope {
        batch
          .map { video ->
            async {
              video.path to
                metadataCache.getOrExtractContentMetadata(
                  video,
                  includeVideoCodec && !video.isAudio && video.videoCodec.isBlank(),
                )
            }
          }
          .awaitAll()
          .forEach { (path, metadata) ->
            if (metadata != null) {
              metadataByVideoPath[path] =
                mergeMetadata(metadata, metadataByVideoPath[path]) ?: metadata
            }
          }
      }
    }

    val localFiles =
      videosNeedingProbe.mapNotNull { video ->
        if (ZipArchiveMedia.isPlaybackUri(video.uri.toString()) ||
          video.uri.scheme == "content"
        ) return@mapNotNull null
        File(video.path).takeIf(File::isFile)?.let { file -> video to file }
      }

    val localMetadata =
      metadataCache.getOrExtractMetadataBatch(
        files = localFiles.map { (video, file) -> Triple(file, video.uri, video.displayName) },
        videoCodecPaths =
          localFiles
            .asSequence()
            .filter { (video, _) -> includeVideoCodec && !video.isAudio }
            .mapTo(mutableSetOf()) { (_, file) -> file.absolutePath },
      )
    localFiles.forEach { (video, file) ->
      localMetadata[file.absolutePath]?.let { metadata ->
        metadataByVideoPath[video.path] =
          mergeMetadata(metadata, metadataByVideoPath[video.path]) ?: metadata
      }
    }

    return metadataByVideoPath
  }

  /**
   * Batch-reads duration/width/height/size for [videos] from MediaStore, keyed by
   * file path. One query per chunk of paths; files the system scanner has not
   * indexed are simply absent from the result and fall back to probing.
   */
  private fun queryMediaStoreMetadata(
    context: Context,
    videos: List<Video>,
  ): Map<String, MediaInfoOps.VideoMetadata> {
    val paths = videos.map { it.path }.filter { it.isNotBlank() }.distinct()
    if (paths.isEmpty()) return emptyMap()
    val projection =
      arrayOf(
        MediaStore.Video.Media.DATA,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.WIDTH,
        MediaStore.Video.Media.HEIGHT,
        MediaStore.Video.Media.SIZE,
      )
    val result = mutableMapOf<String, MediaInfoOps.VideoMetadata>()
    try {
      paths.chunked(MEDIASTORE_QUERY_CHUNK).forEach { chunk ->
        val selection =
          "${MediaStore.Video.Media.DATA} IN (${chunk.joinToString(",") { "?" }})"
        context.contentResolver
          .query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            chunk.toTypedArray(),
            null,
          )
          ?.use { cursor ->
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            while (cursor.moveToNext()) {
              val path = cursor.getString(dataColumn) ?: continue
              val durationMs = cursor.getLong(durationColumn)
              val width = cursor.getInt(widthColumn)
              val height = cursor.getInt(heightColumn)
              if (durationMs <= 0L && width <= 0 && height <= 0) continue
              result[path] =
                MediaInfoOps.VideoMetadata(
                  sizeBytes = cursor.getLong(sizeColumn),
                  durationMs = durationMs,
                  width = width,
                  height = height,
                  fps = 0f,
                  hasEmbeddedSubtitles = false,
                )
            }
          }
      }
    } catch (e: Exception) {
      Log.w(TAG, "MediaStore metadata query failed", e)
      return emptyMap()
    }
    return result
  }

  /** Fills the positive fields of [base] from [fill] where [base] has none. */
  private fun mergeMetadata(
    base: MediaInfoOps.VideoMetadata?,
    fill: MediaInfoOps.VideoMetadata?,
  ): MediaInfoOps.VideoMetadata? {
    if (base == null) return fill
    if (fill == null) return base
    return base.copy(
      sizeBytes = base.sizeBytes.takeIf { it > 0L } ?: fill.sizeBytes,
      durationMs = base.durationMs.takeIf { it > 0L } ?: fill.durationMs,
      width = base.width.takeIf { it > 0 } ?: fill.width,
      height = base.height.takeIf { it > 0 } ?: fill.height,
    )
  }

  /**
   * Whether [video] still needs a per-file probe after the database cache and
   * MediaStore fills: true only when a field an enabled chip shows is still
   * unknown from the video itself and from [metadata].
   */
  private fun stillNeedsProbe(
    video: Video,
    metadata: MediaInfoOps.VideoMetadata?,
    preferences: BrowserPreferences,
  ): Boolean {
    if (ZipArchiveMedia.isPlaybackUri(video.uri.toString())) return false
    val duration = metadata?.durationMs?.takeIf { it > 0L } ?: video.duration
    if (preferences.showDurationField.get() && duration <= 0L) return true
    if (video.isAudio) return false
    val width = metadata?.width?.takeIf { it > 0 } ?: video.width
    val height = metadata?.height?.takeIf { it > 0 } ?: video.height
    val fps = metadata?.fps?.takeIf { it > 0f } ?: video.fps
    val subtitleCodec =
      metadata?.subtitleCodec?.takeIf { it.isNotEmpty() } ?: video.subtitleCodec
    val videoCodec = metadata?.videoCodec?.takeIf { it.isNotBlank() } ?: video.videoCodec
    return (preferences.showResolutionChip.get() && (width == 0 || height == 0)) ||
      (preferences.showFramerateInResolution.get() && fps == 0f) ||
      (preferences.showSubtitleIndicator.get() && subtitleCodec.isEmpty()) ||
      (preferences.showCodecSupportIndicator.get() && videoCodec.isBlank())
  }

  /**
   * Enriches a folder with metadata only if needed
   * Calculates total duration by extracting metadata from all videos in the folder
   */
  suspend fun enrichFolderIfNeeded(
    context: Context,
    folder: VideoFolder,
    browserPreferences: BrowserPreferences,
    metadataCache: VideoMetadataCacheRepository,
    audioOnly: Boolean = false,
  ): VideoFolder =
    withContext(Dispatchers.IO) {
      // If folder duration chip is disabled, return folder as-is
      if (!isFolderMetadataNeeded(browserPreferences)) {
        return@withContext folder
      }

      // If folder already has duration, return as-is
      if (folder.totalDuration > 0 && !ScopedHiddenFolderScanner.isScopedFolder(folder.bucketId)) {
        return@withContext folder
      }

      // Extract metadata for all videos in folder
      try {
        if (ZipArchiveMedia.isBrowserPath(folder.path)) {
          return@withContext folder
        }

        if (app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY) {
          val videos = restoreCachedContentMetadata(
            app.gyrolet.mpvrx.repository.MediaFileRepository.getVideosInFolder(
              context,
              folder.bucketId,
              includeAudioOverride = if (audioOnly) true else null,
            ).filter { !audioOnly || it.isAudio },
            metadataCache,
          )
          var totalDuration = 0L
          for (video in videos) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            totalDuration += video.duration.takeIf { it > 0 }
              ?: metadataCache.getOrExtractContentMetadata(video)?.durationMs ?: 0L
          }
          return@withContext folder.copy(totalDuration = totalDuration)
        }
        val directory = File(folder.path)
        if (!directory.exists() || !directory.isDirectory) {
          return@withContext folder
        }

        val videoFiles =
          directory.listFiles()?.filter { file ->
            file.isFile && FileTypeUtils.isVideoFile(file)
          } ?: emptyList()

        if (videoFiles.isEmpty()) {
          return@withContext folder
        }

        // Batch extract metadata
        val fileTriples =
          videoFiles.map { file ->
            Triple(file, Uri.fromFile(file), file.name)
          }

        val metadataMap = metadataCache.getOrExtractMetadataBatch(fileTriples)

        // Calculate total duration
        val totalDuration = metadataMap.values.sumOf { it.durationMs }

        folder.copy(totalDuration = totalDuration)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (e: Exception) {
        Log.e(TAG, "Error enriching folder metadata: ${folder.name}", e)
        folder
      }
    }

  /**
   * Enriches a list of folders with metadata only if needed
   * Processes in batches for better performance
   */
  suspend fun enrichFoldersIfNeeded(
    context: Context,
    folders: List<VideoFolder>,
    browserPreferences: BrowserPreferences,
    metadataCache: VideoMetadataCacheRepository,
    onProgress: ((Int, Int) -> Unit)? = null,
  ): List<VideoFolder> =
    withContext(Dispatchers.IO) {
      // If folder duration chip is disabled, return folders as-is
      if (!isFolderMetadataNeeded(browserPreferences)) {
        return@withContext folders
      }

      // Filter folders that need metadata extraction
      val foldersNeedingMetadata = folders.filter { it.totalDuration == 0L }

      if (foldersNeedingMetadata.isEmpty()) {
        return@withContext folders
      }

      Log.d(TAG, "Enriching ${foldersNeedingMetadata.size} folders with metadata")

      var processed = 0
      val total = foldersNeedingMetadata.size

      // Process each folder
      val enrichedMap =
        foldersNeedingMetadata.associate { folder ->
          val enriched = enrichFolderIfNeeded(context, folder, browserPreferences, metadataCache)
          processed++
          onProgress?.invoke(processed, total)
          folder.path to enriched
        }

      // Return updated list
      folders.map { folder ->
        enrichedMap[folder.path] ?: folder
      }
    }

  // Helper: Video file extensions
  // Formatting utilities
  private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return "0s"

    val seconds = durationMs / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return when {
      hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, secs)
      minutes > 0 -> String.format("%d:%02d", minutes, secs)
      else -> "${secs}s"
    }
  }

}
