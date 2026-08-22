package dev.wildware.udea.core.snapshot

/**
 * What a snapshot deliberately does not carry, and what happens to it on restore.
 *
 * A snapshot is simulation state. Everything here is either presentation state, which is not
 * simulated and must not rewind, or state owned outside the simulation entirely. Writing the
 * list down as a type rather than as a paragraph means an agent can be told why the smoke
 * puff it was looking at did not come back, and means adding a new excluded subsystem is a
 * reviewed diff.
 *
 * Two shapes of exclusion, and the difference matters:
 *
 * - **cleared** — the subsystem holds state derived from ticks that no longer happened, so
 *   after a restore it must be brought to a defined state. It registers an
 *   [ExcludedSubsystem] with [SnapshotService] and is told when a restore lands.
 * - **untouched** — the subsystem has nothing to do with simulated time at all. It registers
 *   nothing, and the entry exists so that "why was this not restored?" has an answer.
 */
public enum class SnapshotExclusion(
    /** Why this subsystem is outside the snapshot. Shown to an agent asking about a rewind. */
    public val reason: String,
) {

    /**
     * Cleared. A particle is a presentation effect spawned by a `Cue`; the cues that spawned
     * the live ones belong to ticks the rewind just unwound, so leaving them running would
     * draw explosions for hits that no longer happened.
     */
    Particles("particles are presentation state spawned by cues, not simulation state"),

    /**
     * Cleared. A voice mid-playback is a sound for an event the rewind removed, and audio has
     * no notion of a tick to seek to.
     */
    AudioVoices("audio voices are presentation state and cannot be seeked to a tick"),

    /**
     * Snapped. Camera smoothing is a filter over recent positions; after a rewind those
     * positions are from a future that no longer exists, so the camera jumps to its target
     * rather than easing across a discontinuity.
     */
    CameraSmoothing("camera smoothing filters recent positions, which a rewind invalidates"),

    /**
     * Untouched. Scene2d holds a widget tree with its own focus and animation state, none of
     * it simulated. Rewinding it would close the agent's own inspector mid-call.
     */
    Scene2dUi("UI state is not simulated and rewinding it would disrupt the observer"),

    /**
     * Untouched. A socket is a live connection to another process, which did not rewind.
     * Restoring transport state would desynchronise the peer rather than the local world.
     */
    Sockets("a transport peer did not rewind, so local socket state must not either"),
}

/**
 * A subsystem that [SnapshotExclusion] says must be brought to a defined state after a
 * restore.
 *
 * Registered with [SnapshotService], which calls [onRestored] once per applied restore, after
 * the world is whole. Implemented in the module that owns the subsystem — `udea-render` for
 * particles and the camera, audio for voices — so `udea-core` never learns what a particle is.
 *
 * Only the *cleared* exclusions implement this. An untouched one registers nothing, which is
 * the whole difference between the two.
 */
public interface ExcludedSubsystem {

    /** Which exclusion this subsystem is. Names it in logs and in an agent's rewind report. */
    public val exclusion: SnapshotExclusion

    /**
     * Brings the subsystem to a defined state. Called after a restore has been fully applied,
     * never mid-tick.
     */
    public fun onRestored()
}
