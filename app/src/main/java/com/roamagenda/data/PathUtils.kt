/*
 * SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.roamagenda.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract

object PathUtils {

    /**
     * Resolves an Android document tree Uri (from OpenDocumentTree) to an absolute filesystem path.
     */
    fun resolveTreeUriToPath(context: Context, treeUri: Uri): String? {
        return try {
            val documentId = DocumentsContract.getTreeDocumentId(treeUri) ?: return null
            val parts = documentId.split(":", limit = 2)
            val storageId = parts[0]
            val subPath = if (parts.size > 1) parts[1].trimStart('/') else ""

            if (storageId.equals("primary", ignoreCase = true)) {
                val base = Environment.getExternalStorageDirectory().absolutePath
                if (subPath.isNotEmpty()) "$base/$subPath" else base
            } else {
                // Secondary SD card / USB storage volume
                val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                var resolvedBase: String? = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && storageManager != null) {
                    val volume = storageManager.storageVolumes.firstOrNull {
                        it.uuid.equals(storageId, ignoreCase = true)
                    }
                    resolvedBase = volume?.directory?.absolutePath
                }
                val base = resolvedBase ?: "/storage/$storageId"
                if (subPath.isNotEmpty()) "$base/$subPath" else base
            }
        } catch (e: Exception) {
            treeUri.path
        }
    }
}
