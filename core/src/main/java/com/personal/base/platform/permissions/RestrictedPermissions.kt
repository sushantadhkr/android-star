package com.personal.base.platform.permissions

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * The AppOps settings action.
 *
 * `Settings.ACTION_MANAGE_APP_OPS_SETTINGS` is not part of the public SDK, but the action string
 * is stable and documented, and it is the screen where SMS and Call history toggles live.
 */
private const val ACTION_MANAGE_APP_OPS = "android.settings.MANAGE_APP_OPS_SETTINGS"

/**
 * Permissions the runtime dialog cannot grant.
 *
 * Since Android 10 the SMS and Call Log groups are *hard restricted*: calling
 * `requestPermissions()` with them returns denied immediately, without ever showing a dialog. They
 * are instead controlled through AppOps and granted from
 * **Settings → Apps → Special app access → SMS and Call history**.
 *
 * Treating them like ordinary runtime permissions makes the request look like a silent denial, so
 * the platform must recognise them and route the user to the settings screen instead.
 */
object RestrictedPermissions {

    /**
     * Permissions that are granted through AppOps rather than the runtime dialog.
     *
     * On Android 9 and below these behave as ordinary runtime permissions, so [requiresSpecialAccess]
     * returns false there and the normal dialog remains correct.
     */
    private val APP_OPS_PERMISSIONS = setOf(
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.SEND_SMS",
        "android.permission.RECEIVE_WAP_PUSH",
        "android.permission.RECEIVE_MMS",
        "android.permission.READ_CELL_BROADCASTS",
        "android.permission.READ_CALL_LOG",
        "android.permission.WRITE_CALL_LOG",
        "android.permission.PROCESS_OUTGOING_CALLS"
    )

    /** Maps a permission to its AppOps name, which is not always the same string. */
    private fun appOpName(permission: String): String = when (permission) {
        "android.permission.SEND_SMS" -> "WRITE_SMS"
        else -> permission.removePrefix("android.permission.")
    }

    fun isAppOpsManaged(permission: String): Boolean =
        permission in APP_OPS_PERMISSIONS

    /**
     * Whether this permission needs the special-access settings screen on the current OS version.
     */
    fun requiresSpecialAccess(permission: String): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isAppOpsManaged(permission)

    /** Whether any permission in the list needs the special-access screen. */
    fun requiresSpecialAccess(permissions: List<String>): Boolean =
        permissions.any { requiresSpecialAccess(it) }

    /**
     * Checks an AppOps-managed permission.
     *
     * `checkSelfPermission` reports these as denied even when the user has granted them, so status
     * must come from AppOps.
     */
    fun isGranted(context: Context, permission: String): Boolean {
        if (!isAppOpsManaged(permission)) return true
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val opName = appOpName(permission)
            // MODE_DEFAULT follows whatever the user chose in settings.
            val mode = appOps.unsafeCheckOpNoThrow(
                opName,
                Process.myUid(),
                context.packageName
            )
            when (mode) {
                AppOpsManager.MODE_ALLOWED -> true
                // MODE_DEFAULT means "inherit"; treat as allowed and let the call itself decide,
                // which matches how the platform treats unspecialised capabilities.
                AppOpsManager.MODE_DEFAULT -> appOps.checkOpNoThrow(
                    opName,
                    Process.myUid(),
                    context.packageName
                ) == AppOpsManager.MODE_ALLOWED
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * An intent to the screen where these permissions are actually granted.
     *
     * Falls back to the app's details page on devices that do not expose the AppOps screen.
     */
    fun settingsIntent(context: Context): Intent {
        val packageUri = Uri.parse("package:${context.packageName}")

        val appOps = Intent(ACTION_MANAGE_APP_OPS, packageUri)
        if (appOps.resolveActivity(context.packageManager) != null) return appOps

        val appOpsPlain = Intent(ACTION_MANAGE_APP_OPS)
        if (appOpsPlain.resolveActivity(context.packageManager) != null) return appOpsPlain

        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
    }

    /** Human-readable label for the settings destination, for use in UI. */
    fun settingsLabel(): String =
        "Settings → Apps → ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "SMS and Call history" else "Permissions"}"
}