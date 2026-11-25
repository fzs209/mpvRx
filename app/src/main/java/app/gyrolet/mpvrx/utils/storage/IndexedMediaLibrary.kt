/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import app.gyrolet.mpvrx.domain.media.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Phase 1 of the IsekaiPlayer-style browsing port: one full MediaStore query,
 * grouped in memory by parent folder, reused by every video-list open.
 *
 * Unlike [VideoScanUtils.getVideosInFolder], opening a folder never queries
 * MediaStore, never walks the filesystem and never probes a file: [getVideos]
 * is a lookup in the map built by [ensureLoaded]. The only per-file work
 * before the list shows is the full load itself, which runs once until the
 * index is invalidated.
 *
 * Cache rules, deliberately minimal (same shape as IsekaiPlayer's local
 * repository): no TTL, no generation-token check, no persisted snapshot.
 * The index is rebuilt only when [invalidate] is called (explicit refresh
 * and [app.gyrolet.mpvrx.utils.media.MediaLibraryEvents] changes) or when
 * [ensureLoaded] is asked to force a rebuild. Row mapping, extension
 * filtering, .nomedia exclusion and the rotation-column retry mirror
 * [VideoScanUtils] row for row so the index returns the same [Video]
 * objects the per-folder scan used to return.
 */
object IndexedMediaLibrary {
  private const val TAG = "IndexedMediaLibrary"

  private val videoRotationColumn =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.ORIENTATION else "rotation"

  private data class Snapshot(
    val videosByParent: Map<String, List<Video>>,
    val parentPaths: Set<String>,
  )

  private val cacheMutex = Mutex()

  @Volatile
  private var snapshot: Snapshot? = null

  @Volatile
  private var isDirty = true

  private var cachedOptionsKey: String? = null

  /** True once a load has completed and no invalidation has happened since. */
  val isLoaded: Boolean
    get() = !isDirty && snapshot != null

  /**
   * Drops the whole index. The next [ensureLoaded] rebuilds it with one full
   * MediaStore query. Invalidation never keeps stale rows around: a renamed
   * or deleted file must not survive in any folder list.
   */
  fun invalidate() {
    isDirty = true
    snapshot = null
    cachedOptionsKey = null
  }

  /**
   * Returns the current index, building it with one full MediaStore query
   * when it is dirty, absent, forced, or was built under different scan
   * options. Concurrent callers share the single build via double-checked
   * locking: the second caller waits on [cacheMutex] and reuses the result.
   */
  suspend fun ensureLoaded(
    context: Context,
    options: MediaScanOptions,
    force: Boolean = false,
  ): Snapshot =
    withContext(Dispatchers.IO) {
      val optionsKey = options.cacheKey
      val current = snapshot
      if (!force && !isDirty && current != null && cachedOptionsKey == optionsKey) {
        return@withContext current
      }
      cacheMutex.withLock {
        val locked = snapshot
        if (!force && !isDirty && locked != null && cachedOptionsKey == optionsKey) {
          return@withLock locked
        }
        val started = SystemClock.elapsedRealtime()
        val loaded = loadAll(context, options)
        snapshot = loaded
        cachedOptionsKey = optionsKey
        isDirty = false
        Log.d(
          TAG,
          "Loaded indexed library: ${loaded.parentPaths.size} folders, " +
            "${loaded.videosByParent.values.sumOf { it.size }} videos " +
            "in ${SystemClock.elapsedRealtime() - started}ms",
        )
        loaded
      }
    }

  /**
   * Videos directly inside [folderPath], from the in-memory index only.
   * Callers must call [ensureLoaded] first; an unloaded index returns an
   * empty list rather than triggering a query on the UI's behalf. Unindexed
   * files discovered on the filesystem are merged in (never overwriting an
   * indexed row), exactly as the per-folder scan did. No MediaStore query,
   * no filesystem listing and no metadata probe happen here.
   */
  fun getVideos(folderPath: String, options: MediaScanOptions): List<Video> {
    val current = snapshot ?: return emptyList()
    val folderKey = storagePathKey(normalizeStoragePath(folderPath)) ?: return emptyList()
    val indexed = current.videosByParent[folderKey].orEmpty()
    val unindexed = UnindexedVideoDiscovery.videos(options, folderPath)
    if (indexed.isEmpty() && unindexed.isEmpty()) {
      return emptyList()
    }
    val videosByKey = linkedMapOf<String, Video>()
    val keyResolver = MediaPathKeyResolver()
    for (video in indexed) {
      videosByKey[keyResolver.key(video.path) ?: video.path] = video
    }
    for (video in unindexed) {
      val key = keyResolver.key(video.path) ?: continue
      videosByKey.putIfAbsent(key, video)
    }
    return videosByKey.values.filter(options::includesVideo)
      .sortedBy { it.displayName.lowercase(Locale.getDefault()) }
  }

