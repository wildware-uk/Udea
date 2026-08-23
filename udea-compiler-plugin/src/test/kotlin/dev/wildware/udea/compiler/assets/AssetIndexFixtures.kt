package dev.wildware.udea.compiler.assets

import dev.wildware.udea.diagnostics.assets.AssetCatalog
import dev.wildware.udea.diagnostics.assets.AssetCatalogEntry
import dev.wildware.udea.diagnostics.assets.AssetCatalogJson
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Classpath roots carrying a `META-INF/udea/asset-index.json`, built the two ways a real
 * classpath carries one: a jar, and an output directory.
 *
 * Both shapes are exercised in every test that cares, because they take different branches of
 * the scanner and only one of them is what an upstream *project* dependency looks like inside
 * a Gradle build (a directory), while the other is what a *published* dependency looks like
 * (a jar). Testing one and asserting the other works is how half the mechanism goes untested.
 */
object AssetIndexFixtures {

    /** Real ids, from `example/src/main/resources/assets`. */
    const val ORC: String = "character/orc"

    /** Real id, from `example/src/main/resources/assets/blueprint/arrow.udea.kts`. */
    const val ARROW: String = "blueprint/arrow"

    /** Kinds the checker fixtures declare, so the subtype question has an answer. */
    const val CHARACTER_KIND: String = "udea.fixtures.CharacterAsset"
    const val BLUEPRINT_KIND: String = "udea.fixtures.BlueprintAsset"

    fun catalog(vararg entries: Pair<String, String>): AssetCatalog =
        AssetCatalog.of(entries.map { (id, kind) -> AssetCatalogEntry(id, kind) })

    /** The catalog both checker tests and reader tests resolve against. */
    fun exampleCatalog(): AssetCatalog =
        catalog(ORC to CHARACTER_KIND, ARROW to BLUEPRINT_KIND)

    /** A directory root whose `META-INF/udea/asset-index.json` holds [text]. */
    fun directoryRoot(text: String, name: String = "classes"): File {
        val root = File(tempDir(), name).also { it.mkdirs() }
        val resource = File(root, AssetCatalog.RESOURCE_PATH)
        resource.parentFile.mkdirs()
        resource.writeText(text, Charsets.UTF_8)
        return root
    }

    /** A jar root whose `META-INF/udea/asset-index.json` holds [text]. */
    fun jarRoot(text: String, name: String = "upstream.jar"): File {
        val jar = File(tempDir(), name)
        ZipOutputStream(jar.outputStream().buffered()).use { out ->
            // A jar in the wild carries other entries; if the scanner only worked on a
            // single-entry archive nothing here would notice.
            out.putNextEntry(ZipEntry("udea/fixtures/Marker.class"))
            out.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
            out.closeEntry()
            out.putNextEntry(ZipEntry(AssetCatalog.RESOURCE_PATH))
            out.write(text.toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }
        return jar
    }

    /** A directory root with no index at all: the silent case. */
    fun emptyRoot(name: String = "no-assets"): File =
        File(tempDir(), name).also { it.mkdirs() }

    /** A root whose index declares [version] instead of the one this build reads. */
    fun versionedRoot(version: Int, name: String = "future.jar"): File =
        jarRoot("{\n  \"version\": $version,\n  \"assets\": []\n}\n", name)

    private fun tempDir(): File =
        Files.createTempDirectory("udea-asset-index").toFile().also { dir ->
            Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
        }

    /** [AssetCatalogJson.encode] of [catalog], for a fixture that wants the real bytes. */
    fun encoded(catalog: AssetCatalog): String = AssetCatalogJson.encode(catalog)
}
