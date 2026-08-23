package dev.wildware.moba.match

/**
 * Fires once for each new match, for a caller that has to redo something when one starts.
 *
 * ## Why anything needs telling
 *
 * A restart is a `SceneManager` swap, and a swap calls `NetIdIndex.reset()`. That resets the
 * allocator but **deliberately not the generation counters**: an id captured before the reset
 * must still read stale, or a reference held across a scene swap would silently resolve to
 * whatever happens to occupy its index in the new scene. So the player's unit comes back on the
 * same dense index and a *different* `NetId` - `MatchProofTest` asserts exactly that.
 *
 * Everything that was handed a `NetId` once, at boot, is therefore pointing at a stale id from
 * the second match onwards. In this game that is the camera (`MobaEntry.follow`) and the audio
 * listener (`MobaAudio.listenTo`), and the symptom is not an error: the camera simply stops
 * following and the view sits wherever the first match left it, which reads as the game having
 * frozen at exactly the moment it restarted.
 *
 * ## Why it is a poll and not a callback on the scene manager
 *
 * `BarrierSceneManager.onSwapped` exists and would work, but it is reached by casting
 * `GameContext.scenes` from the `SceneManager` interface to the implementation, and it fires
 * inside a barrier action - on the simulation thread, at a point where the caller's camera
 * belongs to the render thread. This is read from a frame callback, which is where the things
 * that need re-pointing actually live, and it needs nothing but [MatchService].
 *
 * ```
 * val newMatch = NewMatchSignal(host.ctx[MatchService.KEY])
 * // in the frame callback:
 * if (newMatch.poll()) MobaEntry.follow(rendering, MobaEntry.playerId(host))
 * ```
 *
 * One of these per thing that needs telling: [poll] consumes the edge, so two callers sharing an
 * instance would race for it and one of them would never fire.
 */
public class NewMatchSignal(
    private val service: MatchService,
) {

    /**
     * The last match number this signal reported.
     *
     * `0` and not `1`, so the **first** match is an edge too. A caller that follows the player on
     * a new match should be doing it for match one as well, and a signal that silently skipped
     * the first one would work perfectly from match two onwards - which is a bug that only shows
     * up in a session nobody runs to the end.
     */
    private var reported: Int = 0

    /** Which match this signal has seen. `0` until the first [poll] after a match exists. */
    public val lastSeen: Int get() = reported

    /**
     * True exactly once per match, on the first call after it starts.
     *
     * Safe to call every frame; it is one comparison. Returns false while there is no match at
     * all, which is the tick between a process starting and its scene swap landing.
     */
    public fun poll(): Boolean {
        if (!service.hasMatch) return false
        val current = service.matchNumber
        if (current == reported) return false
        reported = current
        return true
    }

    override fun toString(): String = "NewMatchSignal(lastSeen=$reported)"
}
