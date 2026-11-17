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
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import app.gyrolet.mpvrx.ui.player.PlaybackIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Tree View Scanner - Optimized for tree/browser view.
 *
 * Includes parent folders, recursive counts, smart single-child flattening,
 * and recursive NEW badge counts for folders.
 */
object TreeViewScanner {
  private const val TAG = "TreeViewScanner"

  private var cachedTreeViewData: Map<String, FolderNode>? = null
  private var cacheTimestamp: Long = 0
  private var cacheOptionsKey: String? = null

  // The scan options the cached tree was built with. Indexed video rows are only served to a
  // caller asking with these exact options, because the build bakes its filters into the rows.
  private var cachedTreeOptions: MediaScanOptions? = null
  private const val CACHE_TTL_MS = 10_000L

  // Revalidates a stale snapshot off the UI path; each exact key is refreshed by one job at a time.
  private val treeRefreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val refreshingKeys = ConcurrentHashMap.newKeySet<String>()

  fun clearCache() {
    cachedTreeViewData = null
    cacheTimestamp = 0
    cacheOptionsKey = null
    cachedTreeOptions = null
  }

  data class FolderData(
    val path: String,
    val name: String,
    val videoCount: Int,
    val totalSize: Long,
    val totalDuration: Long,
    val lastModified: Long,
    val hasSubfolders: Boolean = false,
    val newCount: Int = 0,
  )

  /**
   * One direct-child media row retained from the tree build's MediaStore pass. The build query
   * already reads these fields for its aggregates; keeping the rows lets a folder's video list be
   * served from the same index instead of a second, per-folder MediaStore query on the
   * navigation path. [mediaId] is -1 for rows the filesystem supplement found (not indexed).
   */
  data class VideoInfo(
    val displayName: String,
    val filePath: String,
    val size: Long,
    val duration: Long,
    val dateModified: Long,
    val mediaId: Long = -1L,
    val dateAdded: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val mimeType: String = "",
    val rotation: Int = 0,
    val isAudio: Boolean = false,
    val title: String = "",
  )

  private data class FolderAggregate(
    var path: String,
    val videos: MutableList<VideoInfo> = mutableListOf(),
  )

  private data class FolderNode(
    var path: String,
    var name: String,
    var directVideoCount: Int = 0,
    var directSize: Long = 0L,
    var directDuration: Long = 0L,
    var directLastModified: Long = 0L,
    var directNewCount: Int = 0,
    var hasDirectSubfolders: Boolean = false,
    var isFlattened: Boolean = false,
    var recursiveVideoCount: Int = 0,
    var recursiveSize: Long = 0L,
    var recursiveDuration: Long = 0L,
    var recursiveLastModified: Long = 0L,
    var recursiveNewCount: Int = 0,
    var directVideos: List<VideoInfo> = emptyList(),
  )

  private data class NewBadgeConfig(
    val enabled: Boolean,
    val thresholdMillis: Long,
    val playedMediaTitles: Set<String>,
    val newLabelOverrides: Map<String, Boolean>,
  )

