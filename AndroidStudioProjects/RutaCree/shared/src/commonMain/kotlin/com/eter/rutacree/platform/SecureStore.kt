package com.eter.rutacree.platform

import androidx.compose.runtime.Composable

/** Encrypted key-value storage for credentials (Android Keystore / iOS Keychain). */
interface SecureStore {
    fun read(key: String): String?

    /** Null deletes the entry. */
    fun write(key: String, value: String?)
}

@Composable
expect fun rememberSecureStore(): SecureStore
