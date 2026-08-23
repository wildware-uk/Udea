package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentToolArg
import dev.wildware.udea.agent.AgentToolDef
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.reflect.KClass

/** Error kinds this module's tools answer with. Open by design; see `AgentErrorKind`. */
public object AgentHostErrors {

    /** An artifact id names nothing this process stored. */
    public val ARTIFACT_NOT_FOUND: AgentErrorKind = AgentErrorKind("artifact_not_found")

    /** Two artifacts were compared and their dimensions differ. */
    public val ARTIFACT_SIZE_MISMATCH: AgentErrorKind = AgentErrorKind("artifact_size_mismatch")

    /** An artifact is recorded by the store but could not be decoded - truncated, or not an image. */
    public val ARTIFACT_UNREADABLE: AgentErrorKind = AgentErrorKind("artifact_unreadable")

    /** No artifact store is wired, so nothing can be stored or compared. */
    public val NO_ARTIFACT_STORE: AgentErrorKind = AgentErrorKind("no_artifact_store")

    /**
     * A capture was asked for and no frame came back.
     *
     * Deliberately distinct from [NO_RENDER_CONTEXT]: that one means the toolset is not live in
     * this process and the remedy is to stop calling it, this one means the toolset *is* live and
     * this particular frame did not arrive - the render loop died, the driver refused the read.
     * An agent that could not tell them apart would give up on screenshots after one bad frame.
     */
    public val CAPTURE_FAILED: AgentErrorKind = AgentErrorKind("capture_failed")

    /**
     * The renderer draws with a fixed projection: there is no camera to move.
     *
     * Distinct from [NO_RENDER_CONTEXT] because the toolset *is* live - `screenshot` works and
     * returns real pixels - and only the view is immovable. An agent told `no_render_context`
     * would stop taking screenshots, which is the opposite of the right move: it should keep
     * capturing and stop trying to aim.
     */
    public val NO_CAMERA_BOUND: AgentErrorKind = AgentErrorKind("no_camera_bound")

    /**
     * A camera exists but was never bound to a world, so it can never resolve a follow target.
     *
     * A different remedy from [NO_CAMERA_BOUND] and a different person's problem: the camera was
     * built and handed to the renderer without being registered with the `RenderRegistry` the
     * pipeline was assembled from. `set_camera` still works on such a rig; only following cannot.
     */
    public val CAMERA_NOT_BOUND: AgentErrorKind = AgentErrorKind("camera_not_bound")

    /**
     * The entity exists, but nothing gives it a position the camera could track.
     *
     * The failure this kind exists for is the one `moba` documented against itself: a unit with
     * no `PhysicsBody` has no drawn pose, `render.follow_entity` answered `ok`, and the camera
     * sat exactly where it was. `no_such_entity` would have been a lie - the entity is there -
     * and `ok` was a worse one.
     */
    public val ENTITY_NOT_FOLLOWABLE: AgentErrorKind = AgentErrorKind("entity_not_followable")

    /**
     * This process has no GL context, so the render toolset is not live.
     *
     * The exact token matters: `RenderUnavailable.NoRenderContext.code` is the same string, and a
     * model that has learned it must keep getting it from every producer.
     */
    public val NO_RENDER_CONTEXT: AgentErrorKind = AgentErrorKind("no_render_context")
}

/**
 * The `render.compare_artifacts` tool: diffing two captures without leaving the process.
 *
 * ## Why the diff is engine-side
 *
 * The Phase 1 demo ends "...screenshots again, diffs the images, then inspects the entity whose
 * health changed", and both candidate owners had disclaimed the diff as "an agent-side concern",
 * so nobody owned the last step of the demo. Engine-side is also the cheaper answer: the images
 * are already in the artifact store, so a diff costs one tool call rather than two artifact
 * downloads through the bridge, and what the agent actually wants - did anything change, and
 * where - is a handful of scalars rather than two megabytes of PNG.
 *
 * ## No GL, deliberately
 *
 * Decoding and comparing is `javax.imageio` and arithmetic. So this tool works in
 * `RenderMode.Headless`, where *capture* answers `no_render_context`, and a CI job can diff two
 * artifacts that were produced somewhere else entirely.
 *
 * ## Nothing thrown reaches the HTTP layer
 *
 * A truncated PNG is the realistic way to make a decoder throw, and a tool that threw into the
 * dispatcher would land as `tool_threw` with a stack trace in it. Every failure here is a typed
 * [AgentResult.Failed] instead, and `completedCommandId` advances for a failure exactly as for a
 * success - which is what stops a bridge reporting a healthy game as frozen.
 */
