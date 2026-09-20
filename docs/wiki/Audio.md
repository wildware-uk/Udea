# Audio

The simulation never plays a sound. It drops a small note — a **cue** — into a queue saying "a hit
landed, and it came from this entity". Once a frame, the audio layer empties that queue and turns
each note into a noise.

That split is the whole design. A cue is deterministic and cheap; the sound is not, and a headless
server plays none at all without running different code.

## The pieces

| Thing | Module | Job |
|---|---|---|
| `Cue`, `CueId`, `CueSink`, `CueQueue` | `udea-core` | The note, and the box it goes in |
| `SoundCue` | `udea-assets` | The authored data: which files, how loud, how much pitch variance |
| `CueSound`, `AudioBindings` | `udea-audio` | Which cue id plays which loaded files |
| `CueAudio` | `udea-audio` | Empties the queue, picks a file, works out volume and pan |
| `AudioListener`, `CueSourceLocator` | `udea-audio` | Where the ear is, and where a cue came from |
| `AudioDevice`, `SoundHandle` | `udea-audio` | The one thing this module cannot do: make a noise |
| `KoolAudioDevice`, `koolAudioDevice(assetRoot)` | `udea-render` | The device that actually does |

`udea-audio` has no graphics context and no playback backend of its own. It names no Kool type, by
design — spec section 3 keeps Kool inside `udea-render`, and `UDEA-MG-002` fails the build if
`de.fabmax.kool:*` ever reaches `udea-audio`.

## Emitting a cue

A cue carries three things and nothing else: which effect, which tick, and which entity.

```kotlin
public data class Cue(
    public val id: CueId,
    public val tick: Tick,
    public val source: NetId = NetId.NONE,
)
```

Simulation code writes to `GameContext.cues`, which is a `CueSink`. It never reads back. A cue is
not simulation state and never enters a snapshot, so emitting one cannot change a replay.

`CueQueue` is the sink `CoreModule` puts there. It holds `CueQueue.DEFAULT_CAPACITY` (1024) undrained
cues and then drops the *newest* emit, counting it in `droppedCount`. That is deliberate: a bounded
queue with a counter beats an unbounded one that grows all match. It does assume somebody drains it.

`CueSinkDecorator` exists because a debug build wraps the sink so an observer can watch cues go past.
A wrapper is a `CueSink`, not a `CueQueue`, so anything that wants to *drain* walks the chain with
`CueSink.innermost()` rather than casting once.

## What a cue sounds like

A `soundCue` is authored in an asset script. Here is one from
`moba/game/assets/sounds/sounds.udea.kts`:

```kotlin
soundCue(
    name = "melee_hit",
    pitchVariance = 0.25F,
    volume = 0.5F,
    sounds = listOf(
        "sounds/effects/melee_hit_1.ogg",
        "sounds/effects/melee_hit_2.ogg",
        "sounds/effects/melee_hit_3.ogg",
    ),
)
```

`sounds` is a list because one event should not always sound identical — `CueAudio` picks one of them
at random per play. `pitchVariance` is a fraction *either side* of unit pitch, so `0.25` means 0.75x
to 1.25x. `volume` is linear gain at the ear.

The asset holds file paths, never loaded sounds. That is why `SoundCue` can be read on a machine with
no audio device at all.

## Binding cues to sounds

`CueSound.load` loads each file through the device and remembers the slots it got back.
`AudioBindings.of` puts them in a dense table indexed by cue id:

```kotlin
val bindings = AudioBindings.of(
    listOf(CueSound.load(CueId(MobaCues.MELEE_HIT), meleeHitAsset, device)),
)
```

A dense array, not a map: this is read once per drained cue and `CueId` is a value class, so a
`HashMap<CueId, _>` would box the key every lookup. Duplicate cue ids are refused, because otherwise
the sound a cue made would depend on list order. Ids above `AudioBindings.MAX_CUE_ID` (1023) are
refused too, which turns a `CueId(Int.MAX_VALUE)` typo into a message rather than a huge allocation.

**A cue with no sound is normal, not an error.** `CueAudio` counts it as `unbound` and carries on. A
game whose designers could not emit a cue until somebody had recorded audio for it would be a game
whose vocabulary was decided by its sound department.

## The mixer

`CueAudio` is built once and `drain`ed once a frame.

```kotlin
val audio = CueAudio(
    device = device,
    bindings = bindings,
    listener = AudioListener(falloff = 20F, panWidth = 6F),
    locator = locator,
)

// once a frame, between ticks
audio.drain(host.ctx.cues as CueQueue)
```

For each cue it:

1. looks up the binding — no binding, count `unbound`, done;
2. claims a voice (see below) — none left, count `suppressed`, done;
3. asks the `CueSourceLocator` where the source is, and works out volume from distance and pan from
   the sideways offset;
4. drops it if the volume is below `CueAudio.SILENCE_FLOOR` (1/512), counting `suppressed`;
5. picks one of the cue's files at random, picks a pitch, and calls `device.play`.

Four counters are public and are what a test reads: `drained`, `played`, `unbound`, `suppressed`.
`playsOf(cue)` gives the count per cue id, which is what tells "every kind of sound fired" apart from
"the deaths carried the total".

**Drain per frame, not per tick.** A fast-forward runs many ticks between frames, and all their cues
belong to the one frame that follows. That is what makes the queue's capacity a backlog allowance
rather than a per-tick budget.

### The voice cap

Twenty-seven units swinging on the same tick emit twenty-seven melee hits. Twenty-seven copies of one
200ms recording started in the same millisecond is not twenty-seven hits — it is one loud click, and
it costs the device twenty-seven voices to make it.

