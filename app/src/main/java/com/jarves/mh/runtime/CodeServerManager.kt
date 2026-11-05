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

data class RunningCodeServer(
    val projectId: String,
    val projectSlug: String,
    val port: Int,
    val url: String,
)

/** Keeps independent Code Server processes alive across project navigation. */
class CodeServerManager(
    private val context: Context,
    private val installer: RuntimeInstaller,
) {
    private data class ServerHandle(
        val projectId: String,
        val projectSlug: String,
        val port: Int,
        val process: Process,
    )

    private val lock = Any()
    private val servers = LinkedHashMap<String, ServerHandle>()

    @Volatile var runningProjectId: String? = null; private set
    @Volatile var runningProjectSlug: String? = null; private set
    @Volatile var port: Int = DEFAULT_PORT; private set

    val isRunning: Boolean get() = synchronized(lock) { servers.values.any { it.process.isAlive } }
    val url: String get() = "http://127.0.0.1:$port"

    fun runningServers(): List<RunningCodeServer> = synchronized(lock) {
        servers.values.filter { it.process.isAlive }.map { handle ->
            RunningCodeServer(handle.projectId, handle.projectSlug, handle.port, "http://127.0.0.1:${handle.port}")
        }
    }

    private fun resolveCodeServerExecutable(rootfs: File): String = when {
        File(rootfs, "usr/local/bin/code-server").canExecute() -> "/usr/local/bin/code-server"
        File(rootfs, "usr/bin/code-server").canExecute() -> "/usr/bin/code-server"
        File(rootfs, "root/.local/bin/code-server").canExecute() -> "/root/.local/bin/code-server"
        else -> RuntimeInstaller.CODE_SERVER_GUEST_PATH
    }

    suspend fun start(
        projectId: String,
        projectSlug: String,
        projectRootPath: String = "",
        targetPort: Int = DEFAULT_PORT,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!installer.isInstalled()) return@withContext Result.failure(IllegalStateException("Linux runtime is not installed"))
        if (!installer.isCodeServerInstalled()) return@withContext Result.failure(IllegalStateException("Code Server is not installed yet"))

        synchronized(lock) {
            val existing = servers[projectId]
            if (existing != null && existing.process.isAlive && isServerResponding(existing.port)) {
                runningProjectId = projectId
                runningProjectSlug = projectSlug
                port = existing.port
                return@withContext Result.success("http://127.0.0.1:${existing.port}")
            }
            servers.remove(projectId)
        }

        val runtime = runCatching { installer.installedRuntime() }.getOrElse { return@withContext Result.failure(it) }
        val projectBase = File(context.filesDir, "workspaces/$projectId").canonicalFile.apply { mkdirs() }
        val workspace = if (projectRootPath.isBlank()) projectBase else File(projectBase, projectRootPath).canonicalFile.also {
            check(it.toPath().startsWith(projectBase.toPath())) { "Invalid project root path" }
            it.mkdirs()
        }
        val guestWorkspacePath = "/workspace/$projectSlug"
        val userDataDir = "/root/.local/share/code-server/pocket-ide/$projectId"
        File(runtime.rootfs, userDataDir.removePrefix("/")).mkdirs()
        val selectedPort = allocatePort(targetPort)
        val executable = resolveCodeServerExecutable(runtime.rootfs)
        val guestCommand = listOf(
            executable, "--bind-addr", "127.0.0.1:$selectedPort", "--auth", "none",
            "--disable-telemetry", "--disable-update-check", "--user-data-dir", userDataDir,
            guestWorkspacePath,
        )
        val outputFile = File(context.cacheDir, "code-server-$projectId-${System.nanoTime()}.log")
        try {
            val spawned = installer.process(
                proot = runtime.proot, rootfs = runtime.rootfs, workspace = workspace,
                environment = mapOf("SHELL" to "/bin/bash", "HOME" to "/root", "TERM" to "xterm-256color", "LANG" to "C.UTF-8"),
                guestCommand = guestCommand, guestWorkspacePath = guestWorkspacePath, outputFile = outputFile,
            )
            synchronized(lock) {
                servers[projectId] = ServerHandle(projectId, projectSlug, selectedPort, spawned)
                runningProjectId = projectId
                runningProjectSlug = projectSlug
                port = selectedPort
            }
            repeat(25) {
                if (!spawned.isAlive) {
                    val log = outputFile.takeIf { it.isFile }?.readText()?.takeLast(500).orEmpty()
                    synchronized(lock) { servers.remove(projectId) }
                    return@withContext Result.failure(IllegalStateException("Code Server stopped unexpectedly: ${log.ifBlank { "Exit code unknown" }}"))
                }
                if (isServerResponding(selectedPort)) return@withContext Result.success("http://127.0.0.1:$selectedPort")
                delay(600)
            }
            Result.success("http://127.0.0.1:$selectedPort")
        } catch (error: Exception) {
            Log.e("CodeServerManager", "Failed to start code-server", error)
            stop(projectId)
            Result.failure(error)
        }
    }

    fun stop(projectId: String? = null) {
        val handles = synchronized(lock) {
            if (projectId == null) servers.values.toList() else listOfNotNull(servers[projectId])
        }
        handles.forEach { handle ->
            runCatching {
                if (handle.process.isAlive) {
                    handle.process.destroy()
                    if (!handle.process.waitFor(1200, TimeUnit.MILLISECONDS) && handle.process.isAlive) handle.process.destroyForcibly()
                }
            }
            synchronized(lock) { servers.remove(handle.projectId) }
        }
        synchronized(lock) {
            val current = servers.values.firstOrNull { it.process.isAlive }
            runningProjectId = current?.projectId
            runningProjectSlug = current?.projectSlug
            port = current?.port ?: DEFAULT_PORT
        }
    }

    fun isProjectRunning(projectId: String): Boolean = synchronized(lock) { servers[projectId]?.process?.isAlive == true }

    fun isServerResponding(checkPort: Int = port): Boolean = runCatching {
        val connection = URL("http://127.0.0.1:$checkPort").openConnection() as HttpURLConnection
        connection.connectTimeout = 1000
        connection.readTimeout = 1000
        connection.requestMethod = "GET"
        val code = connection.responseCode
        connection.disconnect()
        code in 200..499
    }.getOrDefault(false)

    private fun allocatePort(preferredPort: Int): Int = synchronized(lock) {
        var candidate = nextPort.coerceIn(MIN_DYNAMIC_PORT, MAX_DYNAMIC_PORT)
        repeat(MAX_DYNAMIC_PORT - MIN_DYNAMIC_PORT + 1) {
            val occupied = servers.values.any { it.port == candidate && it.process.isAlive }
            if (!occupied && !isServerResponding(candidate)) {
                nextPort = if (candidate >= MAX_DYNAMIC_PORT) MIN_DYNAMIC_PORT else candidate + 1
                return candidate
            }
            candidate = if (candidate >= MAX_DYNAMIC_PORT) MIN_DYNAMIC_PORT else candidate + 1
        }
        preferredPort
    }

    companion object {
        const val DEFAULT_PORT = 8443
        private const val MIN_DYNAMIC_PORT = DEFAULT_PORT
        private const val MAX_DYNAMIC_PORT = 10443
        @Volatile private var nextPort: Int = MIN_DYNAMIC_PORT
    }
}
