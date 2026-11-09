package com.jarvis.assistant.emergency

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object EmergencySettings {
    data class Config(val phone:String="",val enabled:Boolean=false)
    private fun prefs(context:Context) = EncryptedSharedPreferences.create(context.applicationContext,"jarvis_emergency",
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    suspend fun load(context:Context):Config = withContext(Dispatchers.IO) {
        val p=prefs(context);Config(p.getString("contact","").orEmpty(),p.getBoolean("enabled",false))
    }
    suspend fun save(context:Context,config:Config) = withContext(Dispatchers.IO) {
        check(prefs(context).edit().putString("contact",config.phone).putBoolean("enabled",config.enabled).commit())
    }
}
