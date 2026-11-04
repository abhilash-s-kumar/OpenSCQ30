package com.oppzippy.openscq30.features.fastpair

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/**
 * Trampoline opened from the widget when the Fast Pair Message Stream is busy. Opens the closest
 * reachable settings screen and tells the user where the Fast Pair toggle is, then finishes.
 */
class FastPairSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val candidates = listOf(
            // Google settings inside Play Services (Settings > Google)
            Intent().setClassName("com.google.android.gms", "com.google.android.gms.app.settings.GoogleSettingsLink"),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
                break
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }

        Toast.makeText(
            applicationContext,
            "For exact battery %: Google > All services > Devices > Saved devices, remove these earbuds. " +
                "Then forget and re-pair them in Bluetooth settings, and skip the Fast Pair popup.",
            Toast.LENGTH_LONG,
        ).show()
        finish()
    }
}
