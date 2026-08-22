package com.anmi.devworkspace.servermonitor.domain

import kotlinx.serialization.Serializable

@Serializable
data class ProcessInfo(
    val pid: String,
    val user: String,
    val cpu: String,
    val memory: String,
    val command: String,
)
