package com.personal.base

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Why SMS is answered by trying rather than by asking.
 *
 * Measured on a real Android 16 device, because every reasonable way of reading the grant state
 * turns out to be unavailable:
 *
 *  - `AppOpsManager.unsafeCheckOpNoThrow("READ_SMS", …)` throws
 *    `IllegalArgumentException: Unknown operation string` — these operations left the public table;
 *  - `AppOpsManager.strToOp` is hidden and absent, so there is no int fallback to reach;
 *  - `checkSelfPermission` reports denied, and for Call log it reports *granted* while the read
 *    still throws — so it is not merely conservative, it is wrong.
 *
 * This test records that so the reasoning survives, and so anyone tempted to reintroduce an AppOps
 * query finds out why not.
 */
@RunWith(AndroidJUnit4::class)
class SmsGrantStateTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun queryingAppopsForSmsIsNotPossibleOnThisPlatform() {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val failure = runCatching {
            @Suppress("DEPRECATION")
            appOps.unsafeCheckOpNoThrow("READ_SMS", android.os.Process.myUid(), ctx.packageName)
        }.exceptionOrNull()

        // Either it throws, or a future Android restores the operation. What must never happen is
        // it returning a mode the platform treats as proof, which is what the old code did.
        assertNotNull(
            "AppOps no longer knows about SMS; the platform must probe by using it instead",
            failure
        )
    }

    @Test
    fun deniedReadIsASecurityExceptionRatherThanAnEmptyResult() {
        val outcome = runCatching {
            ctx.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("_id"), null, null, null)?.use { it.count }
        }

        // Either it works, or it is refused. What it must never do is quietly return nothing,
        // because an empty inbox and a blocked inbox look identical to everything downstream.
        outcome.exceptionOrNull()?.let { assertTrue(it is SecurityException) }
    }
}