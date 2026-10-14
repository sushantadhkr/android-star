package com.personal.base.platform.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * The AppOps settings action.
 *
 * `Settings.ACTION_MANAGE_APP_OPS_SETTINGS` is not part of the public SDK, but the action string
 * is stable and documented, and it is the screen where SMS and Call history toggles live.
 */
private const val ACTION_MANAGE_APP_OPS = "android.settings.MANAGE_APP_OPS_SETTINGS"

/**
 * Permissions the runtime dialog cannot grant, and which the public API cannot verify either.
 *
 * Since Android 10 the SMS and Call Log groups are *hard restricted*. Calling `requestPermissions()`
 * with them returns denied immediately without ever showing a dialog; they are granted from
 * **Settings → Apps → Special app access → SMS and Call history**, and the decision is recorded as
 * a uid-level AppOps mode.
 *
 * That mode cannot be read from an app:
 *
 *  - `AppOpsManager.unsafeCheckOpNoThrow(String, …)` throws
 *    `IllegalArgumentException: Unknown operation string: READ_SMS` on Android 16, because these
 *    operations are not in the public table;
 *  - `AppOpsManager.strToOp` is hidden and absent, so there is no int fallback to reach;
 *  - `checkSelfPermission` reports these as denied even after they are granted.
 *
 * Measured on a real Android 16 device rather than assumed — the third point was the reason this
 * used to consult AppOps, and it is still true, but the first two mean AppOps can no longer answer
 * the question either.
 *
 * So the only honest answer is to **try the operation**. [com.personal.base.platform.permissions.PlatformPermissionManager]
 * takes a probe for exactly this: a provider that does the thing and says whether it worked.
 */
object RestrictedPermissions {

    /**
     * Permissions whose real grant state cannot be read, only demonstrated.
     */
    val UNVERIFIABLE = setOf(
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.SEND_SMS",
        "android.permission.READ_CALL_LOG",
        "android.permission.WRITE_CALL_LOG"
    )

    fun isAppOpsManaged(permission: String): Boolean = permission in UNVERIFIABLE

    /**
     * Whether this permission needs the special-access settings screen on the current OS version.
     */
    fun requiresSpecialAccess(permission: String): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isAppOpsManaged(permission)

    /** Whether any permission in the list needs the special-access screen. */
    fun requiresSpecialAccess(permissions: List<String>): Boolean =
        permissions.any { requiresSpecialAccess(it) }

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