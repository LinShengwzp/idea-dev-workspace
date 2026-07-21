package com.anmi.devworkspace

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.DevWorkspaceBundle"

object DevWorkspaceBundle : DynamicBundle(BUNDLE) {
    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): String = getMessage(key, *params)
}
