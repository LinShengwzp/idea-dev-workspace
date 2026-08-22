package com.anmi.devworkspace.servermonitor.ssh

import com.anmi.devworkspace.servermonitor.domain.ProcessInfo
import com.anmi.devworkspace.servermonitor.domain.ServerMetrics
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MetricsCollector(
    private val executor: SshExecutor,
    private val logger: Logger = Logger.getInstance(MetricsCollector::class.java),
) {

    suspend fun collectMetrics(): ServerMetrics = withContext(Dispatchers.IO) {
        var metrics = ServerMetrics()

        // Collect CPU usage
        executor.executeCommand("top -bn1 | grep 'Cpu(s)' | awk '{print \$2}' | sed 's/%us,//'").onSuccess { output ->
            metrics = metrics.copy(cpuUsagePercent = output.toDoubleOrNull())
        }.onFailure { e ->
            logger.warn("Failed to collect CPU metrics: ${e.message}")
        }

        // Collect memory info
        executor.executeCommand("free -b | awk '/^Mem:/ {print \$2 \" \" \$3 \" \" \$7}'").onSuccess { output ->
            val parts = output.split(" ")
            if (parts.size >= 3) {
                metrics = metrics.copy(
                    memoryTotalBytes = parts[0].toLongOrNull(),
                    memoryUsedBytes = parts[1].toLongOrNull(),
                    memoryAvailableBytes = parts[2].toLongOrNull(),
                )
            }
        }.onFailure { e ->
            logger.warn("Failed to collect memory metrics: ${e.message}")
        }

        // Collect disk info
        executor.executeCommand("df -B1 / | awk 'NR==2 {print \$2 \" \" \$3 \" \" \$4}'").onSuccess { output ->
            val parts = output.split(" ")
            if (parts.size >= 3) {
                metrics = metrics.copy(
                    diskTotalBytes = parts[0].toLongOrNull(),
                    diskUsedBytes = parts[1].toLongOrNull(),
                    diskAvailableBytes = parts[2].toLongOrNull(),
                )
            }
        }.onFailure { e ->
            logger.warn("Failed to collect disk metrics: ${e.message}")
        }

        // Collect load average
        executor.executeCommand("cat /proc/loadavg | awk '{print \$1 \" \" \$2 \" \" \$3}'").onSuccess { output ->
            val parts = output.split(" ")
            if (parts.size >= 3) {
                metrics = metrics.copy(
                    loadAverage1m = parts[0].toDoubleOrNull(),
                    loadAverage5m = parts[1].toDoubleOrNull(),
                    loadAverage15m = parts[2].toDoubleOrNull(),
                )
            }
        }.onFailure { e ->
            logger.warn("Failed to collect load average: ${e.message}")
        }

        metrics
    }

    suspend fun collectProcesses(): List<ProcessInfo> = withContext(Dispatchers.IO) {
        val result = executor.executeCommand(
            "ps aux --sort=-%cpu | head -n 11"
        )

        return@withContext result.fold(
            onSuccess = { output ->
                parseProcessOutput(output)
            },
            onFailure = { e ->
                logger.warn("Failed to collect process metrics: ${e.message}")
                emptyList()
            }
        )
    }

    private fun parseProcessOutput(output: String): List<ProcessInfo> {
        val lines = output.lines().drop(1) // Skip header
        val processes = mutableListOf<ProcessInfo>()

        for (line in lines) {
            val parts = line.trim().split("\\s+".toRegex()).toList()
            if (parts.size >= 11) {
                val pid = parts[1]
                val user = parts[0]
                val cpu = parts[2]
                val memory = parts[3]
                // Command is everything from column 10 onwards
                val command = parts.drop(10).joinToString(" ")
                processes.add(ProcessInfo(pid, user, cpu, memory, command))
            }
        }

        return processes.take(10) // Limit to top 10
    }
}
