package dev.wildware.hollow

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hollow's copy of the CC0 human is the same art as moba's, and its `NOTICE.md` says so.
 *
 * An asset root belongs to one game - `udea { assetRoots }` names it, and the `.udeapak` a game
 * opens is built from its own root alone - so Hollow cannot read moba's tree without making one
 * game's build depend on another game's assets. The files are therefore copied, and a copy with
 * nothing watching it is a copy that drifts: someone re-exports the model for one game, the two
 * games ship different humans, and nothing fails.
 *
 * This is the thing that fails. It is the test `hollow/game/assets/models/human/NOTICE.md` names.
 */
class HumanAssetTest {

    @Test
    fun `every file of the human is identical to moba's copy`() {
        val hollow = humanDirectory(projectDir())
        val moba = humanDirectory(projectDir().resolve("../../moba/game").normalize())
        assertTrue(Files.isDirectory(moba), "moba's human is not at $moba")

        val here = listing(hollow)
        val there = listing(moba)
        assertEquals(there.keys, here.keys, "the two copies hold different files")
        assertTrue(EXPECTED_FILES.all { it in here }, "$hollow is missing one of $EXPECTED_FILES: ${here.keys}")

        for ((name, digest) in here) {
            // Everything but the notice: the art and the script that made it must be the same
            // bytes, while Hollow's notice carries a section moba's does not - the one that
            // explains why there are two copies at all, and names this test.
            if (name == NOTICE) continue
            assertEquals(there.getValue(name), digest, "$name differs between the two games")
        }

        assertTrue(
            "moba/game/assets/models/human" in Files.readString(hollow.resolve(NOTICE)),
            "Hollow's notice no longer says where the other copy is",
        )
    }

    @Test
    fun `the model and the texture are the CC0 files the notice names`() {
        val hollow = humanDirectory(projectDir())
        val notice = Files.readString(hollow.resolve(NOTICE))

        // The pack's own published SHA-256s are in the notice for the *source* files; what is
        // committed is the re-exported model, so only the texture is comparable byte for byte.
        // Assert the one that is, and that the notice still carries both - a notice whose hashes
        // were deleted would leave the provenance of this art unrecorded.
        assertEquals(
            TEXTURE_SHA,
            sha256(hollow.resolve("ClothedLightSkin.png")),
            "the texture is not the published CC0 file",
        )
        assertTrue(TEXTURE_SHA in notice, "the notice no longer records the texture's hash")
        assertTrue(SOURCE_MODEL_SHA in notice, "the notice no longer records the published model's hash")
        assertTrue("CC0" in notice, "the notice no longer states the licence")
    }

    /** Each file in [directory] by name, as a SHA-256. */
    private fun listing(directory: Path): Map<String, String> =
        Files.list(directory).use { stream ->
            stream.filter(Files::isRegularFile).toList().associate { it.fileName.toString() to sha256(it) }
        }

    private fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }

    private fun humanDirectory(gameProject: Path): Path = gameProject.resolve("assets/models/human")

    private fun projectDir(): Path = Path.of(
        checkNotNull(System.getProperty(PROJECT_DIR)) {
            "-D$PROJECT_DIR is not set; hollow/game/build.gradle.kts sets it on every Test task"
        },
    )

    private companion object {
        const val PROJECT_DIR = "udea.hollow.projectDir"

        const val NOTICE = "NOTICE.md"

        val EXPECTED_FILES = setOf("Human.fbx", "Human.glb", "ClothedLightSkin.png", "NOTICE.md", "relink.py")

        /** The pack's `OBJ/Textures/ClothedLightSkin.png`, committed unchanged. */
        const val TEXTURE_SHA = "c8a975424739500699e27618f1e57ece309dff5ffeff657f4ae3c3c7a309eb57"

        /** The pack's `FBX/Animated Human.fbx`, which `relink.py` re-exports into what is committed. */
        const val SOURCE_MODEL_SHA = "edd4fde3a73afe2a22ddb5a10a215373a0880d9a4ee32a0815879a260b8e8445"
    }
}
