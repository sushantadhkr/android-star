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
     * Maps a permission to its AppOps operation name.
     *
     * AppOps names operations after the permission with the prefix stripped — `READ_SMS` for
     * `android.permission.READ_SMS`, `SEND_SMS` for `android.permission.SEND_SMS`. There is no
     * `WRITE_SMS` operation, and an unknown operation name resolves to MODE_ERROR rather than
     * raising, so a wrong name here makes the permission look permanently denied no matter what the
     * user answers. That failure is invisible until someone tries to use the capability, so the
     * mapping is written out in full instead of derived from the permission string.
     */
    private fun appOpName(permission: String): String? = when (permission) {
        "android.permission.READ_SMS" -> "READ_SMS"
        "android.permission.RECEIVE_SMS" -> "RECEIVE_SMS"
        "android.permission.SEND_SMS" -> "SEND_SMS"
        "android.permission.RECEIVE_WAP_PUSH" -> "RECEIVE_WAP_PUSH"
        "android.permission.RECEIVE_MMS" -> "RECEIVE_MMS"
        "android.permission.READ_CELL_BROADCASTS" -> "READ_CELL_BROADCASTS"
        "android.permission.READ_CALL_LOG" -> "READ_CALL_LOG"
        "android.permission.WRITE_CALL_LOG" -> "WRITE_CALL_LOG"
        "android.permission.PROCESS_OUTGOING_CALLS" -> "PROCESS_OUTGOING_CALLS"
        else -> null
    }

    fun isAppOpsManaged(permission: String): Boolean = appOpName(permission) != null

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
     * `checkSelfPermission` reports these as denied even once granted, so the answer has to come
     * from AppOps.
     */
    fun isGranted(context: Context, permission: String): Boolean {
        val opName = appOpName(permission) ?: return true
        return mode(context, opName) == AppOpsManager.MODE_ALLOWED
    }

    /**
     * The raw AppOps mode behind a permission, for explaining a refusal.
     *
     * Returned so the platform can tell the user *why* something is blocked — not granted, denied
     * outright, or no such operation — instead of only ever saying "denied".
     */
    fun modeFor(context: Context, permission: String): String {
        val opName = appOpName(permission) ?: return "not_app_ops_managed"
        return when (mode(context, opName)) {
            AppOpsManager.MODE_ALLOWED -> "allowed"
            AppOpsManager.MODE_IGNORED -> "denied"
            AppOpsManager.MODE_ERRORED -> "failed"
            AppOpsManager.MODE_DEFAULT -> "default"
            else -> "other"
        }
    }

    /**
     * The raw AppOps mode for an operation.
     *
     * The string overload is deprecated from Android 13 but remains the only one in the public SDK —
     * `AppOpsManager.strToOp` is a hidden API — and it still resolves correctly for these
     * operations. A MODE_ERROR here means the operation name is unknown to the platform, which is
     * exactly the mistake this function's callers need to be able to see.
     */
    @Suppress("DEPRECATION")
    private fun mode(context: Context, opName: String): Int = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return AppOpsManager.MODE_ERRORED

        appOps.unsafeCheckOpNoThrow(opName, Process.myUid(), context.packageName)
    } catch (_: Exception) {
        AppOpsManager.MODE_ERRORED
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