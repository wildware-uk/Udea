package dev.wildware.moba.audio

import dev.wildware.moba.CueNames
import dev.wildware.moba.MobaAssets
import dev.wildware.moba.MobaCharacters
import dev.wildware.moba.MobaGame
import dev.wildware.moba.ability.MobaCues
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.SoundCue
import dev.wildware.udea.audio.AudioDevice
import dev.wildware.udea.audio.SoundHandle
import dev.wildware.udea.core.CueId
import dev.wildware.udea.core.CueQueue
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The claim this wave is about, measured on the real game: the cue queue is served.
 *
 * Everything here runs `RenderMode.Headless`, which is what CI and an agent session run, and which
 * is exactly the arrangement the drain has to work in - a headless process has no renderer and no
 * speakers, and before this it also had nothing emptying the queue.
 */
class MobaAudioTest {

    /** A booted, seeded headless game and its cue queue. */
    private class Booted {
        val host: GameHost = GameHost(RenderMode.Headless, MobaGame.definition(), null)
        val queue: CueQueue

        init {
            MobaEntry.seed(host)
            queue = host.ctx.cues as CueQueue
        }
    }

    /**
     * Records which *file* each play was for, without needing a `Gdx.audio`.
     *
     * Which slot matters and a bare count does not: this game binds four cues, and a test asserting
     * only that "something played" stays green when the melee hit stops playing and the deaths
     * carry it. Slots are per-`load` call and this device does not de-duplicate paths, so the slots
     * behind one `CueSound` identify *that cue* even where two cues name the same recording -
     * `sounds/arrow_hit` reuses two of `sounds/melee_hit`'s three files, so matching on the file
     * name would have counted an arrow landing as a sword landing.
     */
    private class RecordingDevice : AudioDevice {
        val loaded = mutableListOf<String>()
        val playedSlots = mutableListOf<Int>()

        val loads: Int get() = loaded.size
        val plays: Int get() = playedSlots.size

        override fun load(path: String): SoundHandle {
            loaded += path
            return SoundHandle(loaded.size - 1)
        }

        override fun play(sound: SoundHandle, volume: Float, pitch: Float, pan: Float) {
            playedSlots += sound.slot
        }

        override fun close(): Unit = Unit
    }

    /**
     * The before and after the wave was asked for, on one thousand ticks of the real fight.
     *
     * `MobaGame.definition()` twice, so the two runs are the same game from the same seed. The
     * only difference between them is whether anything calls [MobaAudio.frame].
     */
    @Test
    fun `a thousand ticks saturate the queue undrained and never fill it drained`() {
        val ticks = 1000

        val before = Booted()
        repeat(ticks) { before.host.run(1) }
        val saturated = before.queue.size
        val dropped = before.queue.droppedCount
        println(
            "[MobaAudioTest] undrained after $ticks ticks: queue=$saturated/" +
                "${CueQueue.DEFAULT_CAPACITY} dropped=$dropped emitted=${saturated + dropped}",
        )
        assertEquals(
            CueQueue.DEFAULT_CAPACITY,
            saturated,
            "the shipped game pins its cue queue at capacity - this is the defect",
        )
        assertTrue(dropped > 0, "and then silently discards everything after it")

        val after = Booted()
        val device = RecordingDevice()
        val audio = MobaAudio.of(after.host, device)
        var peak = 0
        var drained = 0L
        repeat(ticks) {
            after.host.run(1)
            peak = maxOf(peak, after.queue.size)
            drained += audio.frame().toLong()
        }
        println(
            "[MobaAudioTest] drained after $ticks ticks: peak=$peak dropped=${after.queue.droppedCount} " +
                "drained=$drained played=${audio.audio.played} unbound=${audio.audio.unbound} " +
                "suppressed=${audio.audio.suppressed} devicePlays=${device.plays}",
        )
        assertEquals(0, after.queue.size, "nothing is left on the queue")
        assertEquals(0L, after.queue.droppedCount, "and nothing was ever dropped")
        assertTrue(
            peak < CueQueue.DEFAULT_CAPACITY / 4,
            "depth stays far below capacity; peak was $peak",
        )
        assertTrue(drained > dropped, "the drained run took more cues than the other one threw away")
    }