public class ArtifactToolset(
    /** Where the images come from and where the visualisation goes. */
    private val artifacts: AgentArtifacts?,
) {

    /**
     * Compares the artifacts [a] and [b].
     *
     * @param tolerance a per-channel delta at or below this counts as equal. `0` by default,
     *   because the capture path is supposed to be deterministic and a tolerance that were on by
     *   default would hide the day it stopped being.
     */
    public fun compareArtifacts(a: String, b: String, tolerance: Int): AgentResult {
        val store = artifacts ?: return AgentResult.failed(
            AgentHostErrors.NO_ARTIFACT_STORE,
            "this instance has no artifact store, so there is nothing to compare",
        )
        if (tolerance < 0) {
            return AgentResult.failed(
                AgentErrorKind.BAD_ARGUMENT,
                "tolerance is a channel delta of 0..255, was $tolerance",
            )
        }
        val left = decode(store, a)
        left.failure?.let { return it }
        val right = decode(store, b)
        right.failure?.let { return it }
        val first = left.image ?: return notFound(store, a)
        val second = right.image ?: return notFound(store, b)

        if (first.width != second.width || first.height != second.height) {
            return AgentResult.failed(
                AgentHostErrors.ARTIFACT_SIZE_MISMATCH,
                "$a is ${first.width}x${first.height} and $b is ${second.width}x${second.height}; " +
                    "a per-pixel diff needs equal dimensions",
            )
        }

        val report = ImageDiff.compare(first, second, tolerance)
        // No diff artifact when there is nothing to look at: writing a uniformly dimmed copy of
        // an unchanged frame would spend a store slot to tell the agent what `identical` already did.
        val diffId = if (report.identical) null else writeVisualisation(store, first, second, tolerance)

        return AgentResult.ok {
            put("identical", report.identical)
            put("differentPixels", report.differentPixels)
            put("fraction", report.fraction.toFloat())
            put("maxChannelDelta", report.maxChannelDelta)
            obj("bbox") {
                put("x", report.bbox.x)
                put("y", report.bbox.y)
                put("w", report.bbox.w)
                put("h", report.bbox.h)
            }
            put("diffArtifactId", diffId?.value)
        }
    }

    /**
     * One decode attempt.
     *
     * Three outcomes, not two, and they are genuinely different answers: the image, a typed
     * failure explaining why a *stored* artifact could not be read, and "neither" - which means
     * the store never had it, and only the caller knows which of the two ids that was.
     * [AgentResult] cannot express this: it is a sealed interface in another module, so a third
     * case cannot be added to it from here, and that is the correct restriction rather than an
     * obstacle - `no such artifact` is not a result of the tool, it is a reason it has none.
     */
    private class Decoded(val image: RgbaImage?, val failure: AgentResult.Failed?) {
        override fun toString(): String = "Decoded(${image ?: failure ?: "absent"})"
    }

    private fun decode(store: AgentArtifacts, raw: String): Decoded {
        val id = ArtifactId.parse(raw) ?: return ABSENT
        val artifact = store.get(id) ?: return ABSENT
        return try {
            val image = ImageIO.read(artifact.path.toFile())
                ?: return unreadable(
                    "$raw (${artifact.mediaType}) is not an image any installed decoder reads",
                )
            Decoded(RgbaImage.of(image), null)
        } catch (e: IOException) {
            unreadable("$raw could not be decoded: ${e.javaClass.simpleName}: ${e.message}")
        } catch (e: IllegalArgumentException) {
            unreadable("$raw decoded to something that is not an image: ${e.message}")
        }
    }

    private fun unreadable(message: String): Decoded =
        Decoded(null, AgentResult.failed(AgentHostErrors.ARTIFACT_UNREADABLE, message))

    private fun notFound(store: AgentArtifacts, raw: String): AgentResult {
        val id = ArtifactId.parse(raw)
        val evicted = id != null && store.wasEvicted(id)
        return AgentResult.failed(
            AgentHostErrors.ARTIFACT_NOT_FOUND,
            when {
                id == null -> "$raw is not an artifact id; ids are cap_ followed by digits"
                evicted -> "$raw was dropped by the artifact LRU; capture again"
                else -> "$raw was never stored by this process"
            },
        )
    }

    private fun writeVisualisation(
        store: AgentArtifacts,
        a: RgbaImage,
        b: RgbaImage,
        tolerance: Int,
    ): ArtifactId? {
        val image = ImageDiff.visualise(a, b, tolerance)
        val bytes = encodePng(image) ?: return null
        return store.put(bytes, AgentArtifacts.PNG)
    }

    private fun encodePng(image: BufferedImage): ByteArray? = try {
        val out = ByteArrayOutputStream()
        if (ImageIO.write(image, "png", out)) out.toByteArray() else null
    } catch (e: IOException) {
        System.err.println("[udea-agent-host] could not encode the diff visualisation: ${e.message}")
        null
    }

    override fun toString(): String = "ArtifactToolset($artifacts)"

    private companion object {
        /** The store held nothing under that id. */
        val ABSENT = Decoded(null, null)
    }
}

