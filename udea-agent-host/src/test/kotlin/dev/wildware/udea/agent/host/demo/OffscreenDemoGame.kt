package dev.wildware.udea.agent.host.demo

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Matrix4
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.EntityCreateContext
import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.agent.query.AgentComponentType
import dev.wildware.udea.agent.query.agentComponent
import dev.wildware.udea.agent.state.ArchetypeVisitor
import dev.wildware.udea.agent.state.EntityCensus
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.blueprint.Blueprint
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.core.blueprint.BlueprintSpawner
import dev.wildware.udea.core.blueprint.SpawnPlacement
import dev.wildware.udea.core.blueprint.blueprintSpawner
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.core.replication.BitWriter
import dev.wildware.udea.core.replication.ComponentTypeId
import dev.wildware.udea.core.replication.FieldMask
import dev.wildware.udea.core.replication.FieldStore
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.replication.NoSuchFieldIndexException
import dev.wildware.udea.core.replication.Replicator
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.fleksComponentType
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.camera.CameraRig
import dev.wildware.udea.render.draw.DebugDraw
import dev.wildware.udea.render.interp.PoseHistory

/**
 * The world the offscreen demo draws, and the smallest one that can prove anything visually.
 *
 * ## Why the spatial component is `PhysicsBody` and not a component of its own
 *
 * `Phase1Demo` — the headless half — invents a `Position`, which is right for it: it proves the
 * *numbers* half of the surface and a made-up component keeps the fixture honest about being a
 * fixture. This half has to prove the *picture*, and every presentation-side thing that reads a
 * position reads `PhysicsBody`: `Interpolator`, `CameraRig`, `DebugOverlayRenderSystem`. A demo
 * with its own component would have had to reimplement all three, and would then be proving the
 * reimplementation rather than the engine.
 *
 * Only `x`, `y` and `angle` are replicated. Nothing else in this game changes, so a snapshot that
 * carried the whole body would be recording constants — and `time.rewind` restoring exactly the
 * three fields an agent can move is what makes the rewind visible in a screenshot diff.
 */
internal object DemoBodyReplicator : Replicator<PhysicsBody> {

    const val X = 0
    const val Y = 1
    const val ANGLE = 2
    const val FIELD_COUNT = 3

    override val typeId: ComponentTypeId = ComponentTypeId(1)

    override val fieldNames: List<String> = listOf("x", "y", "angle")

    override val netMask: FieldMask = MaskOps.of(X, Y)

    override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override fun capture(component: PhysicsBody, store: FieldStore, slot: Int) {
        store.setFloat(slot, X, component.x)
        store.setFloat(slot, Y, component.y)
        store.setFloat(slot, ANGLE, component.angle)
    }

    override fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask {
        var mask = MaskOps.EMPTY
        for (field in 0 until FIELD_COUNT) {
            if (store.getFloat(slotA, field) != store.getFloat(slotB, field)) {
                mask = MaskOps.set(mask, field)
            }
        }
        return mask
    }

    override fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter) {
        for (field in 0 until FIELD_COUNT) {
            if (MaskOps.test(mask, field)) out.writeFloat(store.getFloat(slot, field))
        }
    }

    override fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask {
        var mask = MaskOps.EMPTY
        for (field in 0 until FIELD_COUNT) {
            store.setFloat(slot, field, src.readFloat())
            mask = MaskOps.set(mask, field)
        }
        return mask
    }

    override fun apply(store: FieldStore, slot: Int, component: PhysicsBody, mask: FieldMask) {
        if (MaskOps.test(mask, X)) component.x = store.getFloat(slot, X)
        if (MaskOps.test(mask, Y)) component.y = store.getFloat(slot, Y)
        if (MaskOps.test(mask, ANGLE)) component.angle = store.getFloat(slot, ANGLE)
    }

    override fun getField(component: PhysicsBody, fieldIndex: Int): Any? = when (fieldIndex) {
        X -> component.x
        Y -> component.y
        ANGLE -> component.angle
        else -> throw NoSuchFieldIndexException("PhysicsBody", fieldIndex, FIELD_COUNT)
    }

    override fun setField(component: PhysicsBody, fieldIndex: Int, value: Any?) {
        val float = requireNotNull(value as? Float) { "PhysicsBody fields are floats, got $value" }
        when (fieldIndex) {
            X -> component.x = float
            Y -> component.y = float
            ANGLE -> component.angle = float
            else -> throw NoSuchFieldIndexException("PhysicsBody", fieldIndex, FIELD_COUNT)
        }
    }
}

/** The snapshot ring's view of the demo world: one component, three fields. */
internal fun demoRegistry(): ComponentRegistry = ComponentRegistry(
    listOf(
        fleksComponentType(
            DemoBodyReplicator,
            ComponentSchema.of(
                DemoBodyReplicator,
                "PhysicsBody",
                listOf(FieldKind.Float, FieldKind.Float, FieldKind.Float),
            ),
            PhysicsBody,
        ) { PhysicsBody() },
    ),
)

/** What `world.query` and `world.set_component_field` can see. `angle` is deliberately read-only. */
internal fun demoBodyAccess(): AgentComponentType = agentComponent(
    name = "PhysicsBody",
    replicator = DemoBodyReplicator,
    componentType = PhysicsBody,
    agentWritableFields = setOf(DemoBodyReplicator.X, DemoBodyReplicator.Y),
)

/** Publishes the spawner on the context, which is where `ctx.blueprints` reads it from. */
internal class DemoBodyModule : UdeaModule {

