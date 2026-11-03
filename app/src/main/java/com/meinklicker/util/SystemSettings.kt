package com.meinklicker.util

import android.content.Context
import android.content.Intent
import android.provider.Settings

object SystemSettings {

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
