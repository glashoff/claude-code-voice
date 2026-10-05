package dev.voiceproto

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Compose state backed by SharedPreferences: reads the stored value once, writes through on every change.
 * Usage: `var leadInMs by settings.int("leadInMs", 600)`
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private class Persisted<T>(initial: T, private val write: (T) -> Unit) : ReadWriteProperty<Any?, T> {
        private val state = mutableStateOf(initial)
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            write(value)
        }
    }

    fun getRaw(key: String): String = prefs.getString(key, "") ?: ""
    fun putRaw(key: String, value: String) = prefs.edit().putString(key, value).apply()

    fun string(key: String, default: String): ReadWriteProperty<Any?, String> =
        Persisted(prefs.getString(key, default) ?: default) { prefs.edit().putString(key, it).apply() }

    fun bool(key: String, default: Boolean): ReadWriteProperty<Any?, Boolean> =
        Persisted(prefs.getBoolean(key, default)) { prefs.edit().putBoolean(key, it).apply() }

    fun int(key: String, default: Int): ReadWriteProperty<Any?, Int> =
        Persisted(prefs.getInt(key, default)) { prefs.edit().putInt(key, it).apply() }

    fun float(key: String, default: Float): ReadWriteProperty<Any?, Float> =
        Persisted(prefs.getFloat(key, default)) { prefs.edit().putFloat(key, it).apply() }

    inline fun <reified E : Enum<E>> enum(key: String, default: E): ReadWriteProperty<Any?, E> {
        val delegate = string(key, default.name)
        return object : ReadWriteProperty<Any?, E> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): E =
                enumValues<E>().firstOrNull { it.name == delegate.getValue(thisRef, property) } ?: default
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: E) =
                delegate.setValue(thisRef, property, value.name)
        }
    }
}
