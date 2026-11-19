package com.mockloc.e2e.infra

import java.io.File

/**
 * 工程与模块路径自动定位工具。
 * 兼容从工程根目录（例如 `/root/模拟定位`）、`app/` 模块目录或更深层子路径向上回溯查找。
 */
object WorkspacePathResolver {

    private fun isProjectRoot(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val hasSettings = File(dir, "settings.gradle.kts").isFile || File(dir, "settings.gradle").isFile
        val hasManifest = File(dir, "app/src/main/AndroidManifest.xml").isFile
        return hasSettings && hasManifest
    }

    /**
     * 从 [startDir] 向上回溯寻找工程根目录；若未找到则返回 null。
     */
    fun tryResolveProjectRoot(
        startDir: File = File(System.getProperty("user.dir") ?: ".")
    ): File? {
        var current: File? = runCatching { startDir.canonicalFile }.getOrElse { startDir.absoluteFile }
        while (current != null) {
            if (isProjectRoot(current)) {
                return current
            }
            current = current.parentFile
        }
        return null
    }

    /**
     * 从 [startDir] 向上回溯寻找工程根目录；若未找到则抛出 [IllegalStateException]。
     */
    fun resolveProjectRoot(
        startDir: File = File(System.getProperty("user.dir") ?: ".")
    ): File {
        return tryResolveProjectRoot(startDir)
            ?: throw IllegalStateException(
                "Unable to locate project root (missing settings.gradle.kts and app/src/main/AndroidManifest.xml) " +
                    "starting from: ${startDir.absolutePath}"
            )
    }

    fun resolveAppDir(
        startDir: File = File(System.getProperty("user.dir") ?: ".")
    ): File {
        val root = resolveProjectRoot(startDir)
        val appDir = File(root, "app")
        if (!appDir.isDirectory) {
            throw IllegalStateException("App module directory not found at: ${appDir.absolutePath}")
        }
        return appDir
    }

    fun manifestFile(startDir: File = File(System.getProperty("user.dir") ?: ".")): File =
        File(resolveAppDir(startDir), "src/main/AndroidManifest.xml")

    fun resDir(startDir: File = File(System.getProperty("user.dir") ?: ".")): File =
        File(resolveAppDir(startDir), "src/main/res")

    fun assetsDir(startDir: File = File(System.getProperty("user.dir") ?: ".")): File =
        File(resolveAppDir(startDir), "src/main/assets")

    fun xposedInitFile(startDir: File = File(System.getProperty("user.dir") ?: ".")): File =
        File(assetsDir(startDir), "xposed_init")

    fun xposedScopeArraysFile(startDir: File = File(System.getProperty("user.dir") ?: ".")): File =
        File(resDir(startDir), "values/arrays.xml")

    fun appBuildGradleFile(startDir: File = File(System.getProperty("user.dir") ?: ".")): File =
        File(resolveAppDir(startDir), "build.gradle.kts")

    fun mainJavaDir(startDir: File = File(System.getProperty("user.dir") ?: ".")): File =
        File(resolveAppDir(startDir), "src/main/java/com/mockloc")
}
