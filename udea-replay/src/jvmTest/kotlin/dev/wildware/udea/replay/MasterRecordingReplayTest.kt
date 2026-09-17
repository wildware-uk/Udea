package dev.wildware.udea.replay

import dev.wildware.udea.replay.equality.fixture.DriftFixture
import dev.wildware.udea.replay.equality.fixture.DriftFixtureKind
import dev.wildware.udea.replay.equality.fixture.DriftWorld
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A `.udearep` that `master`'s build recorded replays on this build to the hash it recorded, on
 * every tick (issue #206).
 *
 * The file is `drift-3600.udearep`, and the SHA-256 below is what makes it `master`'s: running
 * `:udea-replay:udeaWriteReplayFixture` in a checkout of `origin/master` at `409c044`, with the
 * checked-in copy deleted first, rebuilt a file with exactly this digest. Pinning it here means
 * the replay below cannot quietly start passing against a file re-recorded by this branch.
 *
 * The comparison is [ReplayVerifier]'s: the recording's own hash stream against the replayed
 * world's, tick by tick, so the recorder is the authority - which is the question a byte-compatible
 * port has to answer. Whether two machines agree with each other is the replay-equality job's.
 */
class MasterRecordingReplayTest {

    @Test
    fun `master's recording replays bit-exactly on this build`() {
        val bytes = checkNotNull(javaClass.getResourceAsStream(DriftFixtureKind.PR.resource)) {
            "${DriftFixtureKind.PR.resource} is not on the test classpath"
        }.use { it.readBytes() }
        assertEquals(MASTER_SHA256, sha256(bytes), "the fixture is no longer the file master recorded")

        val recording = ReplayRecording.decode(bytes)
        val verification = ReplayVerifier.verify(recording, DriftWorld.worlds())

        assertTrue(verification.isBitExact, verification.describe())
        assertEquals(DriftFixture.PR_TICKS, verification.ticksCompared)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        /** `sha256sum drift-3600.udearep` of the file `origin/master` `409c044` recorded. */
        const val MASTER_SHA256: String = "57cc9c2fa3ca5348a6d04be00117fe89c3d26c6ff2a3bc2820cf29bed39d2a60"
    }
}