  suspend fun getFoldersInDirectory(
    context: Context,
    parentPath: String,
    options: MediaScanOptions = MediaScanOptions(),
    forceFileSystemCheck: Boolean = false,
    playedMediaTitles: Set<String> = emptySet(),
    showNewLabels: Boolean = false,
    thresholdDays: Int = 7,
    maxAutoFlattenLevels: Int = -1,
    newLabelOverrides: Map<String, Boolean> = emptyMap(),
    blacklistedPaths: Set<String> = emptySet(),
  ): List<FolderData> =
    withContext(Dispatchers.IO) {
      val allFolders =
        getOrBuildTreeViewData(
          context = context,
          options = options,
          forceFileSystemCheck = forceFileSystemCheck,
          playedMediaTitles = playedMediaTitles,
          showNewLabels = showNewLabels,
          thresholdDays = thresholdDays,
          newLabelOverrides = newLabelOverrides,
        )

      // Recompute recursive counts after removing blacklisted branches, otherwise their
      // old parent counters leave empty phantom folders visible in Tree view.
      val visibleFolders = filterBlacklistedTree(context, allFolders, blacklistedPaths)
      getEffectiveChildren(parentPath, indexDirectChildren(visibleFolders), maxAutoFlattenLevels)
        .map(::toFolderData)
        .sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

  suspend fun getFolderDataRecursive(
    context: Context,
    folderPath: String,
    options: MediaScanOptions = MediaScanOptions(),
    forceFileSystemCheck: Boolean = false,
    playedMediaTitles: Set<String> = emptySet(),
    showNewLabels: Boolean = false,
    thresholdDays: Int = 7,
    newLabelOverrides: Map<String, Boolean> = emptyMap(),
  ): FolderData? =
    withContext(Dispatchers.IO) {
      val allFolders =
        getOrBuildTreeViewData(
          context = context,
          options = options,
          forceFileSystemCheck = forceFileSystemCheck,
          playedMediaTitles = playedMediaTitles,
          showNewLabels = showNewLabels,
          thresholdDays = thresholdDays,
          newLabelOverrides = newLabelOverrides,
        )
      val normalizedFolderPath = normalizeStoragePath(folderPath) ?: return@withContext null
      val folderKey = storagePathKey(normalizedFolderPath) ?: return@withContext null

      allFolders[folderKey]?.let { return@withContext toFolderData(it) }

      val children = getEffectiveChildren(normalizedFolderPath, indexDirectChildren(allFolders))
      if (children.isEmpty()) {
        return@withContext null
      }

      FolderData(
        path = normalizedFolderPath,
        name = leafStorageName(normalizedFolderPath),
        videoCount = children.sumOf { it.recursiveVideoCount },
        totalSize = children.sumOf { it.recursiveSize },
        totalDuration = children.sumOf { it.recursiveDuration },
        lastModified = children.maxOfOrNull { it.recursiveLastModified } ?: 0L,
        hasSubfolders = true,
        newCount = children.sumOf { it.recursiveNewCount },
      )
    }

  private suspend fun getOrBuildTreeViewData(
    context: Context,
    options: MediaScanOptions,
    forceFileSystemCheck: Boolean,
    playedMediaTitles: Set<String>,
    showNewLabels: Boolean,
    thresholdDays: Int,
    newLabelOverrides: Map<String, Boolean>,
  ): Map<String, FolderNode> =
    withContext(Dispatchers.IO) {
      val now = System.currentTimeMillis()
      val cacheKey =
        buildCacheKey(
          options = options,
          showNewLabels = showNewLabels,
          thresholdDays = thresholdDays,
          playedMediaTitles = playedMediaTitles,
          newLabelOverrides = newLabelOverrides,
        )

      cachedTreeViewData?.let { cached ->
        if (!forceFileSystemCheck && now - cacheTimestamp < CACHE_TTL_MS && cacheOptionsKey == cacheKey) {
          Log.d(TAG, "ScanDiag: tree source=memory nodes=${cached.size} ageMs=${now - cacheTimestamp}")
          return@withContext cached
        }
      }

      // The process-local cache is empty after a cold boot. The persisted snapshot is still exact while
      // MediaStore matches the generation it was built from, so restore it instead of walking again.
      if (!forceFileSystemCheck) {
        restoreTreeViewData(context, cacheKey)?.let { restored ->
          Log.d(
            TAG,
            "ScanDiag: tree source=exact-snapshot nodes=${restored.size} " +
              "legacyRows=${restored.hasNodesWithoutVideoRows()}",
          )
          cachedTreeViewData = restored
          cacheTimestamp = now
          cacheOptionsKey = cacheKey
          cachedTreeOptions = options
          // A snapshot written before video rows were retained decodes with counts but no
          // rows, and getCachedIndexedVideos declines to serve those nodes - so without an
          // upgrade every folder in this session would fall back to a live per-folder
          // MediaStore query, and the exact-restore path would keep reviving the legacy
          // snapshot forever because its generation still matches. Serve it now (folder
          // lists are unaffected) and rebuild once in the background to rewrite the
          // snapshot in the current format.
          if (restored.hasNodesWithoutVideoRows()) {
            refreshTreeInBackground(
              context = context,
              options = options,
              cacheKey = cacheKey,
              playedMediaTitles = playedMediaTitles,
              showNewLabels = showNewLabels,
              thresholdDays = thresholdDays,
              newLabelOverrides = newLabelOverrides,
            )
          }
          return@withContext restored
        }
        // Played titles and overrides change the exact key without changing the scan. Show the last
        // tree built with these scan options now and rebuild the exact one in the background, so
        // reopening stays instant and the NEW badges catch up shortly after.
        restoreTreeViewData(context, baseCacheKey(options, showNewLabels, thresholdDays))?.let { stale ->
          Log.d(
            TAG,
            "ScanDiag: tree source=stale-snapshot-basekey nodes=${stale.size} " +
              "legacyRows=${stale.hasNodesWithoutVideoRows()}",
          )
          cachedTreeViewData = stale
          cacheTimestamp = now
          cacheOptionsKey = cacheKey
          cachedTreeOptions = options
          refreshTreeInBackground(
            context = context,
            options = options,
            cacheKey = cacheKey,
            playedMediaTitles = playedMediaTitles,
            showNewLabels = showNewLabels,
            thresholdDays = thresholdDays,
            newLabelOverrides = newLabelOverrides,
          )
          return@withContext stale
        }
        // MediaStore has moved on since every snapshot was built, so none of them can be proven
        // exact, and the checks above all decline. Folder view still shows its last list in that
        // situation and rescans behind it; do the same here instead of walking the whole volume
        // on the navigation path. The stale tree is only a stand-in while the background rebuild
        // runs. With no snapshot at all there is nothing to show, so the build below stays
        // synchronous for the first ever scan.
        val staleTree =
          cachedTreeViewData?.takeIf { cacheOptionsKey == cacheKey }
            ?: restoreStaleTreeViewData(context, cacheKey)
            ?: restoreStaleTreeViewData(context, baseCacheKey(options, showNewLabels, thresholdDays))
        if (staleTree != null) {
          Log.d(
            TAG,
            "ScanDiag: tree source=stale-fallback nodes=${staleTree.size} " +
              "legacyRows=${staleTree.hasNodesWithoutVideoRows()}",
          )
          cachedTreeViewData = staleTree
          cacheTimestamp = now
          cacheOptionsKey = cacheKey
          cachedTreeOptions = options
          refreshTreeInBackground(
            context = context,
            options = options,
            cacheKey = cacheKey,
            playedMediaTitles = playedMediaTitles,
            showNewLabels = showNewLabels,
            thresholdDays = thresholdDays,
            newLabelOverrides = newLabelOverrides,
          )
          return@withContext staleTree
        }
      }

      Log.d(TAG, "ScanDiag: tree source=sync-build force=$forceFileSystemCheck")
      buildAndPersist(
        context = context,
        options = options,
        forceFileSystemCheck = forceFileSystemCheck,
        cacheKey = cacheKey,
        playedMediaTitles = playedMediaTitles,
        showNewLabels = showNewLabels,
        thresholdDays = thresholdDays,
        newLabelOverrides = newLabelOverrides,
      )
    }

  /**
   * The retained direct-child media rows for [folderPath] from the tree already in memory, or
   * null when no tree is cached, the cached tree was built with different [options], or the
   * folder's node carries counts without rows (a snapshot written before rows were retained).
   * Never builds or restores anything: callers fall back to a live scan on null, so this can
   * only ever remove a MediaStore query from the navigation path, never add one.
   */
  /**
   * True when any node carries a direct-media count but no retained rows - the mark of a
   * snapshot written before rows were kept (or a node the filesystem supplement created
   * before it kept them). Such a tree displays fine but cannot serve video lists.
   */
  private fun Map<String, FolderNode>.hasNodesWithoutVideoRows(): Boolean =
    values.any { it.directVideoCount > 0 && it.directVideos.isEmpty() }

  fun getCachedIndexedVideos(
    folderPath: String,
    options: MediaScanOptions,
  ): List<VideoInfo>? {
    val tree = cachedTreeViewData
    if (tree == null) {
      Log.d(TAG, "ScanDiag: index decline for $folderPath - no tree in memory")
      return null
    }
    if (cachedTreeOptions != options) {
      Log.d(TAG, "ScanDiag: index decline for $folderPath - options mismatch")
      return null
    }
    val normalizedFolderPath = normalizeStoragePath(folderPath) ?: return null
    val folderKey = storagePathKey(normalizedFolderPath) ?: return null
    val node = tree[folderKey]
    if (node == null) {
      Log.d(TAG, "ScanDiag: index decline for $folderPath - no node in tree")
      return null
    }
    if (node.directVideos.isEmpty() && node.directVideoCount > 0) {
      Log.d(
        TAG,
        "ScanDiag: index decline for $folderPath - node count=${node.directVideoCount} but no rows (legacy)",
      )
      return null
    }
    Log.d(TAG, "ScanDiag: index hit for $folderPath - ${node.directVideos.size} rows")
    return node.directVideos
  }

  /** Rebuilds the exact-key tree off the caller's path and swaps it into the cache when done. */
  private fun refreshTreeInBackground(
    context: Context,
    options: MediaScanOptions,
    cacheKey: String,
    playedMediaTitles: Set<String>,
    showNewLabels: Boolean,
    thresholdDays: Int,
    newLabelOverrides: Map<String, Boolean>,
  ) {
    if (!refreshingKeys.add(cacheKey)) return
    treeRefreshScope.launch {
      try {
        buildAndPersist(
          context = context,
          options = options,
          forceFileSystemCheck = false,
          cacheKey = cacheKey,
          playedMediaTitles = playedMediaTitles,
          showNewLabels = showNewLabels,
          thresholdDays = thresholdDays,
          newLabelOverrides = newLabelOverrides,
        )
      } catch (error: Exception) {
        Log.w(TAG, "Could not refresh tree snapshot", error)
      } finally {
        refreshingKeys.remove(cacheKey)
      }
    }
  }

  /**
   * Loads a persisted tree WITHOUT the generation check in [restoreTreeViewData]. Only the
   * stale-first branch of [getOrBuildTreeViewData] may use this, and only to put the last known
   * tree on screen while a background rebuild runs; the result must never be treated as exact.
   */
  private fun restoreStaleTreeViewData(
    context: Context,
    cacheKey: String,
  ): Map<String, FolderNode>? {
    val payload = TreeViewCache.load(context, cacheKey) ?: return null
    return decodeTreeViewData(payload)
  }

  /** Builds a tree from the current library and persists it under both the exact and the base key. */
  private suspend fun buildAndPersist(
    context: Context,
    options: MediaScanOptions,
    forceFileSystemCheck: Boolean,
    cacheKey: String,
    playedMediaTitles: Set<String>,
    showNewLabels: Boolean,
    thresholdDays: Int,
    newLabelOverrides: Map<String, Boolean>,
  ): Map<String, FolderNode> {
    // Read before building: recording the generation afterwards would bless a MediaStore newer
    // than the data, and the next cold boot would keep a stale tree.
    val observedGeneration =
      if (forceFileSystemCheck) null else MediaStoreGenerationGuard.currentToken(context)

    val buildStartMs = System.currentTimeMillis()
    val data =
      buildTreeViewData(
        context = context,
        options = options,
        forceFileSystemCheck = forceFileSystemCheck,
        playedMediaTitles = playedMediaTitles,
        showNewLabels = showNewLabels,
        thresholdDays = thresholdDays,
        newLabelOverrides = newLabelOverrides,
      )

    cachedTreeViewData = data
    cacheTimestamp = System.currentTimeMillis()
    cacheOptionsKey = cacheKey
    cachedTreeOptions = options
    // The snapshot is only trustworthy while MediaStore still matches, so the generation that
    // describes it travels inside the payload. Both are dropped together when the library changes.
    val payload = MediaStoreGenerationGuard.withGeneration(encodeTreeViewData(data), observedGeneration)
    if (payload != null) {
      TreeViewCache.save(context, cacheKey, payload)
      TreeViewCache.save(context, baseCacheKey(options, showNewLabels, thresholdDays), payload)
      Log.d(
        TAG,
        "ScanDiag: tree built nodes=${data.size} buildMs=${System.currentTimeMillis() - buildStartMs} " +
          "persisted generation=$observedGeneration",
      )
    } else {
      // No generation to prove it fresh, so there is nothing worth keeping.
      TreeViewCache.clear(context)
      Log.d(
        TAG,
        "ScanDiag: tree built nodes=${data.size} buildMs=${System.currentTimeMillis() - buildStartMs} " +
          "but NOT persisted (no generation token); snapshot cleared",
      )
    }
    return data
  }

  /**
   * Restores a previously persisted tree, but only while MediaStore still matches the generation the
   * snapshot was built from.
   *
   * The check lives here rather than at the call site so the persisted copy can never be trusted on
   * its own.
   */
  private suspend fun restoreTreeViewData(
    context: Context,
    cacheKey: String,
  ): Map<String, FolderNode>? {
    val payload = TreeViewCache.load(context, cacheKey)
    if (payload == null) {
      Log.d(TAG, "ScanDiag: snapshot miss (nothing persisted for this key)")
      return null
    }
    if (!MediaStoreGenerationGuard.isSnapshotCurrent(context, payload)) {
      Log.d(TAG, "ScanDiag: snapshot rejected - MediaStore generation has moved on")
      return null
    }
    return decodeTreeViewData(payload)
  }

  // Same optional-geometry column the per-folder scan uses: ORIENTATION from API 29, the legacy
  // "rotation" name before it. Providers that reject it are retried without it at query time.
  private val treeVideoRotationColumn =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.ORIENTATION else "rotation"

  // ASCII unit/record separators, escaped rather than embedded as literal control characters so the
  // source stays readable. Both are pathological in a path or folder name.
  private const val FIELD_SEPARATOR = "\u001F"
  private const val RECORD_SEPARATOR = "\u001E"
  private const val NODE_FIELD_COUNT = 15

  // Separators for the retained video rows appended to each node record as one extra field.
  private const val VIDEO_FIELD_SEPARATOR = "\u001C"
  private const val VIDEO_RECORD_SEPARATOR = "\u001D"
  private const val VIDEO_FIELD_COUNT = 13

  /**
   * Serialises every [FolderNode] field.
   *
   * The whole node is stored rather than the rendered [FolderData], because the tree is re-read
   * through [getEffectiveChildren] and [getFolderDataRecursive], which need the hierarchy and the
   * recursive aggregates, not just one directory's children.
   */
  private fun encodeTreeViewData(folders: Map<String, FolderNode>): String =
    folders.entries.joinToString(RECORD_SEPARATOR) { (key, node) ->
      val nodeFields =
        listOf(
          key,
          node.path,
          node.name,
          node.directVideoCount.toString(),
          node.directSize.toString(),
          node.directDuration.toString(),
          node.directLastModified.toString(),
          node.directNewCount.toString(),
          node.hasDirectSubfolders.toString(),
          node.isFlattened.toString(),
          node.recursiveVideoCount.toString(),
          node.recursiveSize.toString(),
          node.recursiveDuration.toString(),
          node.recursiveLastModified.toString(),
          node.recursiveNewCount.toString(),
        ).joinToString(FIELD_SEPARATOR) { sanitizeField(it) }
      // The rows blob is appended unsanitised as a whole: its fields are sanitised one by one
      // and its own separators are distinct from the node-level ones.
      nodeFields + FIELD_SEPARATOR + encodeVideoRows(node.directVideos)
    }

  private fun encodeVideoRows(videos: List<VideoInfo>): String =
    videos.joinToString(VIDEO_RECORD_SEPARATOR) { video ->
      listOf(
        video.filePath,
        video.displayName,
        video.mediaId.toString(),
        video.size.toString(),
        video.duration.toString(),
        video.dateModified.toString(),
        video.dateAdded.toString(),
        video.width.toString(),
        video.height.toString(),
        video.mimeType,
        video.rotation.toString(),
        video.isAudio.toString(),
        video.title,
      ).joinToString(VIDEO_FIELD_SEPARATOR) { sanitizeField(it) }
    }

  private fun decodeVideoRows(blob: String): List<VideoInfo>? {
    if (blob.isBlank()) return emptyList()
    val videos = mutableListOf<VideoInfo>()
    for (record in blob.split(VIDEO_RECORD_SEPARATOR)) {
      val parts = record.split(VIDEO_FIELD_SEPARATOR)
      if (parts.size != VIDEO_FIELD_COUNT) return null
      videos +=
        VideoInfo(
          filePath = parts[0],
          displayName = parts[1],
          mediaId = parts[2].toLongOrNull() ?: return null,
          size = parts[3].toLongOrNull() ?: return null,
          duration = parts[4].toLongOrNull() ?: return null,
          dateModified = parts[5].toLongOrNull() ?: return null,
          dateAdded = parts[6].toLongOrNull() ?: return null,
          width = parts[7].toIntOrNull() ?: return null,
          height = parts[8].toIntOrNull() ?: return null,
          mimeType = parts[9],
          rotation = parts[10].toIntOrNull() ?: return null,
          isAudio = parts[11].toBooleanStrictOrNull() ?: return null,
          title = parts[12],
        )
    }
    return videos
  }

  /** Returns null when the payload is unreadable, so the caller rebuilds rather than showing junk. */
  private fun decodeTreeViewData(payload: String): Map<String, FolderNode>? {
    // The leading line is the embedded generation, added by MediaStoreGenerationGuard.withGeneration.
    val body = payload.substringAfter('\n')
    if (body.isBlank()) return null
    val folders = linkedMapOf<String, FolderNode>()
    for (record in body.split(RECORD_SEPARATOR)) {
      val node = decodeTreeNode(record) ?: return null
      folders[node.first] = node.second
    }
    return folders.takeIf { it.isNotEmpty() }
  }

  private fun decodeTreeNode(record: String): Pair<String, FolderNode>? {
    val parts = record.split(FIELD_SEPARATOR)
    // Snapshots written before video rows were retained have no 16th field; they still decode,
    // with empty rows, and getCachedIndexedVideos declines to serve those nodes.
    if (parts.size != NODE_FIELD_COUNT && parts.size != NODE_FIELD_COUNT + 1) return null
    val directVideos =
      if (parts.size == NODE_FIELD_COUNT + 1) {
        decodeVideoRows(parts[NODE_FIELD_COUNT]) ?: return null
      } else {
        emptyList()
      }
    val directVideoCount = parts[3].toIntOrNull() ?: return null
    val directSize = parts[4].toLongOrNull() ?: return null
    val directDuration = parts[5].toLongOrNull() ?: return null
    val directLastModified = parts[6].toLongOrNull() ?: return null
    val directNewCount = parts[7].toIntOrNull() ?: return null
    val hasDirectSubfolders = parts[8].toBooleanStrictOrNull() ?: return null
    val isFlattened = parts[9].toBooleanStrictOrNull() ?: return null
    val recursiveVideoCount = parts[10].toIntOrNull() ?: return null
    val recursiveSize = parts[11].toLongOrNull() ?: return null
    val recursiveDuration = parts[12].toLongOrNull() ?: return null
    val recursiveLastModified = parts[13].toLongOrNull() ?: return null
    val recursiveNewCount = parts[14].toIntOrNull() ?: return null

    return parts[0] to
      FolderNode(
        path = parts[1],
        name = parts[2],
        directVideoCount = directVideoCount,
        directSize = directSize,
        directDuration = directDuration,
        directLastModified = directLastModified,
        directNewCount = directNewCount,
        hasDirectSubfolders = hasDirectSubfolders,
        isFlattened = isFlattened,
        recursiveVideoCount = recursiveVideoCount,
        recursiveSize = recursiveSize,
        recursiveDuration = recursiveDuration,
        recursiveLastModified = recursiveLastModified,
        recursiveNewCount = recursiveNewCount,
        directVideos = directVideos,
      )
  }

  private fun sanitizeField(value: String): String =
    value
      .replace(FIELD_SEPARATOR, " ")
      .replace(RECORD_SEPARATOR, " ")
      .replace(VIDEO_FIELD_SEPARATOR, " ")
      .replace(VIDEO_RECORD_SEPARATOR, " ")
      .replace('\n', ' ')
      .replace('\r', ' ')

  private fun baseCacheKey(
    options: MediaScanOptions,
    showNewLabels: Boolean,
    thresholdDays: Int,
  ): String = "${options.cacheKey}|new=$showNewLabels|days=$thresholdDays|base"

  private fun buildCacheKey(
    options: MediaScanOptions,
    showNewLabels: Boolean,
    thresholdDays: Int,
    playedMediaTitles: Set<String>,
    newLabelOverrides: Map<String, Boolean>,
  ): String {
    val playedTitlesHash =
      if (showNewLabels) {
        playedMediaTitles.toList().sorted().hashCode()
      } else {
        0
      }

    return "${options.cacheKey}|new=$showNewLabels|days=$thresholdDays|played=$playedTitlesHash" +
      "|marks=${newLabelOverrides.hashCode()}"
  }

  private suspend fun buildTreeViewData(
    context: Context,
    options: MediaScanOptions,
    forceFileSystemCheck: Boolean,
    playedMediaTitles: Set<String>,
    showNewLabels: Boolean,
    thresholdDays: Int,
    newLabelOverrides: Map<String, Boolean>,
  ): Map<String, FolderNode> =
    withContext(Dispatchers.IO) {
      val allFolders = mutableMapOf<String, FolderNode>()
      val noMediaPathFilter = NoMediaPathFilter(options)
      val storageRootKeys = getStorageRootKeys(context)
      val newBadgeConfig =
        NewBadgeConfig(
          enabled = showNewLabels,
          thresholdMillis = thresholdDays.toLong() * 24L * 60L * 60L * 1000L,
          playedMediaTitles = playedMediaTitles,
          newLabelOverrides = newLabelOverrides,
        )
      val currentTimeMs = System.currentTimeMillis()

      scanMediaStoreRecursive(context, allFolders, noMediaPathFilter, newBadgeConfig, currentTimeMs, options)
      if (options.includeAudio) {
        scanAudioMediaStoreRecursive(
          context,
          allFolders,
          noMediaPathFilter,
          newBadgeConfig,
          currentTimeMs,
          options,
        )
      }
      scanFileSystemRoots(
        context = context,
        folders = allFolders,
        options = options,
        noMediaPathFilter = noMediaPathFilter,
        forceFileSystemCheck = forceFileSystemCheck,
        newBadgeConfig = newBadgeConfig,
        currentTimeMs = currentTimeMs,
      )
      buildParentHierarchy(allFolders)
      markFlattenedFolders(allFolders, storageRootKeys)

      allFolders.entries.removeIf { it.value.recursiveVideoCount <= 0 && !it.value.isFlattened }
      allFolders
    }

  private fun scanMediaStoreRecursive(
    context: Context,
    folders: MutableMap<String, FolderNode>,
    noMediaPathFilter: NoMediaPathFilter,
    newBadgeConfig: NewBadgeConfig,
    currentTimeMs: Long,
    options: MediaScanOptions,
  ) {
    // The extra columns beyond the aggregates (id, geometry, mime, dates, rotation) are the
    // video-list fields: reading them in this same pass is what lets the rows be retained in
    // the tree index at zero extra query cost.
    val baseProjection =
      arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DATA,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.DATE_MODIFIED,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.MIME_TYPE,
        MediaStore.Video.Media.WIDTH,
        MediaStore.Video.Media.HEIGHT,
      )
    val projectionWithRotation = baseProjection + treeVideoRotationColumn

    try {
      fun queryTreeVideos(projection: Array<String>) =
        context.contentResolver.query(
          MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
          projection,
          null,
          null,
          null,
        )
      val cursor =
        try {
          queryTreeVideos(projectionWithRotation)
        } catch (error: RuntimeException) {
          // Strict providers reject the optional rotation column; retry without it, mirroring
          // the per-folder scan, so the whole tree build is not discarded over one column.
          if (error !is IllegalArgumentException &&
            error !is android.database.sqlite.SQLiteException
          ) {
            throw error
          }
          queryTreeVideos(baseProjection)
        }
      cursor?.use { cursor ->
          val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
          val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
          val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
          val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
          val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
          val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
          val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
          val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
          val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
          val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
          val rotationColumn = cursor.getColumnIndex(treeVideoRotationColumn)

          val videosByFolder = mutableMapOf<String, FolderAggregate>()
          val folderResolver = MediaFolderResolver(noMediaPathFilter)
          val existenceCache = FolderListingExistenceCache()

          while (cursor.moveToNext()) {
            val videoPath = cursor.getString(dataColumn)?.takeIf { it.isNotBlank() } ?: continue
            val file = File(videoPath)

            if (!FileTypeUtils.isVideoFile(file)) continue
            val folder = folderResolver.resolve(file.parent) ?: continue
            if (folder.excluded || !options.includesPath(folder.path)) continue
            if (!app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY && !existenceCache.exists(file)) continue
            if (!options.includesMedia(cursor.getLong(sizeColumn), cursor.getLong(durationColumn))) continue

            val folderPath = folder.path
            val aggregate = videosByFolder.getOrPut(folder.key) { FolderAggregate(path = folderPath) }
            if (aggregate.path != folderPath) {
              aggregate.path = choosePreferredStoragePath(aggregate.path, folderPath)
            }
            val rawRotation =
              if (rotationColumn >= 0 && !cursor.isNull(rotationColumn)) {
                cursor.getInt(rotationColumn)
              } else {
                0
              }
            val normalizedRotation = ((rawRotation % 360) + 360) % 360
            aggregate.videos.add(
              VideoInfo(
                displayName = cursor.getString(displayNameColumn) ?: file.name,
                filePath = file.absolutePath,
                size = cursor.getLong(sizeColumn),
                duration = cursor.getLong(durationColumn),
                dateModified = cursor.getLong(dateColumn),
                mediaId = cursor.getLong(idColumn),
                dateAdded = cursor.getLong(dateAddedColumn),
                width = cursor.getInt(widthColumn),
                height = cursor.getInt(heightColumn),
                mimeType = cursor.getString(mimeColumn) ?: "",
                rotation = if (normalizedRotation % 90 == 0) normalizedRotation else 0,
                title = file.nameWithoutExtension,
              ),
            )
          }

          for ((folderKey, aggregate) in videosByFolder) {
            folders[folderKey] =
              createDirectNode(
                folderPath = aggregate.path,
                videos = aggregate.videos,
                newBadgeConfig = newBadgeConfig,
                currentTimeMs = currentTimeMs,
              )
          }
        }
    } catch (e: Exception) {
      Log.e(TAG, "MediaStore scan error", e)
    }
  }

