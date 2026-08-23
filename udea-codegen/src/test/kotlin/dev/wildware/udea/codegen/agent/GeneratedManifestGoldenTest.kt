package dev.wildware.udea.codegen.agent

import dev.wildware.udea.codegen.ModuleRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generated manifest against a checked-in golden.
 *
 * This is the CI diff the brief asks for, and it is a test rather than a bespoke Gradle task
 * because `check` already runs it on every commit and because the update path then matches the
 * one this module already has for its generated-source hashes — one flag, not two.
 *
 * What it buys is not byte-tidiness. **A tool description is API for a model**, and spec
 * section 6 makes description quality a Phase 1 exit criterion: rewording one changes what an
 * agent will and will not call, and that has to arrive as a line in a diff somebody read rather
 * than as a silent change to a build artefact.
 */
class GeneratedManifestGoldenTest {

    @Test
    fun `the generated manifest matches the reviewed golden`() {
        if (System.getProperty(UPDATE_PROPERTY) == "true") {
            golden.parentFile.mkdirs()
            golden.writeText(generated.readText())
        }
        assertTrue(golden.isFile, "no golden at ${golden.absolutePath}; regenerate with -P$UPDATE_PROPERTY=true")
        assertEquals(
            golden.readText().replace("\r\n", "\n"),
            generated.readText().replace("\r\n", "\n"),
            "the agent tool manifest changed. Every line of it is text a model reasons over, so " +
                "review the diff; if the change was intended, re-run with -P$UPDATE_PROPERTY=true.",
        )
    }

    @Test
    fun `the golden is the document the bridge parser accepts, not just matching text`() {
        // A golden diff alone would happily pin a manifest that is not valid JSON. Parsing it
        // here is what stops the two tests from being one test.
        val document = TestJson.obj(TestJson.parse(golden.readText()))
        assertEquals(ToolManifestFacts.PROTOCOL, document["protocol"])
        assertTrue(TestJson.arr(document["toolsets"]).isNotEmpty())
    }

    private companion object {
        /** The same flag the generated-source hashes use: one way to update generated goldens. */
        const val UPDATE_PROPERTY = "udea.updateGeneratedHashes"

        val generated: File = ModuleRoot
            .file("build/generated/ksp/test/resources/udea/CodegenFixtures-agent-tools.json")
            .also {
                check(it.isFile) { "no generated manifest at ${it.absolutePath}; run :udea-codegen:kspTestKotlin" }
            }

        val golden: File = ModuleRoot.file("src/test/resources/CodegenFixtures-agent-tools.json")
    }
}
