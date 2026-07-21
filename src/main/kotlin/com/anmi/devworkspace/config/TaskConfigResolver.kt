package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.ResolvedTask
import com.anmi.devworkspace.domain.TaskScope

object TaskConfigResolver {
    fun resolve(
        global: List<DevTask>,
        shared: List<DevTask>,
        privateTasks: List<DevTask>,
    ): List<ResolvedTask> {
        val byId = linkedMapOf<String, MutableList<DevTask>>()
        global.forEach { byId.getOrPut(it.id) { mutableListOf() } += it }
        shared.forEach { byId.getOrPut(it.id) { mutableListOf() } += it }
        privateTasks.forEach { byId.getOrPut(it.id) { mutableListOf() } += it }

        return byId.values.map { chain ->
            val ordered = chain.sortedByDescending { it.scope.priority }
            ResolvedTask(ordered.first(), ordered.drop(1))
        }.sortedBy { it.effective.id }
    }

    private val TaskScope.priority: Int
        get() = when (this) {
            TaskScope.GLOBAL -> 0
            TaskScope.PROJECT_SHARED -> 1
            TaskScope.PROJECT_PRIVATE -> 2
        }
}