  private fun createDirectNode(
    folderPath: String,
    videos: List<VideoInfo>,
    newBadgeConfig: NewBadgeConfig,
    currentTimeMs: Long,
  ): FolderNode {
    val newCount =
      if (newBadgeConfig.enabled) {
        videos.count { video ->
          isVideoNew(
            displayName = video.displayName,
            filePath = video.filePath,
            dateModifiedSeconds = video.dateModified,
            currentTimeMs = currentTimeMs,
            newBadgeConfig = newBadgeConfig,
          )
        }
      } else {
        0
      }

    return FolderNode(
      path = folderPath,
      name = leafStorageName(folderPath),
      directVideoCount = videos.size,
      directSize = videos.sumOf { it.size },
      directDuration = videos.sumOf { it.duration },
      directLastModified = videos.maxOfOrNull { it.dateModified } ?: 0L,
      directNewCount = newCount,
      directVideos = videos,
    )
  }

  private fun scanAudioMediaStoreRecursive(
    context: Context,
    folders: MutableMap<String, FolderNode>,
    noMediaPathFilter: NoMediaPathFilter,
    newBadgeConfig: NewBadgeConfig,
    currentTimeMs: Long,
    options: MediaScanOptions,
  ) {
    val projection =
      arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.TITLE,
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
          null,
        )?.use { cursor ->
          val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
          val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
          val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
          val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
          val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
          val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
          val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
          val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
          val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
          val audioByFolder = mutableMapOf<String, FolderAggregate>()
          val folderResolver = MediaFolderResolver(noMediaPathFilter)
          val existenceCache = FolderListingExistenceCache()

          while (cursor.moveToNext()) {
            val path = cursor.getString(dataColumn)?.takeIf { it.isNotBlank() } ?: continue
            val file = File(path)
            if (!FileTypeUtils.isAudioFile(file)) continue
            val folder = folderResolver.resolve(file.parent) ?: continue
            if (folder.excluded) continue
            if (!app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY && !existenceCache.exists(file)) continue
            val duration = cursor.getLong(durationColumn)
            if (!options.includesPath(folder.path) || !options.includesMedia(cursor.getLong(sizeColumn), duration, isAudio = true)) continue
            audioByFolder.getOrPut(folder.key) { FolderAggregate(folder.path) }.videos +=
              VideoInfo(
                displayName = cursor.getString(nameColumn) ?: file.name,
                filePath = file.absolutePath,
                size = cursor.getLong(sizeColumn),
                duration = duration,
                dateModified = cursor.getLong(dateColumn),
                mediaId = cursor.getLong(idColumn),
                dateAdded = cursor.getLong(dateAddedColumn),
                mimeType = cursor.getString(mimeColumn) ?: "",
                isAudio = true,
                title = cursor.getString(titleColumn)?.takeIf { it.isNotBlank() }
                  ?: file.nameWithoutExtension,
              )
          }

          for ((folderKey, aggregate) in audioByFolder) {
            val audioNode = createDirectNode(aggregate.path, aggregate.videos, newBadgeConfig, currentTimeMs)
            val existing = folders[folderKey]
            folders[folderKey] =
              if (existing == null) {
                audioNode
              } else {
                existing.apply {
                  directVideoCount += audioNode.directVideoCount
                  directSize += audioNode.directSize
                  directDuration += audioNode.directDuration
                  directLastModified = maxOf(directLastModified, audioNode.directLastModified)
                  directNewCount += audioNode.directNewCount
                  directVideos = directVideos + audioNode.directVideos
                }
              }
          }
        }
    } catch (e: Exception) {
      Log.e(TAG, "MediaStore audio tree scan error", e)
    }
  }

  private fun isVideoNew(
    displayName: String,
    filePath: String,
    dateModifiedSeconds: Long,
    currentTimeMs: Long,
    newBadgeConfig: NewBadgeConfig,
  ): Boolean {
    if (!newBadgeConfig.enabled) {
      return false
    }

    val identifier = PlaybackIdentity.forLocalPath(filePath)
    if (displayName in newBadgeConfig.playedMediaTitles || identifier in newBadgeConfig.playedMediaTitles) {
      return false
    }
    newBadgeConfig.newLabelOverrides[identifier]?.let { return it }

    val videoAgeMs = currentTimeMs - (dateModifiedSeconds * 1000L)
    return newBadgeConfig.thresholdMillis == 0L || videoAgeMs <= newBadgeConfig.thresholdMillis
  }

  private fun scanFileSystemRoots(
    context: Context,
    folders: MutableMap<String, FolderNode>,
    options: MediaScanOptions,
    noMediaPathFilter: NoMediaPathFilter,
    forceFileSystemCheck: Boolean,
    newBadgeConfig: NewBadgeConfig,
    currentTimeMs: Long,
  ) {
    if (app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY) return
    try {
      val rootsToScan = linkedSetOf<File>()
      val primaryStorageRoot = Environment.getExternalStorageDirectory()

      if (shouldIncludePrimaryStorageInFilesystemFolderScan(options, forceFileSystemCheck)) {
        rootsToScan += primaryStorageRoot
      }

      rootsToScan += getPrimaryStorageSupplementalScanRoots(primaryStorageRoot)

      for (volume in StorageVolumeUtils.getExternalStorageVolumes(context)) {
        val volumePath = StorageVolumeUtils.getVolumePath(volume) ?: continue
        rootsToScan += File(volumePath)
      }

      for (root in rootsToScan) {
        if (!root.exists() || !root.canRead() || !root.isDirectory) {
          continue
        }

        scanDirectoryRecursive(
          directory = root,
          folders = folders,
          maxDepth = 20,
          options = options,
          noMediaPathFilter = noMediaPathFilter,
          newBadgeConfig = newBadgeConfig,
          currentTimeMs = currentTimeMs,
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Filesystem tree scan error", e)
    }
  }

  private fun scanDirectoryRecursive(
    directory: File,
    folders: MutableMap<String, FolderNode>,
    maxDepth: Int,
    options: MediaScanOptions,
    noMediaPathFilter: NoMediaPathFilter,
    newBadgeConfig: NewBadgeConfig,
    currentTimeMs: Long,
    currentDepth: Int = 0,
  ) {
    if (currentDepth >= maxDepth) return
    if (!directory.isDirectory || !directory.canRead()) return
    if (FileFilterUtils.shouldSkipFolder(directory, options, noMediaPathFilter)) return

    try {
      val files = directory.listFiles() ?: return
      val mediaFiles = mutableListOf<File>()
      val mediaStats = HashMap<File, FileEntryStat>()
      // Decoding duration is the most expensive step; it used to run twice per audio file.
      val audioDurations = HashMap<File, Long>()
      val mediaDurations = HashMap<File, Long>()
      val subdirectories = mutableListOf<File>()

      for (file in files) {
        try {
          val stat = readFileEntryStat(file) ?: continue
          when {
            stat.isDirectory -> {
              if (!FileFilterUtils.shouldSkipFolder(file, options, noMediaPathFilter)) {
                subdirectories.add(file)
              }
            }

            stat.isFile -> {
              if (FileFilterUtils.shouldSkipFile(file, options, noMediaPathFilter)) {
                continue
              }
              if (FileTypeUtils.isSupportedMediaFile(file, options)) {
                if (!options.includesPath(directory.absolutePath) || !options.includesSize(stat.size)) continue
                val isAudio = FileTypeUtils.isAudioFile(file)
                val duration = if (isAudio || options.filtersDuration(isAudio = false))
                  FileTypeUtils.getDurationMs(file) else 0L
                if (options.includesMedia(stat.size, duration, isAudio)) {
                  mediaFiles.add(file)
                  mediaStats[file] = stat
                  mediaDurations[file] = duration
                  if (isAudio) audioDurations[file] = duration
                }
              }
            }
          }
        } catch (_: SecurityException) {
        }
      }

      val folderPath = normalizeStoragePath(directory.absolutePath) ?: return
      val folderKey = storagePathKey(folderPath) ?: return

      if (mediaFiles.isNotEmpty()) {
        val existingNode = folders[folderKey]
        val directNewCount =
          if (newBadgeConfig.enabled) {
            mediaFiles.count { file ->
              isVideoNew(
                displayName = file.name,
                filePath = file.absolutePath,
                dateModifiedSeconds = mediaStats.getValue(file).lastModifiedMs / 1000L,
                currentTimeMs = currentTimeMs,
                newBadgeConfig = newBadgeConfig,
              )
            }
          } else {
            0
          }

        if (existingNode == null) {
          folders[folderKey] =
            FolderNode(
              path = folderPath,
              name = leafStorageName(folderPath),
              directVideoCount = mediaFiles.size,
              directSize = mediaFiles.sumOf { mediaStats.getValue(it).size },
              directDuration = audioDurations.values.sum(),
              directLastModified = (mediaFiles.maxOfOrNull { mediaStats.getValue(it).lastModifiedMs } ?: 0L) / 1000L,
              directNewCount = directNewCount,
              hasDirectSubfolders = subdirectories.isNotEmpty(),
              // Rows found only on the filesystem have no MediaStore id; the video list
              // builder falls back to a file URI and path-derived id for them.
              directVideos =
                mediaFiles.map { file ->
                  val stat = mediaStats.getValue(file)
                  VideoInfo(
                    displayName = file.name,
                    filePath = file.absolutePath,
                    size = stat.size,
                    duration = mediaDurations[file] ?: 0L,
                    dateModified = stat.lastModifiedMs / 1000L,
                    isAudio = FileTypeUtils.isAudioFile(file),
                    title = file.nameWithoutExtension,
                  )
                },
            )
        } else {
          existingNode.path = choosePreferredStoragePath(existingNode.path, folderPath)
          existingNode.name = leafStorageName(existingNode.path)
          existingNode.hasDirectSubfolders = existingNode.hasDirectSubfolders || subdirectories.isNotEmpty()
        }
      } else if (subdirectories.isNotEmpty()) {
        folders[folderKey]?.hasDirectSubfolders = true
      }

      for (subdir in subdirectories) {
        scanDirectoryRecursive(
          directory = subdir,
          folders = folders,
          maxDepth = maxDepth,
          options = options,
          noMediaPathFilter = noMediaPathFilter,
          newBadgeConfig = newBadgeConfig,
          currentTimeMs = currentTimeMs,
          currentDepth = currentDepth + 1,
        )
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error scanning: ${directory.absolutePath}", e)
    }
  }

  /**
   * Make a filtered copy: never mutate the shared MediaStore-backed tree cache, which is also
   * used by the unfiltered scanner and other screens.
   */
  private fun filterBlacklistedTree(
    context: Context,
    allFolders: Map<String, FolderNode>,
    blacklistedPaths: Set<String>,
  ): Map<String, FolderNode> {
    if (blacklistedPaths.isEmpty()) return allFolders
    val visible = linkedMapOf<String, FolderNode>()
    for ((key, node) in allFolders) {
      if (blacklistedPaths.any { isMediaPathInFolder(node.path, it) }) continue
      visible[key] = node.copy(isFlattened = false, hasDirectSubfolders = false)
    }
    buildParentHierarchy(visible)
    markFlattenedFolders(visible, getStorageRootKeys(context))
    visible.entries.removeIf { it.value.recursiveVideoCount <= 0 }
    return visible
  }

  private fun buildParentHierarchy(folders: MutableMap<String, FolderNode>) {
    for (folderData in folders.values.toList()) {
      var currentPath = parentStoragePath(folderData.path)
      while (currentPath != null && currentPath != "/" && currentPath.length > 1) {
        val currentKey = storagePathKey(currentPath) ?: break
        folders.putIfAbsent(
          currentKey,
          FolderNode(
            path = currentPath,
            name = leafStorageName(currentPath),
          ),
        )
        currentPath = parentStoragePath(currentPath)
      }
    }

    folders.values.forEach { node ->
      node.recursiveVideoCount = node.directVideoCount
      node.recursiveSize = node.directSize
      node.recursiveDuration = node.directDuration
      node.recursiveLastModified = node.directLastModified
      node.recursiveNewCount = node.directNewCount
    }

    val sortedPaths =
      folders.values
        .sortedByDescending { normalizeStoragePath(it.path)?.count { c -> c == '/' } ?: 0 }
        .mapNotNull { storagePathKey(it.path) }
        .distinct()

    for (pathKey in sortedPaths) {
      val childData = folders[pathKey] ?: continue
      val parentPath = parentStoragePath(childData.path) ?: continue
      val parentKey = storagePathKey(parentPath) ?: continue
      val parentData = folders[parentKey] ?: continue

      parentData.path = choosePreferredStoragePath(parentData.path, parentPath)
      parentData.name = leafStorageName(parentData.path)
      parentData.recursiveVideoCount += childData.recursiveVideoCount
      parentData.recursiveSize += childData.recursiveSize
      parentData.recursiveDuration += childData.recursiveDuration
      parentData.recursiveLastModified =
        maxOf(parentData.recursiveLastModified, childData.recursiveLastModified)
      parentData.recursiveNewCount += childData.recursiveNewCount
      parentData.hasDirectSubfolders = true
    }
  }

  private fun markFlattenedFolders(
    folders: MutableMap<String, FolderNode>,
    storageRootKeys: Set<String>,
  ) {
    val sortedPaths =
      folders.values
        .sortedBy { normalizeStoragePath(it.path)?.count { c -> c == '/' } ?: 0 }
        .mapNotNull { storagePathKey(it.path) }
        .distinct()
    val childIndex = indexDirectChildren(folders)

    for (pathKey in sortedPaths) {
      val node = folders[pathKey] ?: continue
      if (node.directVideoCount > 0) {
        continue
      }

      val childrenWithMedia =
        getDirectChildren(node.path, childIndex)
          .filter { it.recursiveVideoCount > 0 }

      if (childrenWithMedia.size == 1 && pathKey !in storageRootKeys) {
        node.isFlattened = true
      }
    }
  }

  /**
   * Parent key -> direct children, in map order. Replaces a full scan of every node per lookup,
   * which made flattening and tree navigation O(N^2) on large libraries.
   */
  private fun indexDirectChildren(allNodes: Map<String, FolderNode>): Map<String, List<FolderNode>> {
    val index = HashMap<String, MutableList<FolderNode>>()
    for ((key, node) in allNodes) {
      val childKey = storagePathKey(node.path) ?: continue
      val parentKey = childKey.substringBeforeLast('/', "")
      if (parentKey.isEmpty() || !key.startsWith("$parentKey/")) continue
      index.getOrPut(parentKey) { mutableListOf() }.add(node)
    }
    return index
  }

  private fun getDirectChildren(
    parentPath: String,
    childIndex: Map<String, List<FolderNode>>,
  ): List<FolderNode> {
    val parentKey = storagePathKey(parentPath) ?: return emptyList()
    return childIndex[parentKey].orEmpty()
  }

  private fun getEffectiveChildren(
    parentPath: String,
    childIndex: Map<String, List<FolderNode>>,
    remainingLevels: Int = -1,
  ): List<FolderNode> {
    val directChildren = getDirectChildren(parentPath, childIndex)
    val result = mutableListOf<FolderNode>()

    for (child in directChildren) {
      if (child.isFlattened && remainingLevels != 0) {
        val nextLevel = if (remainingLevels < 0) -1 else remainingLevels - 1
        result += getEffectiveChildren(child.path, childIndex, nextLevel)
      } else {
        result += child
      }
    }

    return result
  }

  private fun toFolderData(node: FolderNode): FolderData =
    FolderData(
      path = node.path,
      name = node.name,
      videoCount = node.recursiveVideoCount,
      totalSize = node.recursiveSize,
      totalDuration = node.recursiveDuration,
      lastModified = node.recursiveLastModified,
      hasSubfolders = node.hasDirectSubfolders,
      newCount = node.recursiveNewCount,
    )

  private fun getStorageRootKeys(context: Context): Set<String> {
    val rootKeys = mutableSetOf<String>()
    storagePathKey(Environment.getExternalStorageDirectory().absolutePath)?.let(rootKeys::add)

    for (volume in StorageVolumeUtils.getExternalStorageVolumes(context)) {
      storagePathKey(StorageVolumeUtils.getVolumePath(volume))?.let(rootKeys::add)
    }

    return rootKeys
  }
}
