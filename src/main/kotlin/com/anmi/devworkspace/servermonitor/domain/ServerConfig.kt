package com.anmi.devworkspace.servermonitor.domain

import kotlinx.serialization.Serializable

@Serializable
data class ServerConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: AuthType = AuthType.PASSWORD,
    val password: String = "",
    val keyFilePath: String = "",
    val keyPassphrase: String = "",
    val enabled: Boolean = true,
    val pollingIntervalSeconds: Int = 30,
)

enum class AuthType {
    PASSWORD,
    KEY_FILE,
}
