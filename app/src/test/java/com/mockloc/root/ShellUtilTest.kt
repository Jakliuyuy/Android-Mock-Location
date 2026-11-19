package com.mockloc.root

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ShellUtilTest {

    @Test
    fun `isValidPackageName accepts normal package names and rejects command injection`() {
        assertTrue(ShellUtil.isValidPackageName("com.mockloc"))
        assertTrue(ShellUtil.isValidPackageName("com.tencent.mm"))
        assertTrue(ShellUtil.isValidPackageName("com.example.app_v2"))

        assertFalse(ShellUtil.isValidPackageName(null))
        assertFalse(ShellUtil.isValidPackageName(""))
        assertFalse(ShellUtil.isValidPackageName("com.app; rm -rf /"))
        assertFalse(ShellUtil.isValidPackageName("com.app && id"))
        assertFalse(ShellUtil.isValidPackageName("com.app | cat /etc/passwd"))
        assertFalse(ShellUtil.isValidPackageName("singleword"))
    }

    @Test
    fun `buildProcessCommand constructs su or sh commands`() {
        assertEquals(listOf("su", "-c", "id"), ShellUtil.buildProcessCommand(true, "id"))
        assertEquals(listOf("sh", "-c", "echo hi"), ShellUtil.buildProcessCommand(false, "echo hi"))
    }

    @Test
    fun `executeCommand captures stdout and exitCode 0 on success`() = runTest {
        val process = FakeProcess(
            exitCode = 0,
            stdout = "uid=0(root) gid=0(root)\n",
            stderr = ""
        )
        val result = ShellUtil.executeCommand(
            cmd = listOf("su", "-c", "id"),
            timeoutMs = 2_000L,
            processFactory = { process }
        )

        assertTrue(result.isSuccess)
        assertFalse(result.timedOut)
        assertEquals(0, result.exitCode)
        assertEquals("uid=0(root) gid=0(root)", result.stdout)
    }

    @Test
    fun `executeCommand captures stderr and non-zero exitCode on failure`() = runTest {
        val process = FakeProcess(
            exitCode = 1,
            stdout = "",
            stderr = "Permission denied\n"
        )
        val result = ShellUtil.executeCommand(
            cmd = listOf("su", "-c", "id"),
            timeoutMs = 2_000L,
            processFactory = { process }
        )

        assertFalse(result.isSuccess)
        assertFalse(result.timedOut)
        assertEquals(1, result.exitCode)
        assertEquals("Permission denied", result.stderr)
    }

    @Test
    fun `executeCommand destroys hanging process on timeout without blocking caller`() = runTest {
        val process = FakeProcess(
            exitCode = 0,
            stdout = "",
            stderr = "",
            hangDurationMs = 10_000L
        )
        val result = ShellUtil.executeCommand(
            cmd = listOf("su", "-c", "sleep 10"),
            timeoutMs = 150L,
            processFactory = { process }
        )

        assertFalse(result.isSuccess)
        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
        assertTrue(process.destroyed.get())
    }

    @Test
    fun `executeCommand handles processFactory exception gracefully`() = runTest {
        val result = ShellUtil.executeCommand(
            cmd = listOf("su", "-c", "id"),
            timeoutMs = 1_000L,
            processFactory = { throw java.io.IOException("su binary not found") }
        )

        assertFalse(result.isSuccess)
        assertFalse(result.timedOut)
        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("su binary not found"))
    }

    @Test
    fun `concurrent command executions remain isolated`() = runTest {
        val tasks = (1..8).map { idx ->
            async {
                ShellUtil.executeCommand(
                    cmd = listOf("sh", "-c", "echo $idx"),
                    timeoutMs = 2_000L,
                    processFactory = { FakeProcess(exitCode = 0, stdout = "out-$idx\n", stderr = "") }
                )
            }
        }
        val results = tasks.awaitAll()
        results.forEachIndexed { idx, res ->
            assertTrue(res.isSuccess)
            assertEquals("out-${idx + 1}", res.stdout)
        }
    }

    @Test
    fun `identifyProviderName detects Magisk KernelSU APatch SuperSU`() {
        assertEquals("Magisk", ShellUtil.identifyProviderName("27.0:MAGISKSU"))
        assertEquals("KernelSU", ShellUtil.identifyProviderName("/data/adb/ksu/bin"))
        assertEquals("APatch", ShellUtil.identifyProviderName("apd 0.10"))
        assertEquals("SuperSU", ShellUtil.identifyProviderName("SuperSU 2.82"))
        assertEquals(null, ShellUtil.identifyProviderName("unknown"))
    }

    @Test
    fun `executeCommand times out rapidly under 500ms on real sleeping child and grandchild processes`() = runTest {
        val startDirect = System.currentTimeMillis()
        val directResult = ShellUtil.executeCommand(
            cmd = listOf("sh", "-c", "sleep 20"),
            timeoutMs = 200L
        )
        val directElapsed = System.currentTimeMillis() - startDirect
        assertTrue(directResult.timedOut)
        assertFalse(directResult.isSuccess)
        assertEquals(-1, directResult.exitCode)
        assertTrue("Direct sleep 20 timed out too slowly: ${directElapsed}ms", directElapsed < 500L)

        val startGrandchild = System.currentTimeMillis()
        val grandchildResult = ShellUtil.executeCommand(
            cmd = listOf("sh", "-c", "sleep 2 & sleep 2"),
            timeoutMs = 200L
        )
        val grandchildElapsed = System.currentTimeMillis() - startGrandchild
        assertTrue(grandchildResult.timedOut)
        assertFalse(grandchildResult.isSuccess)
        assertEquals(-1, grandchildResult.exitCode)
        assertTrue("Grandchild FD inheritance blocked timeout: ${grandchildElapsed}ms", grandchildElapsed < 500L)
    }

    @Test
    fun `detectRootProvider returns None when su -v reports Magisk but su access is denied`() = runTest {
        val unauthorizedResult = ShellUtil.detectRootProviderInternal(
            timeoutMs = 500L,
            shellRunner = { _, _ -> ShellResult(exitCode = 0, stdout = "27.0:MAGISKSU", stderr = "") },
            rootChecker = { false },
            rootRunner = { _, _ -> ShellResult(exitCode = 1, stdout = "", stderr = "Permission denied") }
        )
        assertEquals("None", unauthorizedResult)

        val authorizedResult = ShellUtil.detectRootProviderInternal(
            timeoutMs = 500L,
            shellRunner = { _, _ -> ShellResult(exitCode = 0, stdout = "27.0:MAGISKSU", stderr = "") },
            rootChecker = { true },
            rootRunner = { _, _ -> ShellResult(exitCode = 0, stdout = "27.0:MAGISKSU", stderr = "") }
        )
        assertEquals("Magisk", authorizedResult)
    }

    @Test
    fun `repeated timeouts kill all grandchild processes and leave zero blocked MockLoc-ShellIO threads`() = runBlocking {
        val testStart = Instant.now().minusMillis(50)
        repeat(5) { attempt ->
            val res = ShellUtil.execShell("sleep 3 & sleep 3 & wait", 150L)
            assertTrue("Attempt $attempt should time out", res.timedOut)
            assertFalse("Attempt $attempt should fail", res.isSuccess)
        }

        Thread.sleep(250L)

        val blockedShellIoThreads = Thread.getAllStackTraces().entries.count { (thread, frames) ->
            thread.name.startsWith("MockLoc-ShellIO") &&
                frames.any { frame ->
                    frame.className == "java.io.FileInputStream" && frame.methodName == "readBytes"
                }
        }
        assertEquals(
            "Expected 0 MockLoc-ShellIO threads blocked in FileInputStream.readBytes",
            0,
            blockedShellIoThreads
        )

        val phClass = Class.forName("java.lang.ProcessHandle")
        val phInfoClass = Class.forName("java.lang.ProcessHandle\$Info")
        val currentHandle = phClass.getMethod("current").invoke(null)
        val descendantsStream = phClass.getMethod("descendants").invoke(currentHandle) as java.util.stream.Stream<*>
        val isAliveMethod = phClass.getMethod("isAlive")
        val infoMethod = phClass.getMethod("info")
        val parentMethod = phClass.getMethod("parent")
        val pidMethod = phClass.getMethod("pid")
        val cmdMethod = phInfoClass.getMethod("command")
        val cmdLineMethod = phInfoClass.getMethod("commandLine")
        val argsMethod = phInfoClass.getMethod("arguments")
        val startInstantMethod = phInfoClass.getMethod("startInstant")

        var remainingDescendants = 0
        descendantsStream.use { s ->
            val iter = s.iterator()
            while (iter.hasNext()) {
                val h = iter.next() ?: continue
                if (isAliveMethod.invoke(h) as? Boolean != true) continue
                val info = infoMethod.invoke(h)
                @Suppress("UNCHECKED_CAST")
                val cmdLine = (cmdLineMethod.invoke(info) as? java.util.Optional<String>)?.orElse("") ?: ""
                @Suppress("UNCHECKED_CAST")
                val cmd = (cmdMethod.invoke(info) as? java.util.Optional<String>)?.orElse("") ?: ""
                if (cmdLine.contains("sleep") || cmd.endsWith("/sleep") || cmd == "sleep") {
                    remainingDescendants++
                }
            }
        }
        assertEquals("Expected 0 alive sleep descendant processes", 0, remainingDescendants)

        val allStream = phClass.getMethod("allProcesses").invoke(null) as java.util.stream.Stream<*>
        var orphanSleeps = 0
        allStream.use { s ->
            val iter = s.iterator()
            while (iter.hasNext()) {
                val h = iter.next() ?: continue
                if (isAliveMethod.invoke(h) as? Boolean != true) continue
                val info = infoMethod.invoke(h)
                @Suppress("UNCHECKED_CAST")
                val startedAfterTest = (startInstantMethod.invoke(info) as? java.util.Optional<Instant>)
                    ?.map { !it.isBefore(testStart) }
                    ?.orElse(false) ?: false
                if (!startedAfterTest) continue
                @Suppress("UNCHECKED_CAST")
                val parentIsInit = (parentMethod.invoke(h) as? java.util.Optional<Any>)
                    ?.map { (pidMethod.invoke(it) as? Long) == 1L }
                    ?.orElse(false) ?: false
                if (!parentIsInit) continue
                @Suppress("UNCHECKED_CAST")
                val cmd = (cmdMethod.invoke(info) as? java.util.Optional<String>)?.orElse("") ?: ""
                @Suppress("UNCHECKED_CAST")
                val args = (argsMethod.invoke(info) as? java.util.Optional<Array<String>>)
                    ?.map { it.toList() }
                    ?.orElse(emptyList()) ?: emptyList()
                if ((cmd.endsWith("/sleep") || cmd == "sleep") && args.contains("3")) {
                    orphanSleeps++
                }
            }
        }
        assertEquals("Expected 0 orphan sleep 3 processes", 0, orphanSleeps)
    }

    private class FakeProcess(
        private val exitCode: Int,
        stdout: String,
        stderr: String,
        private val hangDurationMs: Long = 0L
    ) : Process() {
        val destroyed = AtomicBoolean(false)
        private val outStream = ByteArrayInputStream(stdout.toByteArray())
        private val errStream = ByteArrayInputStream(stderr.toByteArray())
        private val inStream = ByteArrayOutputStream()

        override fun getOutputStream(): OutputStream = inStream
        override fun getInputStream(): InputStream = outStream
        override fun getErrorStream(): InputStream = errStream

        override fun waitFor(): Int {
            if (hangDurationMs > 0L) {
                val deadline = System.currentTimeMillis() + hangDurationMs
                while (!destroyed.get() && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(20L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            return exitCode
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = exitCode
        override fun destroy() {
            destroyed.set(true)
        }
        override fun destroyForcibly(): Process {
            destroyed.set(true)
            return this
        }
    }
}
