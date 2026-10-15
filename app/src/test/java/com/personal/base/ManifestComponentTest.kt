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

    private val sourceRoots = listOf(File("src/main/java"), File("../core/src/main/java"))

    /**
     * The simple names of every type declared anywhere in the shell or the core library.
     *
     * Read from the declarations rather than from file names, because Kotlin allows several classes
     * in one file — `PlatformAccessibilityService` lives inside
     * `AccessibilityCapabilityProvider.kt`. A filename lookup reports that class missing, and a
     * guard that cries wolf on real classes gets ignored, which is worse than not having one.
     */
    private val declaredTypes: Set<String> by lazy {
        val pattern = Regex("""\b(?:class|object|interface|enum class)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        sourceRoots
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .flatMap { file ->
                runCatching { file.readText() }.getOrDefault("")
                    .let { pattern.findAll(it).map { m -> m.groupValues[1] }.toList() }
            }
            .toSet()
    }

    /**
     * Whether a declared component really exists.
     *
     * Both name forms have to resolve, because both are legal in a manifest and the platform
     * declares each of them: `.shell.MainActivity` is dot-relative, while the capability services
     * are written out in full. Matching only one form silently leaves the other unchecked.
     */
    private fun sourceFileFor(component: String): Boolean {
        val simpleName = component.substringAfterLast('.')
        return simpleName in declaredTypes
    }

    @Test
    fun `every declared component has a class behind it`() {
        assertTrue("the manifest should be where the test expects it", manifest.exists())
        val declared = Regex("""android:name="([.A-Za-z][\w.]*)"""")
            .findAll(manifest.readText())
            .map { it.groupValues[1] }
            .filter { it.startsWith(".") || it.startsWith("com.personal") }
            .toList()

        assertTrue("the manifest declares components", declared.isNotEmpty())

        val missing = declared.filterNot { sourceFileFor(it) }
        assertTrue(
            "these components are declared but do not exist, so the platform offers them and " +
                "cannot deliver them: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `the component guard actually reads both name forms`() {
        // A guard that quietly matches nothing is worse than no guard, because it is reported as
        // passing. This asserts the pattern sees a fully-qualified name and a dot-relative one,
        // so a future tightening of the regex cannot silently narrow it to nothing.
        val text = manifest.readText()
        val seen = Regex("""android:name="([.A-Za-z][\w.]*)"""")
            .findAll(text)
            .map { it.groupValues[1] }
            .filter { it.startsWith(".") || it.startsWith("com.personal") }
            .toList()

        assertTrue("at least one component is declared dot-relative", seen.any { it.startsWith(".") })
        assertTrue(
            "at least one component is declared fully qualified; if none is, the resolution above " +
                "is untested",
            seen.any { !it.startsWith(".") }
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