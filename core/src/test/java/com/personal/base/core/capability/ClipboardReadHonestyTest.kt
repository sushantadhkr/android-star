package com.personal.base.core.capability

import com.personal.base.core.logging.PlatformLogger
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clipboard's honest answer.
 *
 * Android 10 restricted clipboard reads to the focused app and the current keyboard. A background
 * host is neither, so `getPrimaryClip()` returns nothing — and "nothing" is indistinguishable from
 * "the clipboard is empty" unless the capability says which one it is. These hold that line, because
 * the failure mode is a caller concluding the clipboard is blank when in fact they were not allowed
 * to look at it.
 */
class ClipboardReadHonestyTest {

    @Test
    fun `a partial capability is still usable`() {
        // The working half — writing — is still worth calling. Reporting it as unusable would make
        // the restriction look like the whole capability is broken.
        val partial = CapabilityStatus.Partial(
            whatWorks = "Writing to the clipboard",
            whatDoesNot = "Reading it."
        )
        assertTrue("partial capabilities must remain invokable", partial.isUsable)
    }

    @Test
    fun `a partial capability says which half works`() {
        val partial = CapabilityStatus.Partial(
            whatWorks = "Writing to the clipboard",
            whatDoesNot = "Reading it, because Android forbids it here."
        )
        assertTrue(partial.whatWorks.isNotBlank())
        assertTrue(partial.whatDoesNot.isNotBlank())
        assertTrue(
            "a bare 'partial' is not an explanation",
            partial.whatDoesNot.length > "Reading it".length
        )
    }

    @Test
    fun `blocked statuses stay unusable`() {
        assertTrue(!CapabilityStatus.Unavailable("no").isUsable)
        assertTrue(!CapabilityStatus.PermissionRequired(listOf("p")).isUsable)
        assertTrue(!CapabilityStatus.DisabledByUser("policy").isUsable)
    }

    @Test
    fun `clipboard read is documented as something Android may permit or refuse`() {
        val actions = CapabilityActions(PlatformCapabilityRegistry(), PlatformLogger())
        val read = actions.specFor(CapabilityType.CLIPBOARD)!!.action("read")!!

        assertTrue(
            "the read action must not promise a value it might not get",
            read.summary.contains("if Android permits it")
        )
        assertTrue(
            "the documented response must carry whether it was readable",
            read.returns.contains("readable")
        )
    }

    @Test
    fun `clipboard read is the only action that can be refused by the platform itself`() {
        val actions = CapabilityActions(PlatformCapabilityRegistry(), PlatformLogger())
        val spec = actions.specFor(CapabilityType.CLIPBOARD)!!

        // Writing is never refused by Android. Only reading is restricted, and only that one
        // action needs to describe the possibility.
        val write = spec.action("write")!!
        assertTrue(
            "writing must not be described as conditional",
            !write.summary.contains("permit")
        )
    }
}