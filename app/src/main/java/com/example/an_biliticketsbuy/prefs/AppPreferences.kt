package com.example.an_biliticketsbuy.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore(name = "app_settings")

/** App 配置持久化，使用 DataStore Preferences */
class AppPreferences(private val context: Context) {

    private object Keys {
        val targetTime = longPreferencesKey("target_time")
        val clickInterval = longPreferencesKey("click_interval")
        val maxRetry = longPreferencesKey("max_retry")
    }

    val targetTime: Flow<Long> = context.store.data.map { prefs ->
        prefs[Keys.targetTime] ?: 0L
    }

    val clickInterval: Flow<Long> = context.store.data.map { prefs ->
        prefs[Keys.clickInterval] ?: 100L
    }

    val maxRetry: Flow<Int> = context.store.data.map { prefs ->
        (prefs[Keys.maxRetry] ?: 50L).toInt()
    }

    suspend fun saveTargetTime(millis: Long) {
        context.store.edit { prefs -> prefs[Keys.targetTime] = millis }
    }

    suspend fun saveClickInterval(interval: Long) {
        context.store.edit { prefs -> prefs[Keys.clickInterval] = interval }
    }

    suspend fun saveMaxRetry(retry: Int) {
        context.store.edit { prefs -> prefs[Keys.maxRetry] = retry.toLong() }
    }
}
