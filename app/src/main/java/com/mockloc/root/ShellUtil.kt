package com.mockloc.root

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val isSuccess: Boolean = exitCode == 0 && !timedOut
)

interface ShellExecutor {
    suspend fun execRoot(command: String, timeoutMs: Long = ShellUtil.DEFAULT_TIMEOUT_MS): ShellResult
    suspend fun execShell(command: String, timeoutMs: Long = ShellUtil.DEFAULT_TIMEOUT_MS): ShellResult
    suspend fun hasRootAccess(timeoutMs: Long = ShellUtil.DEFAULT_PROBE_TIMEOUT_MS): Boolean
    suspend fun detectRootProvider(timeoutMs: Long = ShellUtil.DEFAULT_PROBE_TIMEOUT_MS): String
}

object ShellUtil : ShellExecutor {
    const val DEFAULT_TIMEOUT_MS = 5_000L
    const val DEFAULT_PROBE_TIMEOUT_MS = 2_500L

    private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    fun isValidPackageName(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return PACKAGE_NAME_REGEX.matches(packageName.trim())
    }

    fun buildProcessCommand(useRoot: Boolean, command: String): List<String> {
        val binary = if (useRoot) "su" else "sh"
        return listOf(binary, "-c", command)
    }

    override suspend fun execRoot(command: String, timeoutMs: Long): ShellResult {
        if (command.isBlank()) {
            return ShellResult(exitCode = -1, stdout = "", stderr = "Empty command", isSuccess = false)
        }
        return executeCommand(buildProcessCommand(useRoot = true, command = command), timeoutMs)
    }

    override suspend fun execShell(command: String, timeoutMs: Long): ShellResult {
        if (command.isBlank()) {
            return ShellResult(exitCode = -1, stdout = "", stderr = "Empty command", isSuccess = false)
        }
        return executeCommand(buildProcessCommand(useRoot = false, command = command), timeoutMs)
    }

    override suspend fun hasRootAccess(timeoutMs: Long): Boolean {
        val result = execRoot("id", timeoutMs)
        return result.isSuccess && result.stdout.contains("uid=0")
    }

    private val shellIoPool = ThreadPoolExecutor(
        24,
        96,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(256)
    ) { runnable ->
        Thread(runnable, "MockLoc-ShellIO").apply { isDaemon = true }
    }.apply {
        allowCoreThreadTimeOut(true)
    }

    override suspend fun detectRootProvider(timeoutMs: Long): String {
        return detectRootProviderInternal(
            timeoutMs = timeoutMs,
            shellRunner = { cmd, t -> execShell(cmd, t) },
            rootChecker = { t -> hasRootAccess(t) },
            rootRunner = { cmd, t -> execRoot(cmd, t) }
        )
    }

    internal suspend fun detectRootProviderInternal(
        timeoutMs: Long,
        shellRunner: suspend (String, Long) -> ShellResult,
        rootChecker: suspend (Long) -> Boolean,
        rootRunner: suspend (String, Long) -> ShellResult
    ): String {
        val probeCmd = listOf(
            "su -v 2>/dev/null",
            "su -V 2>/dev/null",
            "ls -d /data/adb/magisk /data/adb/ksu /data/adb/ap 2>/dev/null",
            "which magisk ksud apd 2>/dev/null"
        ).joinToString("; ")

        val shellProbe = shellRunner(probeCmd, timeoutMs)
        val combined = buildString {
            append(shellProbe.stdout)
            append('\n')
            append(shellProbe.stderr)
        }.lowercase()

        val shellDetected = identifyProviderName(combined)

        // 无论普通 sh 是否探测到框架特征，必须校验当前应用是否真正获得 Root 授权
        val rootAvailable = rootChecker(timeoutMs)
        if (!rootAvailable) return "None"

        if (shellDetected != null) {
            return shellDetected
        }

        val rootProbe = rootRunner(probeCmd, timeoutMs)
        val rootCombined = buildString {
            append(rootProbe.stdout)
            append('\n')
            append(rootProbe.stderr)
        }.lowercase()

        return identifyProviderName(rootCombined) ?: "Root"
    }

    internal fun identifyProviderName(rawOutput: String): String? {
        val lower = rawOutput.lowercase()
        return when {
            lower.contains("magisk") -> "Magisk"
            lower.contains("kernelsu") || lower.contains("ksud") || lower.contains("/data/adb/ksu") -> "KernelSU"
            lower.contains("apatch") || lower.contains("apd") || lower.contains("/data/adb/ap") -> "APatch"
            lower.contains("supersu") -> "SuperSU"
            else -> null
        }
    }

