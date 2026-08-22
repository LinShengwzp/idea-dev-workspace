package com.anmi.devworkspace.servermonitor.ssh

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.ssh.SshBackendLibrary
import com.intellij.ssh.SshConnectionService
import com.intellij.ssh.SessionConfig
import com.intellij.ssh.config.SshConnectionConfigService
import com.intellij.ssh.interaction.SshPasswordProvider
import com.intellij.util.EventDispatcher
import com.intellij.ssh.ForceDisconnectListener
import com.intellij.ssh.config.AuthMethods
import com.intellij.ssh.config.AuthMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.nio.file.Paths

class SshExecutor(
    private val serverConfig: com.anmi.devworkspace.servermonitor.domain.ServerConfig,
    private val logger: Logger = Logger.getInstance(SshExecutor::class.java),
) {

    private val connectionService = SshConnectionService.instance
    private val configService = SshConnectionConfigService.getInstance()

    private fun buildSessionConfig(): SessionConfig {
        val connectionConfig = buildConnectionConfig()
        val passwordProvider = object : SshPasswordProvider {
            override fun getKeyPassphrase(
                keyPath: Path,
                progressIndicator: ProgressIndicator?
            ): String? = if (serverConfig.authType == com.anmi.devworkspace.servermonitor.domain.AuthType.KEY_FILE) {
                serverConfig.keyPassphrase.takeIf { it.isNotBlank() }
            } else null

            override fun getUnixPassword(
                progressIndicator: ProgressIndicator?
            ): String? = if (serverConfig.authType == com.anmi.devworkspace.servermonitor.domain.AuthType.PASSWORD) {
                serverConfig.password.takeIf { it.isNotBlank() }
            } else null

            override fun getKeyboardInteractive(
                name: String,
                instruction: String,
                prompts: Array<String>,
                echo: BooleanArray,
                progressIndicator: ProgressIndicator?
            ): Array<String> = emptyArray()
        }
        return SessionConfig(
            serverConfig.id,
            connectionConfig,
            passwordProvider,
            EventDispatcher.create(ForceDisconnectListener::class.java),
            SshBackendLibrary.SSHJ,
            EmptyProgressIndicator(),
        )
    }

    private fun buildConnectionConfig(): com.intellij.ssh.config.SshConnectionConfig {
        val authMethods = when (serverConfig.authType) {
            com.anmi.devworkspace.servermonitor.domain.AuthType.PASSWORD ->
                AuthMethods.create(
                    AuthMethod.Password,
                    AuthMethod.KeyboardInteractive
                )
            com.anmi.devworkspace.servermonitor.domain.AuthType.KEY_FILE ->
                AuthMethods.create(
                    AuthMethod.Password,
                    AuthMethod.KeyboardInteractive,
                    AuthMethod.PublicKey(
                        listOf(java.nio.file.Paths.get(serverConfig.keyFilePath)),
                        AuthMethod.PublicKey.Agent.NO
                    )
                )
        }

        // Create base config with host, then use copy with named parameters
        val baseConfig = com.intellij.ssh.config.SshConnectionConfig(serverConfig.host)
        return baseConfig.copy(
            authMethods = authMethods,
            user = serverConfig.username,
            port = serverConfig.port
        )
    }

    suspend fun executeCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val sessionConfig = buildSessionConfig()
            val channel = connectionService.exec(
                sessionConfig,
                com.intellij.ssh.ExecChannelConfig(command)
            )

            val output = StringBuilder()
            val error = StringBuilder()
            val outputThread = Thread {
                try {
                    channel.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            output.append(line).append('\n')
                            line = reader.readLine()
                        }
                    }
                } catch (_: java.io.IOException) {
                    // stream closed remotely; ignore
                }
            }
            val errorThread = Thread {
                try {
                    channel.getErrorStream().bufferedReader(Charsets.UTF_8).use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            error.append(line).append('\n')
                            line = reader.readLine()
                        }
                    }
                } catch (_: java.io.IOException) {
                    // ignore
                }
            }
            outputThread.start()
            errorThread.start()
            // Wait for the exec channel to be closed by the remote side.
            while (!channel.isClosed) {
                Thread.sleep(50)
            }
            outputThread.join(2000)
            errorThread.join(2000)

            val exitCode = channel.exitStatus
            channel.close()

            if (exitCode == 0) {
                Result.success(output.toString().trim())
            } else {
                Result.failure(
                    Exception(
                        "Command failed with exit code $exitCode: ${error.toString().trim()}"
                    )
                )
            }
        } catch (e: Exception) {
            logger.warn("SSH command failed for ${serverConfig.name}: $command", e)
            Result.failure(e)
        }
    }

    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val sessionConfig = buildSessionConfig()
            connectionService.checkCanAuthenticate(sessionConfig, true)
            true
        } catch (e: Exception) {
            logger.warn("SSH connection test failed for ${serverConfig.name}", e)
            false
        }
    }
}
