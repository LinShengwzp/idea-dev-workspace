package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.DevTask

object TaskConfigValidator {
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    fun validate(tasks: List<DevTask>): List<String> {
        val errors = mutableListOf<String>()
        val duplicates = tasks.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys

        tasks.forEach { task ->
            if (!idPattern.matches(task.id)) {
                errors += "Task id '${task.id}' must match ${idPattern.pattern}"
            }
            task.favoriteSlot?.let {
                if (it !in 1..9) errors += "Task '${task.id}' favoriteSlot must be between 1 and 9"
            }
            task.autoStart?.let {
                if (it.order < 0) errors += "Task '${task.id}' autoStart.order must be non-negative"
                if (it.delaySeconds < 0) errors += "Task '${task.id}' autoStart.delaySeconds must be non-negative"
            }
        }

        duplicates.forEach { errors += "Duplicate task id '$it' in the same configuration file" }
        return errors
    }
}