    internal suspend fun executeCommand(
        cmd: List<String>,
        timeoutMs: Long,
        processFactory: (List<String>) -> Process = { ProcessBuilder(it).start() }
    ): ShellResult = withContext(Dispatchers.IO) {
        val safeTimeout = timeoutMs.coerceAtLeast(100L)
        var process: Process? = null
        var waitFuture: CompletableFuture<Int>? = null
        var stdoutCollector: StreamCollector? = null
        var stderrCollector: StreamCollector? = null
        try {
            process = processFactory(cmd)
            val activeProcess = process
            val outCollector = StreamCollector(activeProcess.inputStream).also { stdoutCollector = it }
            val errCollector = StreamCollector(activeProcess.errorStream).also { stderrCollector = it }
            shellIoPool.execute { outCollector.readLoop() }
            shellIoPool.execute { errCollector.readLoop() }

            val wf = CompletableFuture.supplyAsync({ activeProcess.waitFor() }, shellIoPool)
            waitFuture = wf

            val exitCode = withTimeoutOrNull(safeTimeout) {
                suspendCancellableCoroutine<Int> { cont ->
                    wf.whenComplete { code, err ->
                        if (cont.isActive) {
                            if (err != null) {
                                cont.resumeWithException(err)
                            } else {
                                cont.resume(code)
                            }
                        }
                    }
                    cont.invokeOnCancellation {
                        wf.cancel(true)
                    }
                }
            }

            if (exitCode == null) {
                wf.cancel(true)
                destroyProcessSafely(activeProcess)
                outCollector.cancel()
                errCollector.cancel()
                val collectDeadline = System.currentTimeMillis() + TIMEOUT_DRAIN_MS
                val partialOut = outCollector.awaitText(
                    (collectDeadline - System.currentTimeMillis()).coerceAtLeast(0L)
                ).trimEnd()
                val partialErr = errCollector.awaitText(
                    (collectDeadline - System.currentTimeMillis()).coerceAtLeast(0L)
                ).trimEnd()
                ShellResult(
                    exitCode = -1,
                    stdout = partialOut,
                    stderr = partialErr.ifBlank { "Timed out after ${safeTimeout}ms" },
                    isSuccess = false,
                    timedOut = true
                )
            } else {
                val stdoutText = outCollector.awaitText(NORMAL_DRAIN_MS)
                val stderrText = errCollector.awaitText(NORMAL_DRAIN_MS)
                closeProcessStreams(activeProcess)
                ShellResult(
                    exitCode = exitCode,
                    stdout = stdoutText.trimEnd(),
                    stderr = stderrText.trimEnd(),
                    isSuccess = exitCode == 0,
                    timedOut = false
                )
            }
        } catch (ce: CancellationException) {
            waitFuture?.cancel(true)
            process?.let { destroyProcessSafely(it) }
            stdoutCollector?.cancel()
            stderrCollector?.cancel()
            throw ce
        } catch (t: Throwable) {
            waitFuture?.cancel(true)
            process?.let { destroyProcessSafely(it) }
            stdoutCollector?.cancel()
            stderrCollector?.cancel()
            ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = t.message ?: t.javaClass.simpleName,
                isSuccess = false,
                timedOut = false
            )
        }
    }

    private const val TIMEOUT_DRAIN_MS = 50L
    private const val NORMAL_DRAIN_MS = 300L

    private class StreamCollector(private val stream: InputStream) {
        private val buffer = ByteArrayOutputStream()
        @Volatile
        private var done: Boolean = false
        @Volatile
        private var cancelled: Boolean = false
        private val latch = CountDownLatch(1)

        fun cancel() {
            cancelled = true
            runCatching { stream.close() }
        }

        fun readLoop() {
            try {
                val chunk = ByteArray(4096)
                while (!cancelled && !Thread.currentThread().isInterrupted) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    if (n > 0) {
                        synchronized(buffer) {
                            buffer.write(chunk, 0, n)
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                done = true
                latch.countDown()
            }
        }

        fun awaitText(maxWaitMs: Long): String {
            if (!done && maxWaitMs > 0L) {
                runCatching { latch.await(maxWaitMs, TimeUnit.MILLISECONDS) }
            }
            return synchronized(buffer) {
                buffer.toString(StandardCharsets.UTF_8.name())
            }
        }
    }

    private fun closeProcessStreams(process: Process) {
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
    }

    private fun destroyProcessSafely(process: Process) {
        // 1. 先通过公共接口 java.lang.ProcessHandle 强杀全部后代孙进程（避免 ProcessHandleImpl 模块访问 IllegalAccessException）
        val killedViaProcessHandle = destroyDescendantsViaProcessHandle(process)
        if (!killedViaProcessHandle) {
            // 2. 缺失 ProcessHandle 的旧 Android ART 环境兜底：通过 /proc 枚举子进程树并发送 SIGKILL
            destroyDescendantsViaProcFallback(process)
        }
        // 3. 再强杀直接子进程本身
        runCatching { process.destroyForcibly() }
        // 4. 最后关闭进程三路流
        closeProcessStreams(process)
    }

    private fun destroyDescendantsViaProcessHandle(process: Process): Boolean {
        return try {
            val phClass = Class.forName("java.lang.ProcessHandle")
            val destroyForciblyMethod = phClass.getMethod("destroyForcibly")
            val isAliveMethod = phClass.getMethod("isAlive")
            val descendantsMethod = Process::class.java.getMethod("descendants")

            for (pass in 0 until 2) {
                val stream = descendantsMethod.invoke(process) as? java.util.stream.Stream<*>
                    ?: return false
                val handles = stream.use { s ->
                    val list = ArrayList<Any?>()
                    val iter = s.iterator()
                    while (iter.hasNext()) {
                        list.add(iter.next())
                    }
                    list
                }
                var killedAny = false
                for (i in handles.indices.reversed()) {
                    val handle = handles[i] ?: continue
                    val alive = runCatching { isAliveMethod.invoke(handle) as? Boolean }.getOrNull()
                    if (pass == 0 || alive == true) {
                        runCatching { destroyForciblyMethod.invoke(handle) }
                        killedAny = true
                    }
                }
                if (!killedAny) break
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun destroyDescendantsViaProcFallback(process: Process) {
        val rootPid = extractProcessPid(process) ?: return
        val myPid = runCatching { android.os.Process.myPid() }.getOrDefault(-1)
        val descendants = collectProcDescendants(rootPid)
        for (i in descendants.indices.reversed()) {
            val childPid = descendants[i]
            if (childPid > 0 && childPid != myPid) {
                killPidForcibly(childPid)
            }
        }
        if (rootPid > 0 && rootPid != myPid) {
            killPidForcibly(rootPid)
        }
    }

    private fun extractProcessPid(process: Process): Int? {
        runCatching {
            val pidMethod = Process::class.java.getMethod("pid")
            val pid = (pidMethod.invoke(process) as? Number)?.toInt()
            if (pid != null && pid > 0) return pid
        }
        var cls: Class<*>? = process.javaClass
        while (cls != null && cls !== Any::class.java) {
            val pid = runCatching {
                val f = cls.getDeclaredField("pid")
                f.isAccessible = true
                (f.get(process) as? Number)?.toInt()
            }.getOrNull()
            if (pid != null && pid > 0) return pid
            cls = cls.superclass
        }
        return null
    }

    private fun collectProcDescendants(rootPid: Int): List<Int> {
        val result = ArrayList<Int>()
        val visited = HashSet<Int>()
        val queue = ArrayDeque<Int>()
        visited.add(rootPid)
        queue.addLast(rootPid)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val directChildren = readDirectChildrenFromProc(current)
            for (child in directChildren) {
                if (child > 0 && visited.add(child)) {
                    result.add(child)
                    queue.addLast(child)
                }
            }
        }
        return result
    }

    private fun readDirectChildrenFromProc(ppid: Int): List<Int> {
        val children = ArrayList<Int>()
        val taskDirs = File("/proc/$ppid/task").listFiles()
        if (taskDirs != null) {
            for (tidDir in taskDirs) {
                val childrenFile = File(tidDir, "children")
                if (childrenFile.isFile && childrenFile.canRead()) {
                    runCatching {
                        childrenFile.readText().trim().split(Regex("\\s+")).forEach { token ->
                            token.toIntOrNull()?.let { children.add(it) }
                        }
                    }
                }
            }
        }
        if (children.isNotEmpty()) return children

        val procDirs = File("/proc").listFiles() ?: return emptyList()
        for (entry in procDirs) {
            val pid = entry.name.toIntOrNull() ?: continue
            val statFile = File(entry, "stat")
            if (!statFile.isFile || !statFile.canRead()) continue
            runCatching {
                val line = statFile.readText()
                val rparen = line.lastIndexOf(')')
                if (rparen > 0 && rparen + 2 < line.length) {
                    val rest = line.substring(rparen + 2).trim().split(Regex("\\s+"))
                    val entryPpid = rest.getOrNull(1)?.toIntOrNull()
                    if (entryPpid == ppid) {
                        children.add(pid)
                    }
                }
            }
        }
        return children
    }

    private fun killPidForcibly(pid: Int) {
        val killedByOs = runCatching {
            android.system.Os.kill(pid, android.system.OsConstants.SIGKILL)
            true
        }.getOrDefault(false)
        if (!killedByOs) {
            runCatching { android.os.Process.sendSignal(pid, 9) }
        }
    }
}
