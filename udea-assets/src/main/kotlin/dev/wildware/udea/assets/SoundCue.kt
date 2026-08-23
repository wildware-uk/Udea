package dev.wildware.udea.assets

/**
 * One sound event: the files that can play for it, and how much to vary them.
 *
 * The old `SoundCue` carried a `by lazy { gameManager.assetManager.getAsset<Sound>(it) }`
 * (`common/.../audio.kt`), so an asset value held live LibGDX `Sound` handles and reached a global
 * `gameManager` to get them. Here it holds paths; the audio system holds handles.
 *
 * Several [sounds] means "pick one" - the standard trick for keeping a footstep from sounding
 * identical fifty times. *Which* one is picked is a decision for the caller, and a simulation-
 * visible one must come from `RngService` rather than from anything here (standards section 1: no
 * unseeded randomness in simulation code).
 */
public data class SoundCue(
    override val id: AssetId,
    public val sounds: List<ResPath>,
    /** Fraction either side of unit pitch, so `0.1` means 0.9x to 1.1x. `0` plays it as recorded. */
    public val pitchVariance: Float = 0.0F,
    /** Linear gain. `1` is the file as mastered. */
    public val volume: Float = 1.0F,
) : AssetData {

    init {
        require(sounds.isNotEmpty()) { "sound cue '$id' names no sound files" }
        require(pitchVariance >= 0F && pitchVariance < 1F) {
            "sound cue '$id' has pitchVariance $pitchVariance; it is a fraction either side of " +
                "unit pitch, so 1 or more would allow a pitch of zero or below"
        }
        require(volume >= 0F && volume.isFinite()) { "sound cue '$id' has volume $volume" }
    }
}
