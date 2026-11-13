package com.example.pocketmuseprototype.conversion

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

internal val HQQ_ITERATION_CHOICES = DEFAULT_HQQ_ITERATIONS..20 step 5

private val Context.conversionPreferences by preferencesDataStore("conversion_settings")
private val HqqIterationsKey = intPreferencesKey("hqq_iterations")

internal class ConversionSettings(context: Context) {
    private val preferences = context.applicationContext.conversionPreferences

    val hqqIterations = preferences.data.map { values ->
        values[HqqIterationsKey]?.takeIf { it in HQQ_ITERATION_CHOICES } ?: DEFAULT_HQQ_ITERATIONS
    }

    suspend fun setHqqIterations(iterations: Int) {
        require(iterations in HQQ_ITERATION_CHOICES)
        preferences.edit { it[HqqIterationsKey] = iterations }
    }
}
