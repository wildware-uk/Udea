package dev.wildware.udea.assets.compiler.gen

import dev.wildware.udea.assets.compiler.validate.GltfCheck
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readBytes
import kotlin.math.ceil

/**
 * One animation in a glTF file, as the asset build sees it: where it is, what it is called, and
 * how many ticks it lasts.
 *
 * @property index the position in the file's `animations` array, which is the clip's identity.
 * @property name the file's name for it, or `null` when the file gives none.
 * @property ticks the length at 60Hz, by [GltfClips.ticksOf]'s rule.
 */
internal data class GltfClip(
    val index: Int,
    val name: String?,
    val ticks: Long,
)

/**
 * Reads the animation clips out of a glTF 2.0 file, headless and without Kool (issue #241).
 *
 * The asset build runs before anything draws and on machines with no GL, so it cannot ask the
 * renderer's loader what a model contains. It does not need to: the glTF spec requires every
 * animation sampler's `input` accessor to state its `min` and `max`, so a clip's length is the
 * largest `max` among its samplers, read from the JSON alone. The binary buffer - keyframes,
 * bones, meshes - is never touched.
 *
 * Every failure is a message that names the clip it concerns, returned rather than thrown,
 * because the caller turns it into a located diagnostic.
 */
internal object GltfClips {

    /**
     * The simulation rate a generated length is counted at: `SimClock.DEFAULT_TICK_RATE`, the
     * fixed 60Hz of spec 3.3. Spelled here because this build-time module does not depend on
     * `udea-core`; a game that ran its simulation at another rate would have clips measured in
     * the wrong ticks, and none does.
     */
    const val TICKS_PER_SECOND: Int = 60

    /**
     * How far above a whole number of ticks a length may be and still count as that number.
     *
     * A float32 cannot hold most `n/60` exactly, so a clip authored at exactly 205 ticks is
     * stored as 205.0000048 of them. A thousandth of a tick is 17 microseconds - far below any
     * keyframe spacing anybody authors - and far above float32's error at any plausible length.
     */
    private const val WHOLE_TICK_TOLERANCE: Double = 1e-3

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [seconds] as ticks: times [TICKS_PER_SECOND], rounded **up**, except that a product within
     * [WHOLE_TICK_TOLERANCE] above a whole number is that number.
     *
     * Up, so a clip played once is never finished before its last keyframe has been shown; the
     * cost is at most one tick held on the final pose. Never below zero.
     */
    fun ticksOf(seconds: Float): Long =
        maxOf(0L, ceil(seconds.toDouble() * TICKS_PER_SECOND - WHOLE_TICK_TOLERANCE).toLong())

    /** Every clip in [file], in the file's order, or a failure saying what is wrong. */
    fun read(file: Path): Result<List<GltfClip>> {
        val text = GltfCheck.jsonOf(file.extension.lowercase(), file.readBytes()).getOrElse { return Result.failure(it) }
        val document = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            // `parseToJsonElement` reports malformed input this way; the message below says so.
            null
        } ?: return failure("is not a glTF 2.0 file: its JSON does not parse")
        val accessors = document["accessors"] as? JsonArray ?: JsonArray(emptyList())
        val animations = document["animations"] as? JsonArray ?: return Result.success(emptyList())
        return Result.success(
            animations.mapIndexed { index, animation ->
                val name = ((animation as? JsonObject)?.get("name") as? JsonPrimitive)?.content
                val seconds = lengthOf(animation, accessors).getOrElse { reason ->
                    return failure("has an animation ${describe(index, name)} whose ${reason.message}")
                }
                GltfClip(index, name, ticksOf(seconds))
            },
        )
    }

    /** The largest `max` time among [animation]'s samplers' input accessors. */
    private fun lengthOf(animation: JsonElement, accessors: JsonArray): Result<Float> {
        val samplers = (animation as? JsonObject)?.get("samplers") as? JsonArray
            ?: return failure("samplers are missing")
        var longest = 0f
        for (sampler in samplers) {
            val input = ((sampler as? JsonObject)?.get("input") as? JsonPrimitive)?.intOrNull
                ?: return failure("sampler has no input accessor")
            val accessor = accessors.getOrNull(input) as? JsonObject
                ?: return failure("sampler names accessor $input, which the file does not have")
            val max = ((accessor["max"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)
                ?.takeUnless { it.isString }
                ?.floatOrNull
                ?: return failure("input accessor $input states no numeric max time, which glTF requires")
            if (!max.isFinite() || max < 0f) return failure("input accessor $input ends at $max seconds")
            longest = maxOf(longest, max)
        }
        return Result.success(longest)
    }

    private fun describe(index: Int, name: String?): String = if (name == null) "#$index" else "#$index `$name`"

    /** A failure whose message completes "the file ..." or "... whose", as its caller composes it. */
    private fun <T> failure(why: String): Result<T> = Result.failure(IllegalArgumentException(why))
}