  private suspend fun loadAll(context: Context, options: MediaScanOptions): Snapshot {
    val noMediaPathFilter = NoMediaPathFilter(options)
    val keyResolver = MediaPathKeyResolver()
    val folderResolver = MediaFolderResolver(noMediaPathFilter)
    val existenceCache = FolderListingExistenceCache()
    val videosByParent = HashMap<String, MutableList<Video>>()
    val parentPaths = HashSet<String>()
    val seenKeys = HashSet<String>()

    scanAllVideos(context, options, keyResolver, folderResolver, existenceCache, videosByParent, parentPaths, seenKeys)
    if (options.includeAudio) {
      scanAllAudio(context, options, keyResolver, folderResolver, videosByParent, parentPaths, seenKeys)
    }
    return Snapshot(
      videosByParent = videosByParent.mapValues { it.value.toList() },
      parentPaths = parentPaths.toSet(),
    )
  }

  /**
   * One unconditional MediaStore.Video query. Row mapping mirrors
   * VideoScanUtils.scanVideosFromMediaStore, minus the per-folder selection
   * and the direct-child check: every indexed video is kept and grouped by
   * its own parent folder instead.
   */
  private suspend fun scanAllVideos(
    context: Context,
    options: MediaScanOptions,
    keyResolver: MediaPathKeyResolver,
    folderResolver: MediaFolderResolver,
    existenceCache: FolderListingExistenceCache,
    videosByParent: MutableMap<String, MutableList<Video>>,
    parentPaths: MutableSet<String>,
    seenKeys: MutableSet<String>,
  ) {
    val baseProjection =
      arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DATA,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.DATE_MODIFIED,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.MIME_TYPE,
        MediaStore.Video.Media.WIDTH,
        MediaStore.Video.Media.HEIGHT,
      )
    val projectionWithRotation = baseProjection + videoRotationColumn
    try {
      fun queryVideos(projection: Array<String>) =
        context.contentResolver.query(
          MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
          projection,
          null,
          null,
          "${MediaStore.Video.Media.DISPLAY_NAME} ASC",
        )
      val cursor =
        try {
          queryVideos(projectionWithRotation)
        } catch (error: RuntimeException) {
          // Same retry as VideoScanUtils: providers reject the optional
          // rotation column with IllegalArgumentException or SQLiteException.
          if (error !is IllegalArgumentException && error !is android.database.sqlite.SQLiteException) throw error
          queryVideos(baseProjection)
        }
      cursor?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
        val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
        val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
        val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
        val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
        val rotationColumn = cursor.getColumnIndex(videoRotationColumn)

        while (cursor.moveToNext()) {
          currentCoroutineContext().ensureActive()
          val path = cursor.getString(dataColumn)?.takeIf { it.isNotBlank() } ?: continue
          val file = File(path)
          if (!FileTypeUtils.isVideoFile(file)) continue
          val folder = folderResolver.resolve(file.parent) ?: continue
          if (folder.excluded) continue
          if (!app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY && !existenceCache.exists(file)) continue
          val normalizedPath = normalizeStoragePath(path) ?: continue

          val id = cursor.getLong(idColumn)
          val displayName = cursor.getString(nameColumn)?.takeIf { it.isNotBlank() } ?: file.name
          val title = file.nameWithoutExtension
          val size = cursor.getLong(sizeColumn)
          val duration = cursor.getLong(durationColumn)
          val dateModified = cursor.getLong(dateModifiedColumn)
          val dateAdded = cursor.getLong(dateAddedColumn)
          val mimeType = cursor.getString(mimeTypeColumn) ?: "video/*"
          val width = cursor.getInt(widthColumn)
          val height = cursor.getInt(heightColumn)
          val rawRotation =
            if (rotationColumn >= 0 && !cursor.isNull(rotationColumn)) cursor.getInt(rotationColumn) else 0
          val normalizedRotation = ((rawRotation % 360) + 360) % 360
          val rotation = if (normalizedRotation % 90 == 0) normalizedRotation else 0

          val uri =
            Uri.withAppendedPath(
              MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
              id.toString(),
            )
          val video =
            Video(
              id = id,
              title = title,
              displayName = displayName,
              path = normalizedPath,
              uri = uri,
              duration = duration,
              durationFormatted = VideoScanUtils.formatDuration(duration),
              size = size,
              sizeFormatted = VideoScanUtils.formatFileSize(size),
              dateModified = dateModified,
              dateAdded = dateAdded,
              mimeType = mimeType,
              bucketId = folder.path,
              bucketDisplayName = leafStorageName(folder.path),
              width = width,
              height = height,
              rotation = rotation,
              fps = 0f,
              resolution = VideoScanUtils.formatResolution(width, height),
              hasEmbeddedSubtitles = false,
              subtitleCodec = "",
            )
          if (!options.includesVideo(video)) continue
          val videoKey = keyResolver.key(normalizedPath) ?: normalizedPath
          if (!seenKeys.add(videoKey)) continue
          videosByParent.getOrPut(folder.key) { mutableListOf() }.add(video)
          parentPaths.add(folder.path)
        }
      }
    } catch (error: kotlinx.coroutines.CancellationException) {
      throw error
    } catch (e: Exception) {
      Log.e(TAG, "Indexed MediaStore video scan error", e)
    }
  }

  /** One unconditional MediaStore.Audio query; row mapping mirrors VideoScanUtils. */
  private suspend fun scanAllAudio(
    context: Context,
    options: MediaScanOptions,
    keyResolver: MediaPathKeyResolver,
    folderResolver: MediaFolderResolver,
    videosByParent: MutableMap<String, MutableList<Video>>,
    parentPaths: MutableSet<String>,
    seenKeys: MutableSet<String>,
  ) {
    val projection =
      arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATE_MODIFIED,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.MIME_TYPE,
      )
    try {
      context.contentResolver
        .query(
          MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
          projection,
          null,
          null,
          "${MediaStore.Audio.Media.DISPLAY_NAME} ASC",
        )?.use { cursor ->
          val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
          val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
          val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
          val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
          val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
          val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
          val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
          val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
          val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

          while (cursor.moveToNext()) {
            currentCoroutineContext().ensureActive()
            val path = cursor.getString(dataColumn)?.takeIf { it.isNotBlank() } ?: continue
            val file = File(path)
            if (!FileTypeUtils.isAudioFile(file)) continue
            val folder = folderResolver.resolve(file.parent) ?: continue
            if (folder.excluded) continue
            if (!app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY && !file.isFile) continue
            val duration = cursor.getLong(durationColumn)
            if (!options.includesAudioDuration(duration)) continue
            val normalizedPath = normalizeStoragePath(path) ?: continue
            val id = cursor.getLong(idColumn)
            val displayName = cursor.getString(nameColumn) ?: file.name
            val title = cursor.getString(titleColumn)?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
            val size = cursor.getLong(sizeColumn)
            val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
            val video =
              Video(
                id = id,
                title = title,
                displayName = displayName,
                path = normalizedPath,
                uri = uri,
                duration = duration,
                durationFormatted = VideoScanUtils.formatDuration(duration),
                size = size,
                sizeFormatted = VideoScanUtils.formatFileSize(size),
                dateModified = cursor.getLong(modifiedColumn),
                dateAdded = cursor.getLong(addedColumn),
                mimeType =
                  cursor.getString(mimeColumn) ?: FileTypeUtils.getMimeTypeFromExtension(file.extension),
                bucketId = folder.path,
                bucketDisplayName = leafStorageName(folder.path),
                width = 0,
                height = 0,
                fps = 0f,
                resolution = "--",
                isAudio = true,
              )
            if (!options.includesVideo(video)) continue
            val videoKey = keyResolver.key(normalizedPath) ?: normalizedPath
            if (!seenKeys.add(videoKey)) continue
            videosByParent.getOrPut(folder.key) { mutableListOf() }.add(video)
            parentPaths.add(folder.path)
          }
        }
    } catch (error: kotlinx.coroutines.CancellationException) {
      throw error
    } catch (e: Exception) {
      Log.e(TAG, "Indexed MediaStore audio scan error", e)
    }
  }
}
