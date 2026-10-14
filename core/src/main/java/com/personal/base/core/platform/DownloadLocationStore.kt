package com.personal.base.core.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit

/**
 * Where downloads land.
 *
 * By default the platform writes through MediaStore into the phone's own Downloads folder, which
 * needs no permission at all on Android 10 and up. A user who wants them somewhere else — an SD
 * card, a synced folder, a shared drive — can point the platform at a folder of their choosing
 * through the system folder picker.
 *
 * The choice is a SAF tree URI, which needs no storage permission on any Android version and is the
 * only way that keeps working under scoped storage. The grant is persisted, so it survives a
 * restart; without persisting it the platform would lose access the moment the app was killed.
 */
class DownloadLocationStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The folder the user picked, or null when they are happy with the default. */
    val folder: Uri?
        get() = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)

    /** A human-readable name for the chosen folder, for showing in settings. */
    val folderName: String?
        get() = folder?.let { it.lastPathSegment?.substringAfterLast(':') }

    val isCustom: Boolean get() = folder != null

    /**
     * Remembers a folder the user picked and holds onto the grant.
     *
     * The persistable grant is the whole point: a one-shot grant is revoked when the process dies,
     * and every later download would fail with a permission error the user cannot see the cause of.
     */
    fun setFolder(uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit { putString(KEY_FOLDER, uri.toString()) }
        true
    } catch (_: SecurityException) {
        false
    }

    /** Goes back to the phone's own Downloads folder and gives the grant up. */
    fun clear(): Boolean {
        folder?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        prefs.edit { remove(KEY_FOLDER) }
        return true
    }

    /**
     * Whether the chosen folder is still writable.
     *
     * A persisted grant can be revoked — the folder is removed, the SD card is pulled, the user
     * revokes access — so this is checked before offering the choice rather than assumed.
     */
    fun canWriteToFolder(): Boolean = folder?.let { uri ->
        runCatching {
            android.provider.DocumentsContract.isTreeUri(uri) &&
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isWritePermission
                }
        }.getOrDefault(false)
    } ?: false

    private companion object {
        const val PREFS = "personal_base_download_location"
        const val KEY_FOLDER = "download_folder_uri"
    }
}