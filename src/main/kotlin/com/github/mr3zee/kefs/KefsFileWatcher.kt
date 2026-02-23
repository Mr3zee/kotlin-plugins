package com.github.mr3zee.kefs

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.Watchable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.PathWalkOption
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.walk

internal interface FileWatcherCallback {
    fun onLocalRepoChange(repoRoot: Path)
    fun onCacheDirExternalChange()
}

internal class KefsFileWatcher(
    private val callback: FileWatcherCallback,
) : Closeable {
    private lateinit var watchService: WatchService

    private val watchedDirToRoot = ConcurrentHashMap<Path, Path>()
    private val registeredRoots = ConcurrentHashMap.newKeySet<Path>()
    private val localRepoRoots = ConcurrentHashMap.newKeySet<Path>()
    private val cacheDirSelfUpdates = AtomicLong(0)

    private val logger by lazy { thisLogger() }

    suspend fun registerLocalRepo(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        localRepoRoots.add(normalized)
        registerDirectoryTreeWatch(normalized)
    }

    suspend fun registerCacheDir(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        if (!::watchService.isInitialized) {
            watchService = withContext(Dispatchers.IO) {
                normalized.fileSystem.newWatchService()
            }
        }
        registerDirectoryTreeWatch(normalized)
    }

    fun markSelfUpdateStart() {
        cacheDirSelfUpdates.incrementAndGet()
    }

    fun markSelfUpdateEnd() {
        cacheDirSelfUpdates.decrementAndGet()
    }

    /**
     * Processes one event from the watch service.
     * Returns true if an event was processed, false if poll timed out.
     */
    suspend fun processOneEvent(): Boolean {
        val key = withContext(Dispatchers.IO) {
            watchService.poll(1000, TimeUnit.MILLISECONDS)
        } ?: return false

        // WatchKey.watchable() may return a different Path type (e.g. UnixPath)
        // than what we stored (e.g. MultiRoutingFsPath), so look up by string
        val rawPath = key.watchable() as? Path
        val path = rawPath?.let { findWatchedPath(it) } ?: rawPath

        if (!key.isValid || path == null) {
            val root = path?.let { watchedDirToRoot[it] }
            deregisterWatchedDir(path)
            if (root != null && !isLocalRepoRoot(root)) {
                callback.onCacheDirExternalChange()
            }
            return true
        }

        val root = watchedDirToRoot[path]
        if (root == null) {
            key.pollEvents()
            key.cancel()
            return true
        }

        val events = key.pollEvents()
        val isCacheDir = !isLocalRepoRoot(root)

        for (event in events) {
            val contextName = (event.context() as? Path)?.toString() ?: continue
            val resolved = path.resolve(contextName)

            when (event.kind()) {
                StandardWatchEventKinds.ENTRY_CREATE -> {
                    if (Files.isDirectory(resolved)) {
                        registerSubdirectoriesWatch(resolved, root)
                    }
                }

                StandardWatchEventKinds.ENTRY_DELETE -> {
                    if (isCacheDir) {
                        callback.onCacheDirExternalChange()
                        key.reset()
                        return true
                    }
                    watchedDirToRoot.keys.removeIf { it.startsWith(resolved) }
                }
            }
        }

        if (events.isEmpty()) {
            key.reset()
            return true
        }

        if (!isCacheDir) {
            callback.onLocalRepoChange(root)
        } else {
            if (cacheDirSelfUpdates.get() == 0L) {
                logger.debug("File watcher detected external changes in cache dir: ${events.map { it.context() }}")
                callback.onCacheDirExternalChange()
            }
        }
        key.reset()
        return true
    }

    fun cancelAllWatchKeys() {
        watchedDirToRoot.clear()
        registeredRoots.clear()
    }

    override fun close() {
        cancelAllWatchKeys()
        localRepoRoots.clear()
        if (::watchService.isInitialized) {
            runCatchingExceptCancellation { watchService.close() }
        }
    }

    private suspend fun registerDirectoryTreeWatch(root: Path) {
        if (!registeredRoots.add(root)) return
        if (!root.exists()) {
            registeredRoots.remove(root)
            return
        }
        registerSubdirectoriesWatch(root, root)
    }

    @OptIn(ExperimentalPathApi::class)
    private suspend fun registerSubdirectoriesWatch(dir: Path, root: Path) {
        runCatchingExceptCancellation {
            withContext(Dispatchers.IO) {
                dir.walk(PathWalkOption.INCLUDE_DIRECTORIES).filter {
                    it.isDirectory()
                }.forEach { subdir ->
                    if (watchedDirToRoot.putIfAbsent(subdir, root) == null) {
                        subdir.registerSafe(
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE,
                        )
                    }
                }
            }
        }
    }

    /**
     * WatchKey.watchable() may return a different Path type than what was stored
     * in watchedDirToRoot (e.g. sun.nio.fs.UnixPath vs MultiRoutingFsPath).
     * Look up by string path to find the matching stored key.
     */
    private fun findWatchedPath(rawPath: Path): Path? {
        // Fast path: direct lookup works when Path types match
        if (watchedDirToRoot.containsKey(rawPath)) return rawPath
        // Slow path: match by string representation
        val pathStr = rawPath.toString()
        return watchedDirToRoot.keys.find { it.toString() == pathStr }
    }

    private fun isLocalRepoRoot(root: Path): Boolean {
        return localRepoRoots.contains(root)
    }

    private fun deregisterWatchedDir(dir: Path?) {
        if (dir != null) {
            watchedDirToRoot.remove(dir)
        }
    }

    @Suppress("SameParameterValue")
    private fun Watchable.registerSafe(vararg events: WatchEvent.Kind<*>): WatchKey? {
        return try {
            register(watchService, *events)
        } catch (e: Exception) {
            logger.warn("Failed to register watch key for $this", e)
            null
        }
    }
}
