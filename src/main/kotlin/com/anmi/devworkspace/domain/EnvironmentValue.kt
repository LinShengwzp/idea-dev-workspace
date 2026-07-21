package com.anmi.devworkspace.domain

sealed interface EnvironmentValue {
    data class Plain(val value: String) : EnvironmentValue
    data class SystemReference(val name: String) : EnvironmentValue
    data class SecretReference(val key: String) : EnvironmentValue
}
