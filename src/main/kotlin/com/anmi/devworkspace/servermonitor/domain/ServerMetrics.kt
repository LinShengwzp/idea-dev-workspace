package com.anmi.devworkspace.servermonitor.domain

import kotlinx.serialization.Serializable

@Serializable
data class ServerMetrics(
    val timestamp: Long = System.currentTimeMillis(),
    val cpuUsagePercent: Double? = null,
    val memoryTotalBytes: Long? = null,
    val memoryUsedBytes: Long? = null,
    val memoryAvailableBytes: Long? = null,
    val diskTotalBytes: Long? = null,
    val diskUsedBytes: Long? = null,
    val diskAvailableBytes: Long? = null,
    val loadAverage1m: Double? = null,
    val loadAverage5m: Double? = null,
    val loadAverage15m: Double? = null,
    val error: String? = null,
) {
    val memoryUsagePercent: Double?
        get() = if (memoryTotalBytes != null && memoryTotalBytes!! > 0) {
            (memoryUsedBytes!! * 100.0 / memoryTotalBytes!!)
        } else null

    val diskUsagePercent: Double?
        get() = if (diskTotalBytes != null && diskTotalBytes!! > 0) {
            (diskUsedBytes!! * 100.0 / diskTotalBytes!!)
        } else null

    val isHealthy: Boolean
        get() = error == null
}
