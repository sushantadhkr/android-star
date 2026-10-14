package com.personal.base

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every component the manifest declares must actually exist.
 *
 * Six services were declared with no class behind them — including the input method, which is the
 * only role Android lets read the clipboard from the background. Nothing failed loudly: the app
 * installed, ran, and quietly offered a phone's worth of capability it could not deliver.
 *
 * That is the exact failure this platform is supposed to make impossible, and the one file that
 * must never lie is the one that lied. So it is checked.
 */
class ManifestComponentTest {

    private val manifest = File("src/main/AndroidManifest.xml")

    private fun sourceFileFor(component: String): File? {
        val relative = component.removePrefix(".").replace('.', '/')
        return listOf(
            File("src/main/java/com/personal/base/$relative.kt"),
            File("src/main/java/com/personal/base/$relative.java"),
            File("src/main/java/$relative.kt"),
            File("src/main/java/$relative.java"),
        ).firstOrNull { it.exists() }
    }

    @Test
    fun `every declared component has a class behind it`() {
        assertTrue("the manifest should be where the test expects it", manifest.exists())
        val declared = Regex("""android:name="(\.[^"]+)"""")
            .findAll(manifest.readText())
            .map { it.groupValues[1] }
            .toList()

        assertTrue("the manifest declares components", declared.isNotEmpty())

        val missing = declared.filter { sourceFileFor(it) == null }
        assertTrue(
            "these components are declared but do not exist, so the platform offers them and " +
                "cannot deliver them: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `every meta-data resource referenced actually exists`() {
        val text = manifest.readText()
        val referenced = Regex("""android:resource="@xml/([^"]+)"""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()

        val missing = referenced.filterNot { File("src/main/res/xml/$it.xml").exists() }
        assertTrue("these xml resources are referenced but missing: $missing", missing.isEmpty())
    }

    @Test
    fun `no xml resource is left behind by a removed component`() {
        val referenced = Regex("""android:resource="@xml/([^"]+)"""")
            .findAll(manifest.readText())
            .map { it.groupValues[1] }
            .toSet()

        val orphans = File("src/main/res/xml").listFiles()
            ?.filter { it.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }
            ?.filterNot { it in referenced || it == "backup_rules" || it == "data_extraction_rules" }
            .orEmpty()

        assertTrue(
            "these xml resources are no longer referenced by anything: $orphans",
            orphans.isEmpty()
        )
    }
}