    var spawner: BlueprintSpawner? = null

    override fun context(builder: GameContextBuilder) {
        builder.blueprintSpawner(checkNotNull(spawner) { "wire the spawner before building" })
    }
}

/** This game's spatial component is [PhysicsBody]. */
internal object BodyPlacement : SpawnPlacement {

    override fun defaultIfAbsent(world: World, entity: Entity) {
        with(world) {
            if (entity.getOrNull(PhysicsBody) == null) entity.configure { it += PhysicsBody() }
        }
    }

    override fun moveTo(world: World, entity: Entity, x: Float, y: Float) {
        with(world) {
            val body = entity[PhysicsBody]
            body.x = x
            body.y = y
        }
    }
}

/** The one thing an agent can create here: a square that shows up in a capture. */
internal object BoxBlueprint : Blueprint {
    override val id: BlueprintId = BlueprintId("box")

    override fun configure(context: EntityCreateContext, entity: Entity) {
        with(context) { entity += PhysicsBody() }
    }
}

/** Counts what Fleks already counts. See `Phase1Demo.DemoCensus` for why that is legitimate. */
internal class BodyCensus(private val world: World) : EntityCensus {

    override val entityCount: Int get() = world.numEntities

    override fun forEachArchetype(visitor: ArchetypeVisitor) {
        visitor.visit("box", entityCount)
    }
}

/**
 * A [PoseHistory] that reports every frame as a restore frame, so the demo never interpolates.
 *
 * `InterpSnapshotSystem` is the real implementation and belongs to a game that moves things at
 * 60Hz. This demo is driven by an agent that pauses, steps and rewinds, and every capture is
 * taken with the loop paused — so interpolation would contribute nothing except a second source
 * of frame-to-frame variation between two screenshots that are supposed to be comparable.
 * Reporting a restore makes `Interpolator` draw at exactly the simulated pose, which is the pose
 * the agent just read out of `world.query`.
 */
internal object SimulatedPoseOnly : PoseHistory {
    override val lastTick: Tick = Tick(-1)
}

/**
 * Draws one filled square per body, in world units, through the camera rig.
 *
 * A one-pixel texture stretched to a quad rather than a sprite from an asset: `udea-assets` has
 * no pipeline pointed at this module, and what is being proved here is that *the world reaches
 * the capture*, not that a texture atlas works.
 */
internal class BodyQuadRenderSystem(
    private val resources: RenderResources,
    private val camera: CameraRig,
) : RenderSystem {

    private var bodies: Family? = null

    private var world: World? = null

    private val pixel: TextureRegion = resources.own(
        Texture(
            Pixmap(1, 1, Pixmap.Format.RGBA8888).apply {
                setColor(Color.WHITE)
                fill()
            },
        ),
    ).let(::TextureRegion)

    override fun onBind(world: World, ctx: GameContext) {
        this.world = world
        bodies = world.family { all(PhysicsBody) }
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        val world = this.world ?: return
        val bodies = this.bodies ?: return
        val batch = resources.batch
        batch.projectionMatrix = camera.camera.combined
        batch.color = Color.WHITE
        batch.begin()
        try {
            with(world) {
                bodies.forEach { entity ->
                    val body = entity[PhysicsBody]
                    batch.draw(pixel, body.x - HALF_SIZE, body.y - HALF_SIZE, SIZE, SIZE)
                }
            }
        } finally {
            batch.end()
        }
    }

    private companion object {
        const val SIZE: Float = 2f
        const val HALF_SIZE: Float = SIZE / 2f
    }
}

/**
 * Draws a grid, but only while [DebugDraw] is on.
 *
 * Its whole reason to exist is that `render.toggle_debug_draw` has to be observable in a
 * *capture*: a tool that returned `{"debugDraw":true}` while the picture never changed would be
 * reporting a field, not a behaviour. Registered at `RenderPhase.Debug`, which is before the
 * capture point on purpose (spec 3.7) — debug shapes are information about the game world and an
 * agent asking for a screenshot should get them.
 */
internal class DebugGridRenderSystem(
    private val resources: RenderResources,
    private val camera: CameraRig,
    private val debug: DebugDraw,
) : RenderSystem {

    private val projection = Matrix4()

    private val pixel: TextureRegion = resources.own(
        Texture(
            Pixmap(1, 1, Pixmap.Format.RGBA8888).apply {
                setColor(Color.WHITE)
                fill()
            },
        ),
    ).let(::TextureRegion)

    override fun render(target: OffscreenTarget, alpha: Float) {
        if (!debug.enabled) return
        projection.setToOrtho2D(0f, 0f, target.width.toFloat(), target.height.toFloat())
        val batch = resources.batch
        batch.projectionMatrix = projection
        batch.color = GRID_COLOUR
        batch.begin()
        try {
            var x = 0
            while (x < target.width) {
                batch.draw(pixel, x.toFloat(), 0f, 1f, target.height.toFloat())
                x += SPACING
            }
            var y = 0
            while (y < target.height) {
                batch.draw(pixel, 0f, y.toFloat(), target.width.toFloat(), 1f)
                y += SPACING
            }
        } finally {
            batch.end()
            batch.color = Color.WHITE
            // The camera's projection is restored by the next frame's rig, but the batch colour
            // is not: leaving it tinted would dim everything drawn after this in the same frame,
            // which would make "debug draw off" and "debug draw on" differ in more than the grid.
        }
    }

    private companion object {
        const val SPACING: Int = 16
        val GRID_COLOUR: Color = Color(0f, 1f, 0f, 1f)
    }
}
