package com.github.mr3zee.kotlinPlugins

import com.intellij.idea.LoggerFactory
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class KotlinPluginsExceptionAnalyzerState : BaseState() {
    var enabled by property(false)
}

@Service(Service.Level.PROJECT)
@State(
    name = "com.github.mr3zee.kotlinPlugins.KotlinPluginsExceptionAnalyzer",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class KotlinPluginsExceptionAnalyzer(
    private val project: Project,
    private val scope: CoroutineScope,
) : SimplePersistentStateComponent<KotlinPluginsExceptionAnalyzerState>(KotlinPluginsExceptionAnalyzerState()) {
    private val logger by lazy { thisLogger() }
    private val supervisor = SupervisorJob(scope.coroutineContext.job)
    private val job = AtomicReference<Job?>(null)

    private fun startJob() {
        if (job.get()?.isActive == true) {
            return
        }

        val newJob = scope.launch(
            context = Dispatchers.IO + supervisor + CoroutineName("exception-analyzer-root"),
            start = CoroutineStart.LAZY,
        ) {
            logger.debug("Exception analyzer started")
            val ideaLog = LoggerFactory.getLogFilePath()
            var maybeException = false
            var exceptionMessageLine: String? = null
            val stackTrace = mutableListOf<String>()

            tailIdeaLog(ideaLog) { line ->
                // Analyze each log line here:
                when {
                    "SEVERE" in line -> {
                        maybeException = true
                    }

                    // not a year -> not a line log -> exception part
                    maybeException && line.take(4).toIntOrNull() == null -> {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("at ") && exceptionMessageLine != null) {
                            // stacktrace line
                            val stackElement = trimmed.removePrefix("at ")
                            stackTrace.add(stackElement)
                        } else {
                            // exception message line
                            val message = trimmed.removePrefix("Suppressed: ")
                            exceptionMessageLine = message
                        }
                    }

                    maybeException -> {
                        if (exceptionMessageLine != null) {
                            try {
                                analyzeAndReport(exceptionMessageLine ?: "", stackTrace)
                            } catch (e: Exception) {
                                logger.error("Failed to analyze exception $exceptionMessageLine: ${e.message}")
                            }
                        }

                        maybeException = false
                        exceptionMessageLine = null
                        stackTrace.clear()
                    }
                }
            }
        }

        if (job.compareAndSet(null, newJob)) {
            newJob.start()
        }
    }

    fun start() {
        if (!state.enabled) {
            return
        }

        startJob()
    }

    fun enable() {
        state.enabled = true
        start()
    }

    fun disable() {
        state.enabled = false
        job.getAndSet(null)?.cancel()
    }

    private fun analyzeAndReport(messageLine: String, stackTrace: List<String>) {
        if (stackTrace.isEmpty()) {
            return
        }

        if (stackTrace.any { it.startsWith("kotlinx.rpc.") }) {
            logger.debug("Kotlin RPC exception detected: $messageLine")
            val classAndMessage = messageLine.split(": ", limit = 2)
            if (classAndMessage.size == 2) {
                val (className, message) = classAndMessage
                val lastIndex = stackTrace.indexOfLast { it.startsWith("kotlinx.rpc.") }
                val filteredStackTrace = stackTrace.subList(0, lastIndex + 1)

                println("Kotlin RPC exception detected: $className: $message" + "\n" + filteredStackTrace.joinToString("\n    at "))
            } else {
                logger.debug("Failed to parse Kotlin RPC exception: $messageLine")
            }
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun tailIdeaLog(
        logPath: Path,
        pollDelay: Duration = 300.milliseconds,
        onLine: (String) -> Unit,
    ) {
        if (!logPath.exists()) {
            logger.debug("idea.log not found at: $logPath")
            return
        }

        var raf: RandomAccessFile? = null
        var currentKey: FileKey? = null
        var lastKnownLength = 0L

        fun closeFile() {
            runCatching { raf?.close() }
            raf = null
            currentKey = null
            lastKnownLength = 0L
        }

        try {
            while (true) {
                if (raf == null) {
                    try {
                        raf = RandomAccessFile(logPath.toFile(), "r")

                        raf!!.apply {
                            // Start at end to only read new lines
                            seek(length())
                            lastKnownLength = length()
                            currentKey = fileKey(logPath)

                            logger.debug("Opened idea.log at $logPath (size=$lastKnownLength, key=$currentKey)")
                        }
                    } catch (e: Exception) {
                        logger.debug("Failed to open idea.log: ${e.message}")
                        closeFile()
                        delay(pollDelay)
                        continue
                    }
                }

                yield()

                val file = raf
                var lineRead = false
                try {
                    while (true) {
                        val line = file?.readLine() ?: break
                        lineRead = true
                        // readLine() returns ISO-8859-1; idea.log is UTF-8.
                        // For simplicity, attempt to recover by interpreting bytes as ISO then re-encode to UTF-8.
                        val text = line.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8)
                        onLine(text)
                        yield()
                    }
                } catch (e: IOException) {
                    logger.debug("Failed to read line from idea.log: ${e.message}")
                    closeFile()
                    continue
                }

                // Detect rotation or truncation
                val rotated = try {
                    val nowKey = fileKey(logPath)
                    val nowLen = Files.size(logPath)
                    val truncated = nowLen < lastKnownLength
                    val changedKey = nowKey != null && currentKey != null && nowKey != currentKey
                    if (truncated || changedKey) {
                        logger.debug("Detected log rotation/truncation (truncated=$truncated, changedKey=$changedKey). Reopening.")
                        true
                    } else {
                        lastKnownLength = nowLen
                        false
                    }
                } catch (e: Exception) {
                    // If attributes fail, assume rotation and try reopening
                    logger.debug("Attribute check failed, assuming rotation: ${e.message}")
                    true
                }

                if (rotated) {
                    closeFile()
                    continue
                }

                if (!lineRead) {
                    delay(pollDelay)
                }
            }
        } finally {
            closeFile()
        }
    }

    private data class FileKey(val id: Any?)

    private fun fileKey(path: Path): FileKey? {
        return try {
            val attrs: BasicFileAttributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            FileKey(attrs.fileKey())
        } catch (_: Exception) {
            null
        }
    }
}
