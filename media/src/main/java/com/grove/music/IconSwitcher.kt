package com.grove.music

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Switches the launcher icon to the one matching the chosen accent colour. */
object IconSwitcher {
    val styles = listOf("colour", "neon", "mono", "glass")
    fun apply(ctx: Context, accent: Int) {
        val style = Prefs(ctx).iconStyle
        val idx = if (style != "colour") -1 else Accents.indexOf(accent).takeIf { it >= 0 } ?: Accents.indexOf(0xFF0050EF.toInt())
        val pm = ctx.packageManager
        listOf("neon", "mono", "glass").forEach { st ->
            val want = if (st == style) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val c = ComponentName(ctx, "com.grove.music.IconStyle" + st.replaceFirstChar { it.uppercase() })
            if (pm.getComponentEnabledSetting(c) != want) pm.setComponentEnabledSetting(c, want, PackageManager.DONT_KILL_APP)
        }
        Accents.indices.forEach { i ->
            val want = if (i == idx) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val c = ComponentName(ctx, "com.grove.music.Icon$i")
            if (pm.getComponentEnabledSetting(c) != want) pm.setComponentEnabledSetting(c, want, PackageManager.DONT_KILL_APP)
        }
    }
}
