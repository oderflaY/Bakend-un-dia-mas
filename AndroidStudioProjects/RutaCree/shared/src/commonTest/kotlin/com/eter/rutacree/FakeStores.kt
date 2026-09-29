package com.eter.rutacree

import com.eter.rutacree.platform.SecureStore
import com.eter.rutacree.settings.KeyValueStore

/** In-memory stand-ins for SharedPreferences / NSUserDefaults and the Keystore / Keychain. */
class FakeKeyValueStore(initial: Map<String, String> = emptyMap()) : KeyValueStore, SecureStore {
    val values = initial.toMutableMap()
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
    override fun read(key: String): String? = values[key]
    override fun write(key: String, value: String?) = put(key, value)
}
