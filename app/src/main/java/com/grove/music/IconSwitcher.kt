package com.grove.music

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Switches the launcher icon to the one matching the chosen accent colour. */
object IconSwitcher {
    fun apply(ctx: Context, accent: Int) {
        val idx = Accents.indexOf(accent).takeIf { it >= 0 } ?: Accents.indexOf(0xFF0050EF.toInt())
        val pm = ctx.packageManager
        Accents.indices.forEach { i ->
            val want = if (i == idx) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val c = ComponentName(ctx, "com.grove.music.Icon$i")
            if (pm.getComponentEnabledSetting(c) != want) pm.setComponentEnabledSetting(c, want, PackageManager.DONT_KILL_APP)
        }
    }
}
