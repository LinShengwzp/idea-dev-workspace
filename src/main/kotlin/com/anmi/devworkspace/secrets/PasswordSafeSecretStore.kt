package com.anmi.devworkspace.secrets

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface SecretStore {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String?)
}

class PasswordSafeSecretStore : SecretStore {
    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        PasswordSafe.instance.getPassword(attributes(key))
    }

    override suspend fun set(key: String, value: String?) = withContext(Dispatchers.IO) {
        PasswordSafe.instance.setPassword(attributes(key), value)
    }

    private fun attributes(key: String) =
        CredentialAttributes(generateServiceName("Dev Workspace Tasks", key))
}
