package com.mockloc.root

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Shell 执行结果数据模型
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean
        get() = exitCode == 0
}

/**
 * 进程执行器接口，用于真实系统调用与单元测试解耦
 */
fun interface CommandExecutor {
    fun execute(command: List<String>, timeoutMs: Long): ShellResult
}

/**
 * 基于标准 Java/Android ProcessBuilder 的真实命令执行器
 */
open class DefaultCommandExecutor : CommandExecutor {
    override fun execute(command: List<String>, timeoutMs: Long): ShellResult {
        return try {
            val process = ProcessBuilder(command).start()
            val executorService = Executors.newFixedThreadPool(2)

            val stdoutFuture = executorService.submit<String> {
                BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            }
            val stderrFuture = executorService.submit<String> {
                BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            }

            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                executorService.shutdownNow()
                return ShellResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Command execution timed out after ${timeoutMs}ms"
                )
            }

            val stdout = try { stdoutFuture.get(500, TimeUnit.MILLISECONDS) } catch (_: Throwable) { "" }
            val stderr = try { stderrFuture.get(500, TimeUnit.MILLISECONDS) } catch (_: Throwable) { "" }
            executorService.shutdown()

            ShellResult(
                exitCode = process.exitValue(),
                stdout = stdout.trimEnd(),
                stderr = stderr.trimEnd()
            )
        } catch (e: Throwable) {
            ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "Command execution failed"
            )
        }
    }
}

/**
 * Root Shell 工具类，负责检测 Root 环境、执行提权指令及下发 AppOps 模拟定位权限配置。
 */
object ShellUtil {

    private val PACKAGE_NAME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/su/bin/su",
        "/magisk/.core/bin/su"
    )

    @Volatile
    internal var commandExecutor: CommandExecutor = DefaultCommandExecutor()

    /**
     * 用于单元测试注入自定义执行器
     */
    fun setCommandExecutorForTesting(executor: CommandExecutor?) {
        commandExecutor = executor ?: DefaultCommandExecutor()
    }

    /**
     * 校验包名并生成授权 AppOps 模拟定位的指令字符串。
     * 防止 shell 命令注入。
     */
    @JvmStatic
    fun buildAppOpsCommand(packageName: String): String {
        val trimmed = packageName.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("Package name cannot be empty")
        }
        if (!PACKAGE_NAME_PATTERN.matcher(trimmed).matches()) {
            throw IllegalArgumentException("Invalid package name format: '$packageName'")
        }
        return "appops set $trimmed android:mock_location allow"
    }

    /**
     * 执行 su 提权指令并返回退出码及输出。
     */
    @JvmStatic
    @JvmOverloads
    fun executeSuCommand(command: String, timeoutMs: Long = 5000L): ShellResult {
        return commandExecutor.execute(listOf("su", "-c", command), timeoutMs)
    }

    /**
     * 检查当前环境是否存在可用 su 并成功获取 root 权限。
     */
    @JvmStatic
    fun checkSuAvailable(): Boolean {
        return try {
            val result = executeSuCommand("id", 3000L)
            result.isSuccess && (result.stdout.contains("uid=0") || result.exitCode == 0)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 检查系统已知路径下是否存在 su 二进制可执行文件。
     */
    @JvmStatic
    fun isSuBinaryPresent(): Boolean {
        for (path in SU_PATHS) {
            try {
                val file = File(path)
                if (file.exists()) {
                    return true
                }
            } catch (_: Throwable) {
                // 安全权限拦截忽略
            }
        }
        return false
    }

    /**
     * 执行 AppOps 授予指定应用 mock_location 权限。
     */
    @JvmStatic
    fun grantMockLocationPermission(packageName: String): Boolean {
        return try {
            val command = buildAppOpsCommand(packageName)
            val result = executeSuCommand(command)
            result.isSuccess
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * PROJECT.md 接口契约别名
     */
    @JvmStatic
    fun grantMockPermissionWithSu(packageName: String): Boolean {
        return grantMockLocationPermission(packageName)
    }
}
