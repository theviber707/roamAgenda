/*
 * SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.roamagenda.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "roam_agenda_preferences")

/**
 * Manages persistent configuration for the Roam Agenda app.
 */
class AgendaPreferences(private val context: Context) {

    companion object {
        val KEY_ORG_DIRECTORY_PATH = stringPreferencesKey("org_directory_path")
        const val DEFAULT_PATH = "/sdcard/Org/roam"
    }

    /**
     * Flow emitting the currently configured Org-Roam directory path.
     */
    val orgDirectoryPathFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_ORG_DIRECTORY_PATH]?.takeIf { it.isNotBlank() } ?: DEFAULT_PATH
    }

    /**
     * Updates and persists the Org-Roam root directory path.
     */
    suspend fun setOrgDirectoryPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ORG_DIRECTORY_PATH] = path.trim()
        }
    }
}