/**
 * The hand-written [AgentToolDef] for `render.compare_artifacts`.
 *
 * Hand-written rather than emitted by `@AgentTool` because this module has no KSP round: the
 * codegen processor is wired for engine modules that declare toolsets, and adding one here to
 * generate a single declaration would put a build-time dependency on the debug host for no
 * behaviour. The shape is exactly what the processor emits - `docs/contracts/agent-tools.md` is
 * the written form of it - so replacing this with a generated one later is a deletion, not a
 * migration.
 */
public object CompareArtifactsTool : AgentToolDef<ArtifactToolset> {

    override val name: String = "render.compare_artifacts"

    override val description: String =
        "Compare two stored screenshots pixel by pixel and report whether, how much and where " +
            "they differ. Reach for it after capturing the same scene twice - before and after a " +
            "command, or across a rewind - when you need to know if anything changed on screen " +
            "without downloading both images. Returns scalars plus the id of a diff image with " +
            "the changed pixels highlighted. Works with no GL context, so it is available in " +
            "Headless where screenshot is not."

    override val args: List<AgentToolArg> = listOf(
        AgentToolArg(
            name = "a",
            type = "string",
            description = "Artifact id of the first image, as returned by a screenshot tool, e.g. cap_0001.",
            required = true,
            default = null,
        ),
        AgentToolArg(
            name = "b",
            type = "string",
            description = "Artifact id of the second image. Must have the same dimensions as a.",
            required = true,
            default = null,
        ),
        AgentToolArg(
            name = "tolerance",
            type = "integer",
            description = "Per-channel delta, 0-255, at or below which two pixels count as equal. " +
                "Use 0 for an exact comparison; raise it only to suppress known driver noise.",
            required = false,
            default = "0",
        ),
    )

    /**
     * Derived from [args], not written beside them.
     *
     * It was a second literal, and it had drifted from the shape `udea-codegen` emits in four
     * ways at once - no dialect, no `additionalProperties`, and descriptions that were a
     * paraphrase of the argument text rather than the argument text. See [ToolSchema].
     */
    override val inputSchema: String = ToolSchema.of(args)

    override val owner: KClass<*> = ArtifactToolset::class

    override fun invoke(receiver: ArtifactToolset, command: AgentCommand): Any? =
        receiver.compareArtifacts(
            a = command.str("a"),
            b = command.str("b"),
            tolerance = command.int("tolerance", 0),
        )
}
