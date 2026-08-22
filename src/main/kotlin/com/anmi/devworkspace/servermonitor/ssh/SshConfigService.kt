package com.anmi.devworkspace.servermonitor.ssh

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.anmi.devworkspace.servermonitor.domain.ServerConfig

@Service(Service.Level.APP)
class SshConfigService {

    fun getAllSshConfigs(): List<ServerConfig> {
        // Now returns empty list since we use manual config
        return emptyList()
    }

    fun getSshConfigById(id: String): ServerConfig? {
        return null
    }

    fun getSshConfigByName(name: String): ServerConfig? {
        return null
    }

    // Project-level SSH configs (if needed)
    fun getProjectSshConfigs(project: Project): List<ServerConfig> {
        return emptyList()
    }
}
