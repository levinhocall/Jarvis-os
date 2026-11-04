package com.jarvis.assistant.ui.aura

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object AppIconSwitcher {
    val icons = linkedMapOf("Default" to "DefaultIcon", "Aura Red" to "AuraRedIcon", "Midnight" to "MidnightIcon", "Crimson" to "CrimsonIcon")
    fun select(context: Context, label: String) {
        val selected = icons.getValue(label)
        val manager = context.packageManager
        fun component(name: String) = ComponentName(context.packageName, "${context.packageName}.$name")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            manager.setComponentEnabledSettings(icons.values.map { name ->
                PackageManager.ComponentEnabledSetting(component(name), if (name == selected) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            })
        } else {
            manager.setComponentEnabledSetting(component(selected), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            icons.values.filter { it != selected }.forEach { manager.setComponentEnabledSetting(component(it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP) }
        }
    }
}
