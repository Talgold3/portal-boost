package dev.portalboost

import android.content.Context
import android.content.SharedPreferences

/** Saved choices shared by the UI and the service. */
object Settings {
    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    /** The fixed Wi-Fi Direct passphrase, created on first use so the Portal only has to join once. */
    fun passphrase(c: Context): String {
        val p = prefs(c)
        return p.getString("p2pPass", null) ?: (1..10)
            .map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
            .also { p.edit().putString("p2pPass", it).apply() }
    }
}
