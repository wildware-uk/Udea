package dev.wildware.udea.replay.fixture

import dev.wildware.udea.core.Tick
import dev.wildware.udea.replay.BuildIdentity
import dev.wildware.udea.replay.InputSample
import dev.wildware.udea.replay.InputSchema
import dev.wildware.udea.replay.ReplayRecorder
import dev.wildware.udea.replay.ReplayRecording
import dev.wildware.udea.replay.ReplayRefusedException
import dev.wildware.udea.replay.ReplayVerifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `--update-replay-fixtures`, issue #165, against the failure it exists to answer.
 *
 * ## The failure, stated
 *
 * A `.udearep` carries the [BuildIdentity] of the build that recorded it, and
 * `ReplayVerifier.refuseIfMismatched` refuses it the moment any of those four fields moves. One
 * of the four is `protoHash`, which moves whenever a replicated component is added or removed -
 * issue #167 moved it from `0xea9f` to `0xc67b` by honouring `visibility = OwnerOnly` in the
 * snapshot writer, and every checked-in recording made before it became unreplayable in the same
 * commit. The gate then fails for a reason that has nothing to do with determinism, and the only
 * way out is to rebuild the fixture.
 *
 * So the flag's job is not "rewrite the bytes". It is: tell a reader *which* identity field
 * moved, name the one command that rebuilds every fixture, and rebuild them only when asked.
 *
 * ## Why the fixture here is synthetic
 *
 * Every case below needs a recording whose identity differs from this build's by a stated
 * amount, which is a thing no real fixture can be on demand. These recordings go through
 * `ReplayRecorder` and `ReplayRecording.decode` - the real writer and the real reader - and
 * differ from `drift-3600.udearep` only in being four ticks of a world that does not exist.
 * `ReplayFixturesCurrentTest` is the same mechanism pointed at the real checked-in bytes.
 */
class ReplayFixtureUpdateTest {

    private val dir: Path = createTempDirectory("udea-replay-fixture")

    @AfterTest
    fun cleanUp() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    @Test
    fun `a fixture this build can replay is reported current and its bytes are not touched`() {
        val target = dir.resolve(NAME)
        write(target, protoHash = LIVE_PROTO_HASH)
        val before = Files.readAllBytes(target)

        val status = reconcileOne(target, update = true)

        assertEquals(ReplayFixtureStatus.Outcome.CURRENT, status.outcome)
        assertTrue(
            before.contentEquals(Files.readAllBytes(target)),
            "the update flag rewrote a fixture that was already replayable. It must not: a " +
                "regeneration that runs unconditionally churns a checked-in binary on every " +
                "invocation and makes the diff worthless as a signal.",
        )
    }

    @Test
    fun `a fixture whose protoHash has moved is refused, naming the field and both sides`() {
        val target = dir.resolve(NAME)
        write(target, protoHash = STALE_PROTO_HASH)

        val status = reconcileOne(target, update = false)

        assertEquals(ReplayFixtureStatus.Outcome.REFUSED, status.outcome)
        assertContains(status.detail, "protoHash")
        assertContains(status.detail, BuildIdentity.hexInt(STALE_PROTO_HASH))
        assertContains(status.detail, BuildIdentity.hexInt(LIVE_PROTO_HASH))
    }

    @Test
    fun `a refused fixture names the one command that rebuilds it`() {
        val target = dir.resolve(NAME)
        write(target, protoHash = STALE_PROTO_HASH)
        val statuses = listOf(reconcileOne(target, update = false))

        val failure = assertFailsWith<IllegalStateException> {
            ReplayFixtures.requireCurrent(statuses, TASK)
        }

        // The whole convention in one string, so a reader never has to find out how the flag is
        // spelled. `--update-goldens` is documented and `-Dupdate.goldens=true` is typed; this
        // prints the typed form of its own name rather than the documented one.
        assertContains(failure.message.orEmpty(), "./gradlew $TASK -D${ReplayFixtures.UPDATE_PROPERTY}=true")
        assertContains(failure.message.orEmpty(), NAME)
    }

    @Test
    fun `the update flag rebuilds a fixture whose protoHash has moved, and the replay stops refusing it`() {
        val target = dir.resolve(NAME)
        write(target, protoHash = STALE_PROTO_HASH)
        // The state the flag exists for, asserted rather than assumed: before the rebuild, the
        // replay entry point every leg of the gate runs refuses these bytes outright.
        assertFailsWith<ReplayRefusedException> {
            ReplayVerifier.refuseIfMismatched(ReplayRecording.readFrom(target), liveIdentity())
        }

        val status = reconcileOne(target, update = true)

        assertEquals(ReplayFixtureStatus.Outcome.REGENERATED, status.outcome)
        assertContains(status.detail, "protoHash")
        ReplayVerifier.refuseIfMismatched(ReplayRecording.readFrom(target), liveIdentity())
        ReplayFixtures.requireCurrent(listOf(reconcileOne(target, update = false)), TASK)
    }