`CueAudio.DEFAULT_VOICE_CAP` is 3, per cue id per drain. Three hits, three swooshes and three deaths
in one frame is nine sounds, not three.

### Randomness

Which of five swoosh recordings plays is wall-seeded, on purpose. It must never enter a snapshot or
change a rewind. Seeding it from `RngService` would couple it to the simulation stream: draw one fewer
sound and every later combat roll shifts. The seed comes from `kotlin.time.Clock.System`, and a test
that needs two mixers to agree passes `seed` itself.

This is the general rule — see [[Tick-Model-and-Determinism]]. Presentation randomness is separately
typed and lives where the simulation cannot see it.

## The ear

`AudioListener` holds the ear's position and two distances:

- `falloff` — how far out a sound reaches silence. Attenuation is **linear**, not inverse-square.
  That is a game-audio choice, not an approximation of physics: inverse-square never reaches zero,
  so every sound in the level stays faintly audible and the mixer spends voices on them.
- `panWidth` — how far to the side maps to full stereo pan.

Both defaults (`10F` and `5F`) are in *world* units, so a game whose world units are small must say
its own. `moba` does:

```kotlin
public const val FALLOFF: Float = 20F * MobaScale.WORLD
public const val PAN_WIDTH: Float = 6F * MobaScale.WORLD
```

A `Cue` carries a `NetId` and no position — widening it would put a presentation concern into a kernel
type — so the mixer looks the position up through a `CueSourceLocator`. It writes into a caller-owned
`FloatArray` rather than returning a pair, because this runs once per drained cue.

`CueSourceLocator.Unlocated` answers "no position" for everything, so every cue plays at the ear: full
volume, centred. That is right for a UI-only cue set and for a headless mixer.

A locator returning `false` is the ordinary case, not a failure. A death cue names an entity the
emitting system has just removed; it plays at the ear instead of being silently dropped.

## Devices

```kotlin
public interface AudioDevice {
    public fun load(path: String): SoundHandle
    public fun play(sound: SoundHandle, volume: Float, pitch: Float, pan: Float)
    public fun close()
}
```

`SoundHandle` is a value class over an `Int` slot, so the routing table is an `IntArray` and not an
array of references.

**`AudioDevice.Silent` is a complete implementation, not a stub.** It is what `RenderMode.Headless`
gets. It loads nothing, plays nothing, allocates nothing — and the queue is still drained, which is
the part that must never stop. A headless server, a CI run and an agent session all exercise the
drain path.

`load` throws `AudioLoadException` when a file is missing or will not decode. It never substitutes
silence quietly: a game that plays nothing because its files moved and a game that is deliberately
silent are different states, and whoever composes the game decides which one this process is.

### The Kool device

`udea-render` has the real one. On the desktop:

```kotlin
val device: AudioDevice = koolAudioDevice(assetRoot)
```

Each loaded file becomes a Kool `AudioClip`. Two cues naming the same file share one clip and one
pool.

Two honest limitations, both Kool 0.19.0's:

- **Pitch and pan are not honoured.** `AudioClip` has a volume and nothing else on desktop, Android
  and JS alike. `KoolAudioDevice.play` applies `volume` and ignores the other two. It does not
  approximate them — a pan faked as a volume drop is a quieter sound in the wrong place, not a sound
  on the left. `CueAudio` still computes both, so a backend that has them gets them.
- **Only the desktop has a clip loader today.** The device class itself is `commonMain`; what differs
  per platform is how a clip is *made*, which is the `KoolClipLoader` seam. `DesktopClipLoader` is the
  only implementation on master. A target gains audio by adding a loader and a factory.

`.ogg` files are decoded by `OggToWav` first, because Kool's own Vorbis decode frees memory it does
not own and takes the JVM down.

## Putting it together

`moba` assembles all of it in `MobaAudio`. Building it, and driving it:

```kotlin
val audio = MobaDesktopAudio.forHost(host)   // picks Silent or Kool
audio.listenTo(playerNetId)                  // the ear rides this entity

// in the frame loop
host.frame(0f)
audio.frame()                                // moves the ear, then drains
```

`MobaDesktopAudio.forHost` is the one place that decides this process will be silent:

- `RenderMode.Headless` → `AudioDevice.Silent`;
- no asset root system property → silent, and it says so on stderr;
- `koolAudioDevice` throws `AudioLoadException` → silent, printing the failing path and the reason.

That last one is why this build box, which has no audio output, still runs the game: it logs one line
and keeps draining.

`MobaAudio.of` refuses to build when the innermost `CueSink` is not a `CueQueue`, because then there
is genuinely nothing to drain and doing nothing quietly is exactly the failure the class exists to
prevent.

The ear follows an entity rather than the camera. `CameraRig`'s position belongs to the render thread
and is not exposed for reading; `moba`'s camera follows the player's unit, so following that unit puts
the ear where the camera is. A free camera — which `moba` does not have — would drift away from it.

## Hollow has no audio yet

`hollow`, the 3D example, emits no cues and builds no mixer on master. See [[Example-Games]].

## See also

- [[Assets]] — where `soundCue` is authored and how `.udeapak` is built
- [[Abilities-GAS]] — the ability system that emits most of `moba`'s cues
- [[Architecture]] — why `udea-audio` is headless and `udea-render` is not
- [[Tick-Model-and-Determinism]] — cues, snapshots, and why presentation randomness is separate
- [[Example-Games]] — `moba`'s nine cues, and what Hollow does not have yet
