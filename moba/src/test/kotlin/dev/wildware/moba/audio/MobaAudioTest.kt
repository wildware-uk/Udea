package dev.wildware.moba.audio

import dev.wildware.moba.CharacterAnimationSystem
import dev.wildware.moba.CueNames
import dev.wildware.moba.MobaAssets
import dev.wildware.moba.MobaCharacters
import dev.wildware.moba.MobaControls
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
import dev.wildware.udea.render.input.InjectedIntent
import dev.wildware.udea.render.input.IntentState
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

        val hit = CueId(MobaCues.MELEE_HIT)
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
            "nothing is bound to MELEE_HIT, so no blow that lands can make a sound",
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
        assertEquals(
            hitPlays.toLong(),
            playingAudio.audio.playsOf(hit),
            "the per-cue ledger and the device agree on how many hits were started",
        )
    }

    /**
     * The claim this wave is about: the two cue id spaces no longer overlap.
     *
     * This test is the old `the ability and notify cue namespaces overlap` inverted. That one
     * asserted the collision - [MobaCues] hand-numbered `1..9`, [CueNames] numbered the notifies
     * `0 until size`, both into one sink - and said that whoever separated them would be told here
     * to finish the routing. This is that finish: neither block holds a written-down id any more,
     * so the property to pin is that the two are adjacent and disjoint however many ids either
     * one mints.
     */
    @Test
    fun `the ability and notify cue namespaces are disjoint`() {
        val notifies: CueNames = MobaCharacters.cues
        assertEquals(
            listOf("attack_hit", "attack_hit_2", "attack_hit_3", "attack_hit_4", "fire_arrow", "heal", "swoosh"),
            notifies.ids.map { assertNotNull(notifies.nameOf(CueId(it))) },
            "the notify table is sorted, so these ids are a function of the bundle",
        )
        assertEquals(
            emptySet(),
            MobaCueSounds.collisions(notifies),
            "an id both blocks mint is a cue whose meaning depends on which one emitted it",
        )
        assertEquals(
            MobaCues.NOTIFY_BASE,
            notifies.ids.first,
            "the notify block starts where the ability block ended, so no id is wasted",
        )
        assertTrue(
            MobaCues.ids.max() < notifies.ids.first,
            "ability ids ${MobaCues.ids} must all fall below notify ids ${notifies.ids}",
        )

        val sounds = MobaCueSounds.load(RecordingDevice())
        assertEquals(emptyList(), sounds.silent, "every authored cue is bound")
        assertEquals(MobaCues.ids.size, sounds.bindings.size, "all nine, where four were bound")
        MobaCues.ids.forEach { id ->
            assertNotNull(sounds.bindings[CueId(id)], "${MobaCues.nameOf(id)} plays nothing")
        }
        notifies.ids.forEach { id ->
            assertNull(
                sounds.bindings[CueId(id)],
                "no animation notify is bound in this bundle; see MobaCueSounds for why",
            )
        }
    }

    /**
     * A notify names the unit that fired it, so its sound can be placed.
     *
     * `CharacterAnimationSystem` emitted `NetId.NONE` as every notify cue source, and `CueAudio`
     * plays an unlocatable cue at the ear - centred and unattenuated. A swing on the far side of
     * the field therefore sounded exactly like one in your face. The id is on the record the
     * system keeps as well as on the cue, which is what lets this read it after the queue is gone.
     */
    @Test
    fun `a fired notify carries the unit that fired it`() {
        val game = Booted()
        repeat(400) { game.host.run(1) }

        val fired = game.host.world.system<CharacterAnimationSystem>().log.entries
        assertTrue(fired.isNotEmpty(), "no notify fired in 400 ticks, so there is nothing to place")
        val located = fired.count { !it.source.isNone }
        println("[MobaAudioTest] ${fired.size} notify record(s), $located of them with an emitter")
        assertEquals(
            fired.size,
            located,
            "every unit in the seeded level has a NetId, so every notify it fires must name it",
        )
    }

    /**
     * The count of `Sound.play` calls per cue kind over a headless fight, with the ear on the
     * player.
     *
     * The number this wave asks for, at the resolution that says something: an aggregate `played`
     * cannot tell a run where all nine cues fired from one where the deaths carried it - which is
     * precisely what the id collision did, four cues bound out of nine and a healthy-looking
     * total. A zero here now means a cue nothing *emits*, and the assertion names which.
     */
    @Test
    fun `every authored cue is played at least once over a fight`() {
        val game = Booted()
        val device = RecordingDevice()
        val audio = MobaAudio.of(game.host, device)
        audio.listenTo(MobaEntry.playerId(game.host))
        // The elite orc spin is the player unit's slot 1 and no AI unit has one, so a run that
        // never touches the controls cannot fire it - which is what the per-cue ledger reported
        // the first time this test ran, and is a *gameplay* fact rather than a routing one. Q, at
        // the rate a player would mash it, through the same `IntentState` a keyboard writes.
        val keys = InjectedIntent(MobaControls.BINDINGS.catalog)
        game.host.ctx[IntentState.KEY].source = keys
        repeat(TICKS_PER_FIGHT) { tick ->
            if (tick % SPECIAL_EVERY == 0) keys.tap(MobaControls.ATTACK_2_ACTION)
            game.host.run(1)
            audio.frame()
        }
        val plays = MobaCues.ids.associate { MobaCues.nameOf(it) to audio.audio.playsOf(CueId(it)) }
        println(
            "[MobaAudioTest] $TICKS_PER_FIGHT ticks, ${device.plays} device play(s): " +
                plays.entries.joinToString(" ") { "${it.key}=${it.value}" },
        )
        assertEquals(
            device.plays.toLong(),
            plays.values.sum(),
            "every play the device saw is attributed to exactly one cue",
        )
        assertEquals(
            emptyList(),
            plays.filterValues { it == 0L }.keys.toList(),
            "these cues are authored, routed and loaded, and were never heard",
        )
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

    private companion object {
        /**
         * Long enough for the seeded battle to resolve.
         *
         * The fight is over in about forty seconds of simulated time. The elite orc spin and the
         * priest heal are the two cues that need most of it: one is on a cooldown and the other
         * only fires once somebody is hurt enough to be worth healing.
         */
        const val TICKS_PER_FIGHT: Int = 2400

        /**
         * Ticks between presses of the player special.
         *
         * A second. `ability/orc_elite_spin` has a cooldown longer than that, so most presses are
         * refused, which is exactly what a player mashing Q does and is why the count this test
         * asserts on is "at least one" rather than a number.
         */
        const val SPECIAL_EVERY: Int = 60
    }
}