    @Test
    fun `a fixture that does not exist yet is reported missing, and the flag writes it`() {
        val target = dir.resolve(NAME)
        assertTrue(Files.notExists(target), "the test is meaningless if the file is already there")

        assertEquals(ReplayFixtureStatus.Outcome.MISSING, reconcileOne(target, update = false).outcome)

        val written = reconcileOne(target, update = true)

        assertEquals(ReplayFixtureStatus.Outcome.REGENERATED, written.outcome)
        assertEquals(TICKS, ReplayRecording.readFrom(target).tickCount)
    }

    @Test
    fun `a fixture whose recorded length is not the length it declares is refused`() {
        // Not an identity field, and no `BuildIdentity` check can see it: a recording of the
        // right build and the wrong length replays perfectly and simply stops early, so a
        // nightly job asking for 36000 ticks would silently measure 3600 of them.
        val target = dir.resolve(NAME)
        write(target, protoHash = LIVE_PROTO_HASH, ticks = TICKS - 1)

        val status = reconcileOne(target, update = false)

        assertEquals(ReplayFixtureStatus.Outcome.REFUSED, status.outcome)
        assertContains(status.detail, "${TICKS - 1}")
        assertContains(status.detail, "$TICKS")
    }

    @Test
    fun `bytes that are not a recording at all are refused with what the reader said`() {
        // A reachable path - a truncated checkout, a merge that took one side of a binary - and
        // one where swallowing the reader's own message would leave a reader with nothing.
        val target = dir.resolve(NAME)
        Files.write(target, "not a udearep".toByteArray())

        val status = reconcileOne(target, update = false)

        assertEquals(ReplayFixtureStatus.Outcome.REFUSED, status.outcome)
        assertContains(status.detail, "not a .udearep")
    }

    @Test
    fun `the flag is read from the property that spells the documented flag`() {
        // The two spellings are one convention or they are two, and two is worse than either.
        assertEquals("--update-replay-fixtures", ReplayFixtures.UPDATE_FLAG)
        assertEquals("update.replay.fixtures", ReplayFixtures.UPDATE_PROPERTY)
        assertTrue(ReplayFixtures.updateRequested { if (it == ReplayFixtures.UPDATE_PROPERTY) "true" else null })
        assertTrue(!ReplayFixtures.updateRequested { null })
        assertTrue(!ReplayFixtures.updateRequested { "yes" }, "only the string `true` turns it on")
    }

    // --- the synthetic fixture ------------------------------------------------------------

    private fun reconcileOne(target: Path, update: Boolean): ReplayFixtureStatus =
        ReplayFixtures.reconcile(listOf(fixture(target)), update = update).single()

    private fun fixture(target: Path): ReplayFixture = ReplayFixture(
        name = NAME,
        checkedInAt = target,
        ticks = TICKS,
        identity = ::liveIdentity,
        record = { ticks -> record(ticks, LIVE_PROTO_HASH) },
    )

    private fun liveIdentity(): BuildIdentity = identity(LIVE_PROTO_HASH)

    private fun write(target: Path, protoHash: Int, ticks: Int = TICKS) {
        record(ticks, protoHash).writeTo(target)
    }

    private fun record(ticks: Int, protoHash: Int): ReplayRecording {
        val recorder = ReplayRecorder(
            identityWithoutSchema = identity(protoHash),
            schema = SCHEMA,
            peerCount = 1,
            gameId = "udea-replay-fixture-update-test",
            gameVersion = "1",
        )
        val slots = arrayOf(InputSample(SCHEMA))
        repeat(ticks) { index ->
            slots[0].setAxis(0, index * 0.25f, -index * 0.25f)
            recorder.record(Tick(index.toLong()), slots, index.toLong())
        }
        return recorder.seal()
    }

    private fun identity(protoHash: Int): BuildIdentity = BuildIdentity(
        rootSeed = 20_260_831L,
        protoHash = protoHash,
        assetGraphHash = "fixture-update-test".toByteArray(Charsets.UTF_8),
        inputSchemaHash = SCHEMA.hash,
    )

    private companion object {
        const val NAME: String = "tiny-4.udearep"
        const val TASK: String = ":udea-replay:test"
        const val TICKS: Int = 4

        /** Issue #167's two, so the numbers in this test are the ones that really moved. */
        const val STALE_PROTO_HASH: Int = 0xea9f
        const val LIVE_PROTO_HASH: Int = 0xc67b

        val SCHEMA: InputSchema = InputSchema(axes = listOf("test/move"), actions = listOf("test/act"))
    }
}
