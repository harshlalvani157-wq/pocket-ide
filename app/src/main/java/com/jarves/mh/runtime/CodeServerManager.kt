package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Manages the lifecycle of the code-server (VS Code web editor) process
 * inside the private Ubuntu PRoot runtime.
 */
class CodeServerManager(
    private val context: Context,
    private val installer: RuntimeInstaller,
) {
    @Volatile private var process: Process? = null
    @Volatile var runningProjectId: String? = null; private set
    @Volatile var runningProjectSlug: String? = null; private set
    @Volatile var port: Int = DEFAULT_PORT; private set

    val isRunning: Boolean get() = process?.isAlive == true
    val url: String get() = "http://127.0.0.1:$port"

    private fun resolveCodeServerExecutable(rootfs: File): String {
        return when {
            File(rootfs, "usr/local/bin/code-server").canExecute() -> "/usr/local/bin/code-server"
            File(rootfs, "usr/bin/code-server").canExecute() -> "/usr/bin/code-server"
            File(rootfs, "root/.local/bin/code-server").canExecute() -> "/root/.local/bin/code-server"
            else -> RuntimeInstaller.CODE_SERVER_GUEST_PATH
        }
    }

    /**
     * Starts the code-server instance for the given project workspace.
     * Binds to loopback interface on the specified port without authentication.
     */
    suspend fun start(
        projectId: String,
        projectSlug: String,
        projectRootPath: String = "",
        targetPort: Int = DEFAULT_PORT,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!installer.isInstalled()) {
            return@withContext Result.failure(IllegalStateException("Linux runtime is not installed"))
        }
        if (!installer.isCodeServerInstalled()) {
            return@withContext Result.failure(IllegalStateException("Code Server is not installed yet"))
        }

        if (isRunning && runningProjectId == projectId && runningProjectSlug == projectSlug && isServerResponding(port)) {
            return@withContext Result.success(url)
        }

        val previous = process
        stop()
        if (previous != null && previous.isAlive) {
            previous.waitFor(1500, TimeUnit.MILLISECONDS)
            if (previous.isAlive) previous.destroyForcibly()
        }

        val runtime = runCatching { installer.installedRuntime() }.getOrElse {
            return@withContext Result.failure(it)
        }

        // The Files tab stores user code under the immutable project ID. Never
        // use the display slug here: slugs can collide with legacy workspaces
        // and would make Code Server and Files point at different directories.
        val projectBase = File(context.filesDir, "workspaces/$projectId").canonicalFile.apply { mkdirs() }
        val workspace = if (projectRootPath.isBlank()) {
            projectBase
        } else {
            File(projectBase, projectRootPath).canonicalFile.also {
                check(it.toPath().startsWith(projectBase.toPath())) { "Invalid project root path" }
                it.mkdirs()
            }
        }
        val guestWorkspacePath = "/workspace/$projectSlug"
        val userDataDir = "/root/.local/share/code-server/pocket-ide/$projectId"
        File(runtime.rootfs, userDataDir.removePrefix("/")).mkdirs()
        val executable = resolveCodeServerExecutable(runtime.rootfs)
        // Never reuse one global port when changing projects. If a previous
        // code-server/node child survived process teardown, reusing 8443 would
        // make the new project attach to that old server and old workspace.
        port = allocatePort(targetPort)
        runningProjectId = projectId
        runningProjectSlug = projectSlug

        val guestCommand = listOf(
            executable,
            "--bind-addr", "127.0.0.1:$port",
            "--auth", "none",
            "--disable-telemetry",
            "--disable-update-check",
            "--user-data-dir", userDataDir,
            guestWorkspacePath,
        )

        val environment = mapOf(
            "SHELL" to "/bin/bash",
            "HOME" to "/root",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
        )

        val outputFile = File(context.cacheDir, "code-server-${System.nanoTime()}.log")

        try {
            val spawned = installer.process(
                proot = runtime.proot,
                rootfs = runtime.rootfs,
                workspace = workspace,
                environment = environment,
                guestCommand = guestCommand,
                guestWorkspacePath = guestWorkspacePath,
                outputFile = outputFile,
            )
            process = spawned

            // Check if server binds and starts accepting connections
            var healthy = false
            for (attempt in 1..25) {
                if (!spawned.isAlive) {
                    val log = outputFile.takeIf { it.isFile }?.readText()?.takeLast(500).orEmpty()
                    return@withContext Result.failure(
                        IllegalStateException("Code Server stopped unexpectedly: ${log.ifBlank { "Exit code unknown" }}")
                    )
                }
                if (isServerResponding(port)) {
                    healthy = true
                    break
                }
                delay(600)
            }

            // Return URL even if loopback ping was delayed; WebView will load it
            Result.success(url)
        } catch (e: Exception) {
            Log.e("CodeServerManager", "Failed to start code-server", e)
            stop()
            Result.failure(e)
        }
    }

    /**
     * Gracefully terminates the running code-server process.
     */
    fun stop() {
        process?.destroy()
        process = null
        runningProjectId = null
        runningProjectSlug = null
    }

    /**
     * Checks if the HTTP server is currently responding on the loopback port.
     */
    fun isServerResponding(checkPort: Int = port): Boolean {
        return runCatching {
            val connection = URL("http://127.0.0.1:$checkPort").openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.requestMethod = "GET"
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode in 200..499
        }.getOrDefault(false)
    }

    private fun allocatePort(preferredPort: Int): Int = synchronized(this) {
        var candidate = nextPort.coerceIn(MIN_DYNAMIC_PORT, MAX_DYNAMIC_PORT)
        repeat(MAX_DYNAMIC_PORT - MIN_DYNAMIC_PORT + 1) {
            if (!isServerResponding(candidate)) {
                nextPort = if (candidate >= MAX_DYNAMIC_PORT) MIN_DYNAMIC_PORT else candidate + 1
                return candidate
            }
            candidate = if (candidate >= MAX_DYNAMIC_PORT) MIN_DYNAMIC_PORT else candidate + 1
        }
        // The preferred port is only a last-resort fallback if the bounded
        // local range is exhausted; startup health checking will report a
        // real bind failure instead of silently attaching to another project.
        return preferredPort
    }

    companion object {
        const val DEFAULT_PORT = 8443
        private const val MIN_DYNAMIC_PORT = DEFAULT_PORT
        private const val MAX_DYNAMIC_PORT = 10443
        @Volatile private var nextPort: Int = MIN_DYNAMIC_PORT
    }
}