    /** The requested proof, at the level a headless test can reach it: hits reach the device. */
    @Test
    fun `melee hits reach the audio device during the fight`() {
        val game = Booted()
        val device = RecordingDevice()
        val audio = MobaAudio.of(game.host, device)
        assertTrue(device.loads > 0, "the routing table loaded some files")

        val hit = assertNotNull(
            MobaCharacters.cues.idOf("attack_hit"),
            "the roster declares an attack_hit notify; that is the frame a blade lands on",
        )
        var hits = 0
        repeat(600) {
            game.host.run(1)
            game.queue.drain { cue -> if (cue.id == hit) hits++ }
        }
        assertTrue(hits > 0, "no melee hit was cued in 600 ticks, so there is nothing to hear")

        val playing = Booted()
        val playingDevice = RecordingDevice()
        val playingAudio = MobaAudio.of(playing.host, playingDevice)
        playingAudio.listenTo(MobaEntry.playerId(playing.host))
        repeat(600) {
            playing.host.run(1)
            playingAudio.frame()
        }
        val hitSound = assertNotNull(
            playingAudio.sounds.bindings[hit],
            "nothing is bound to the attack_hit notify, so no swing can make a sound",
        )
        val hitSlots = (0 until hitSound.size).map { hitSound.handleAt(it).slot }.toSet()
        val hitPlays = playingDevice.playedSlots.count { it in hitSlots }
        println(
            "[MobaAudioTest] 600 ticks: ${playingDevice.plays} play(s), $hitPlays of them a melee " +
                "hit, from ${playingDevice.loaded.size} loaded file(s)",
        )
        assertTrue(
            hitPlays > 0,
            "the mixer drained ${playingAudio.audio.drained} cues and played no melee hit; the " +
                "slots it played were ${playingDevice.playedSlots.distinct().sorted()} and the " +
                "hit is $hitSlots",
        )
    }

    /**
     * The cue-id collision, written down so that fixing it is a red test rather than a silent no-op.
     *
     * [MobaCues] hand-numbers `1..9` and [CueNames] numbers the animation notifies `0 until size`,
     * into the same `CueId` space on the same sink. This asserts the overlap that exists today. It
     * goes red when either namespace changes - a new notify name renumbers the whole notify table -
     * and the person it goes red on is the person who has to decide what the new routing is.
     */
    @Test
    fun `the ability and notify cue namespaces overlap, and the overlap is left silent`() {
        val notifies = MobaCharacters.cues
        assertEquals(
            listOf("attack_hit", "attack_hit_2", "attack_hit_3", "attack_hit_4", "fire_arrow", "heal", "swoosh"),
            (0 until notifies.size).map { assertNotNull(notifies.nameOf(CueId(it))) },
            "the notify table is sorted, so these ids are a function of the bundle",
        )
        assertEquals(
            setOf(
                MobaCues.DAMAGE,
                MobaCues.MELEE_HIT,
                MobaCues.MELEE_SWOOSH,
                MobaCues.KNOCKBACK,
                MobaCues.HEAL,
                MobaCues.SPIN,
            ),
            MobaCueSounds.ambiguousIds(notifies),
            "every MobaCues id below ${notifies.size} is also a notify id, so a consumer holding " +
                "a Cue cannot tell which of the two emitted it",
        )

        val sounds = MobaCueSounds.load(RecordingDevice())
        assertEquals(
            listOf("damage", "heal", "knockback", "melee_hit", "melee_swoosh", "spin"),
            sounds.ambiguous,
        )
        assertNull(sounds.bindings[CueId(MobaCues.SPIN)], "an ambiguous id plays nothing")
        assertNotNull(sounds.bindings[CueId(MobaCues.DEATH)], "9 is above the notify range")
        assertNotNull(sounds.bindings[CueId(MobaCues.ARROW_FIRED)])
        assertNotNull(sounds.bindings[CueId(MobaCues.ARROW_HIT)])
        assertNotNull(
            sounds.bindings[assertNotNull(notifies.idOf("attack_hit"))],
            "id 0 is claimed by no ability cue, so the hit sound is routable",
        )
        assertEquals(4, sounds.bindings.size)
    }

    /** Every file the routing names is in the asset tree, at the path `GdxAudioDevice` looks in. */
    @Test
    fun `every routed sound cue names files that exist under the asset root`() {
        val root = assetRoot()
        val ids = MobaCueSounds.BY_ABILITY_CUE.values + MobaCueSounds.BY_NOTIFY.values
        assertTrue(ids.isNotEmpty())
        ids.distinct().forEach { assetId ->
            val cue = assertNotNull(
                MobaAssets.registry.find(AssetId(assetId)) as? SoundCue,
                "'$assetId' is routed but is not a soundCue in the bundle",
            )
            cue.sounds.forEach { path ->
                val file = File(root, path.value)
                assertTrue(file.isFile, "${cue.id} names ${path.value}, which is not at $file")
            }
        }
    }

    /**
     * Where `moba/assets` is from wherever the test JVM was started.
     *
     * `GdxAudioDevice` resolves the same files through `Gdx.files.internal("assets/<path>")`, which
     * is relative to the working directory; Gradle runs both `test` and `runAudio` with this
     * project as the working directory, so the two agree. Walking up is what makes the test survive
     * being run from the repository root by an IDE.
     */
    private fun assetRoot(): File {
        var candidate: File? = File(".").canonicalFile
        while (candidate != null) {
            val assets = File(candidate, "assets/sounds")
            if (assets.isDirectory) return File(candidate, GdxAudioDevice.DEFAULT_ASSET_ROOT)
            val nested = File(candidate, "moba/assets/sounds")
            if (nested.isDirectory) return File(candidate, "moba/${GdxAudioDevice.DEFAULT_ASSET_ROOT}")
            candidate = candidate.parentFile
        }
        error("no moba/assets/sounds directory above ${File(".").canonicalFile}")
    }
}
