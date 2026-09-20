# Rendering with Kool

Udea draws with [Kool](https://github.com/fabmax/kool), and all of that code lives in one module,
`udea-render`. The simulation never draws: drawing code runs after a tick, reads the world, and
writes nothing back. So the same game runs with a window, with a hidden window for screenshots, or
with no graphics at all.

Think of the simulation as a stage play and the renderer as a camera crew. The crew films the play
but never walks on stage. Send the crew home and the play still runs the same way.

## The one rule: no graphics outside `udea-render`

Every GL and Kool call is inside `udea-render`. `udea-core`, the kernel, has no GL on its compile
classpath at all. The module graph gate (`sh gradlew udeaVerifyModuleGraph`, rule ids in
`docs/module-graph.md`) fails the build if a GL or Kool library leaks into another module. A game
module such as `moba:game` depends on `udea-render` and uses its types, but never names Kool itself.

## Three render modes

A game picks a `RenderMode` (`udea-core`, `dev.wildware.udea.core.host.RenderMode`) when it starts.
All three run the identical simulation. The only difference is whether anything is drawn.

| Mode | GL context | Window | Screenshots | Used by |
|---|---|---|---|---|
| `Headless` | none | none | answer `no_render_context` | dedicated server, tests, fast-forward |
| `Offscreen` | real | hidden | yes | the agent, by default |
| `Windowed` | real | visible | yes | a player |

`Offscreen` and `Windowed` are the same code with one difference: whether the window is shown.
`KoolBackend` (`udea-render/src/jvmMain/kotlin/dev/wildware/udea/render/backend/KoolBackend.kt`)
serves both, and its KDoc states there is no `if (offscreen)` anywhere in it. `KoolBackend.start`
throws `IllegalArgumentException` for `Headless`, because a headless game has no context to start.

A running game reports its mode on `/health`, so an agent knows whether screenshots will work
before it asks for one. See [Agent Tool Surface](Agent-Tool-Surface).

## Drawing systems are not ECS systems

A simulation system extends `SimSystem` (a Fleks system) and runs inside the tick. A drawing system
implements `RenderSystem` instead, and Fleks never sees it:

```kotlin
// udea-render/src/commonMain/kotlin/dev/wildware/udea/render/RenderSystem.kt
public interface RenderSystem {
    public fun onBind(world: World, ctx: GameContext) {}
    public fun render(target: OffscreenTarget, alpha: Float)
}
```

- `onBind` runs once, when the pipeline is built, before the first frame. Look up your Fleks
  families here and keep them in a field. `FamiliesResolvedInOnBindTest` in `udea-render` scans the
  compiled bytecode of its render systems and fails if `render` calls `World.family`.
- `render` runs once per frame. It gets the target to draw into and `alpha`, the interpolation
  fraction (below). It gets no `Tick` and no seconds, so it cannot advance game state.

Because drawing systems are not in the world's system list, `world.update()` is pure simulation by
construction. `PureSimulationTest` and `NoRenderSystemIsAFleksSystemTest` in `udea-render` hold that
line.

A system that needs the window size implements `Resizable` as well, and gets `resize(width, height)`
on the render thread, outside a frame.

### Registering what you draw

A game lists its drawing systems in a `RenderRegistry`, then hands the registry to the backend.
This is `moba`'s, trimmed
(`moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaScene.kt`):

```kotlin
val registry = RenderRegistry()
val camera = CameraRig(
    netIds = definition.core.netIds,
    poses = PositionPoses,
    frameTime = registry.frameTime,
    worldWidth = WORLD_WIDTH,
    worldHeight = WORLD_HEIGHT,
)
registry.register(RenderPhase.PreRender, { camera })
val ground = registry.register(
    RenderPhase.World,
    { resources -> BackgroundRenderSystem(resources, camera) },
)
val lane = registry.register(
    RenderPhase.World,
    { resources -> LaneRenderSystem(resources, camera) },
) { after(ground) }
```

Each registration is a factory. The factory gets a `RenderResources`, which holds the shared sprite
batch (`resources.batch`) and the capturable target (`resources.offscreen`). The factory runs on the
render thread when the pipeline is built, so a system can take what it needs as constructor
parameters.

The factory is the **second** argument, not a trailing lambda. The trailing lambda is the ordering
block (`after(...)`, `before(...)`). `MobaScene`'s comments warn about this: a factory written as a
trailing lambda registers nothing.

### Phases and order

`RenderPhase` (`udea-render/src/commonMain/kotlin/dev/wildware/udea/render/RenderPhase.kt`) sets the
coarse order of a frame:

1. `PreRender`: camera update and frame setup. Draws little or nothing.
2. `World`: the simulated world. Most drawing goes here.
3. `UI`: screen-space game interface, such as a HUD.
4. `Debug`: collision shapes and other developer drawing. Still captured.
5. `Overlay`: only for `OverlaySystem`s (below). Never captured.

Within one phase, `before`/`after` constraints set the order; ties go by registration order. A
constraint cannot cross phases, and a cycle fails `RenderRegistry.build` with `RenderOrderException`.

## Drawing 2D

`SpriteBatch2D` (`udea-render/src/commonMain/kotlin/dev/wildware/udea/render/draw/SpriteBatch2D.kt`)
is the 2D batch. The main calls:

- `begin(projection)`: start drawing in world units, through a camera's `Projection2D`.
- `beginPixels()`: start drawing in the target's own pixels.
- `draw(region, x, y, width, height, tint, rotationDegrees, originX, originY, flipX, flipY)`: draw a
  texture region.
- `fill(x, y, width, height, tint)`: draw a solid rectangle.
- `end()`.

This is the shape of `moba`'s `LaneRenderSystem`, which draws the lane and towers with no art at all
(`moba/game/src/commonMain/kotlin/dev/wildware/moba/lane/LaneRender.kt`):

```kotlin
override fun onBind(world: World, ctx: GameContext) {
    this.world = world
    towers = world.family { all(Tower, Position) }
}

override fun render(target: OffscreenTarget, alpha: Float) {
    val world = this.world ?: return
    val towers = this.towers ?: return
    drawnTowers = 0
    batch.begin(camera.projection)
    try {
        drawLane()
        // ... one fill per tower
    } finally {
        batch.end()
    }
}
```

The engine also ships ready-made systems in
`udea-render/src/commonMain/kotlin/dev/wildware/udea/render/draw/`: `SpriteRenderSystem` (entities
with a `SpriteRenderer`), `AnimationRenderSystem` (advances `SpriteAnimation` on wall time, through
`FrameTime`), `BackgroundRenderSystem` and `DebugOverlayRenderSystem`. For 3D models, see
[Models and Animation](Models-and-Animation).

A system that allocates its own render resource (a texture, a font) hands it to
`resources.own(...)`. The pipeline then releases it, in reverse order, when it is disposed.

## Interpolation: smooth drawing between ticks

The simulation ticks 60 times a second. The screen may draw at 144. Between two ticks the renderer
is handed `alpha`, a number from 0 to 1 that says how far this frame is between the last tick and
the next. Drawing an entity at `lerp(previous, current, alpha)` removes the judder.

`RenderModule` adds two small **simulation** systems that record where things were at a tick
boundary, because that value exists only at that moment:

- `InterpSnapshotSystem` records `PhysicsBody` poses into an `Interp` component, in
  `SimPhase.PreSimulation` — the start of a tick, and before `TeleportSystem`.
- `Interp3DSnapshotSystem` records `Transform3D` poses into `Interp3D`, in `SimPhase.Cleanup` — the
  end of a tick (issue #246).

They draw nothing and write only their own presentation components, which the simulation never
reads. `Interpolator` (2D) and `Interpolator3D` read them back while drawing. A game whose entities
use its own position component supplies a `PoseSource`; see [Cameras](Cameras).

`moba`'s units carry their own `Position` and no physics body, so `moba` does not interpolate: its
`PositionPoses` returns the simulated position and ignores `alpha`. Its KDoc says so, and says what
closing that would cost.

## Screenshots and the capture point

Every frame is drawn into an offscreen target first. That target is what a screenshot reads, and it
is then shown in the window. So a screenshot is exactly the frame a player sees.

The frame, in order (`RenderPipeline.render`):

1. Every `RenderSystem`, phase by phase, into the `OffscreenTarget`.
2. **The capture point.** A pending screenshot claims this frame.
3. Editor views, if the editor is open. They draw into passes of their own (issue #234).
4. The frame is presented to the window.
5. Every `OverlaySystem`, onto the `ScreenTarget`.

### Why overlays are never in a screenshot

An agent checks its work by taking a screenshot, acting, and taking another. If the agent's own
status panel were in the picture, it would think the game changed when only its caption did. So the
type system keeps overlays out:

- An `OverlaySystem` draws onto a `ScreenTarget`. No capture ever reads a `ScreenTarget`, and
  nothing outside the module can construct one or convert it.
- Its factory gets `OverlayResources`, which holds no capturable target at all.
- `RenderRegistry.register` refuses `RenderPhase.Overlay`. Overlays go through
  `RenderRegistry.overlay(...)`, which has no phase argument.
- An overlay's `render` gets `dtSeconds` (wall time), not `alpha` or a `Tick`, so it cannot read
  simulation time.

The same idea splits the game interface in two. See [UI with ComposeGL](UI-with-ComposeGL): a
`CapturedUi` HUD is in every screenshot, and a `UiLayer` menu is in none.

A capture in code: `PresentationControl.capture(region, afterTick)` returns a `Deferred` that
completes with the PNG bytes of the first frame drawn after that tick. `moba`'s `MobaShot`
(`moba/desktop/src/main/kotlin/dev/wildware/moba/entry/MobaShot.kt`) uses it for
`sh gradlew :moba:desktop:runShot`. An agent uses `render.screenshot`.

![A captured moba frame](images/moba-lane-clash.png)

*A captured `moba` frame: the ground, the lane and towers, the units and the HUD are all in the
capture, because they draw before the capture point.*

## The render thread

Kool's frame loop owns its thread. `KoolThread`
(`udea-render/src/jvmMain/kotlin/dev/wildware/udea/render/backend/KoolThread.kt`) starts one thread,
named `udea-kool`, and it is the only thread that touches Kool, GL or the ComposeGL toolkit. Two
doors lead in:

- `KoolBackend.onRenderThread { ... }` runs a block there and waits for the result. Use it for
  anything that creates a render object, such as a `KoolKeyboard`.
- `KoolBackend.drive(host)` installs the per-frame callback that steps the game and draws.

Two facts about Kool that shape the engine:

- **One Kool context per process.** Kool refuses a second context even after the first has closed. A
  test suite that needs a context per test class forks a JVM per class.
- **Input and drawing share the thread.** `KoolThread` sets `asyncSceneUpdate = false`, so input
  polling, frame callbacks and drawing all run on the render thread. `GlKoolInputTest` checks it.

A typical start-up, from `KoolBackend`'s own KDoc:

```kotlin
val backend = KoolBackend.start(RenderMode.Offscreen, WindowConfig(), registry)
val host = GameHost(RenderMode.Offscreen, definition, backend)   // PresentationFactory
backend.drive(host)                                              // frames start flowing
// ...
backend.close()
```

Construct, *then* drive. A `GameHost` builds its presentation inside its own constructor, so the
presentation cannot be handed the host it does not yet have. `KoolBackend.start` throws
`GlContextException` when no context can be made — no display, no driver, no natives. It fails
loudly rather than drawing into nothing.

## Platforms

`udea-render` targets `jvm` and `android` (the `udea.kotlin-multiplatform-render` convention). Kool
has no iOS backend and publishes no wasmJs artifact, so nothing that draws has those targets.
`moba`'s Android launcher boots the simulation headless, because `udea-render` has no Android Kool
backend yet. See [Architecture](Architecture).

## Testing graphics

The GL tests are `udeaGlTest` (`udea-render`), `udeaAgentGlTest` (`udea-agent-host`) and
`udeaEditorGlTest` (`udea-editor`). `check` runs them, but **with no display they skip**, and the
build stays green. To run them for real on a machine with no screen:

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true
```

`-Pudea.render.requireGl=true` turns a skip into a failure. See
[Build and Verification](Build-and-Verification).

## See also

- [Models and Animation](Models-and-Animation) — the 3D model pass.
- [UI with ComposeGL](UI-with-ComposeGL) — HUDs and menus.
- [Cameras](Cameras) — `CameraRig`, `ThirdPersonRig`, `PoseSource` and the editor camera.
- [Tick Model and Determinism](Tick-Model-and-Determinism) — why seconds exist only here.
- [Agent Tool Surface](Agent-Tool-Surface) — the `render.*` tools.
- [Getting Started](Getting-Started) — how to run the examples.
