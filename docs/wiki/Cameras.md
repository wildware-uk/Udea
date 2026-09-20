# Cameras

A camera in Udea is presentation, never simulation. It lives in `udea-render`, it reads the world and
writes nothing into it, and a dedicated server has no camera at all. There are three: `CameraRig` for
a 2D game, `ThirdPersonRig` for a 3D one, and `EditorCamera` for the editor's Scene tab.

The rule behind all three is one sentence: **where somebody is looking is not part of the game
state.** If it were, a headless server would have to track a viewport nobody looks through, and a
snapshot would either carry it — meaningless on another machine — or disagree with the world it was
captured with. Both `CameraRigTest` and `ThirdPersonRigTest` tick a world with the rig present and
absent and assert the world hashes match, so this is a checked property rather than an intention.

## CameraRig: the 2D camera

`CameraRig` (`udea-render/src/commonMain/kotlin/dev/wildware/udea/render/camera/CameraRig.kt`) is a
`RenderSystem`. Register it in `RenderPhase.PreRender` so it places the camera before anything is
drawn through it, and hand the same instance to every world renderer
(`moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaScene.kt`):

```kotlin
val camera = CameraRig(
    netIds = definition.core.netIds,
    poses = PositionPoses,
    frameTime = registry.frameTime,
    worldWidth = WORLD_WIDTH,
    worldHeight = WORLD_HEIGHT,
)
registry.register(RenderPhase.PreRender, { camera })
```

What it gives a renderer:

- `camera.projection` — a `Projection2D`, world units to target pixels, as of the last frame.
  This is what you hand to `SpriteBatch2D.begin(...)`.
- `camera.camera` — the `Camera2D` itself: `position.x`, `position.y`, `zoom`.
- `camera.viewport` — an `ExtendViewport` built from `worldWidth` and `worldHeight`. A wider window
  shows **more world**, not a stretched one.

What you set on it:

| Field | What it does |
|---|---|
| `target: NetId?` | The entity to follow, or `null` to leave the camera alone. |
| `offsetX`, `offsetY` | World-space offset from the followed entity. |
| `followHalfLife` | Seconds to close half the distance to the target. `0f` follows exactly. |
| `bounds: CameraBounds?` | Keeps the visible area inside a rectangle, or `null` for an unbounded level. Clamped *after* smoothing, so the camera settles against an edge. |

And three calls: `snapToTarget()` puts the camera on the target at once, with no easing;
`requestLookAt(x, y, zoom)` and `requestFollow(netId)` are the thread-safe versions, applied at the
top of the next frame. An agent's `render.set_camera` arrives that way.

### Following needs a PoseSource

`CameraRig` is built with a `PoseSource`: the thing that answers "where is this entity drawn right
now". It takes one rather than reading a fixed component, because games differ.

- A game with physics bodies passes `Interpolator`, which reads `PhysicsBody` through the `Interp`
  component `InterpSnapshotSystem` fills in.
- `moba` units carry their own `Position` and no body, so `moba` passes `PositionPoses`, an
  eight-line object of its own.

This is not a nicety. Before it, `moba`'s `render.follow_entity` answered `{"following": <id>}` and
moved the camera nowhere, because the rig was reading a component the game did not have.
`followability(netId)` now returns a `CameraOutcome` — `APPLIED`, `NO_CAMERA`, `CAMERA_UNBOUND`,
`UNKNOWN_ENTITY` or `UNFOLLOWABLE` — so a refusal says which.

## ThirdPersonRig: the 3D camera

`ThirdPersonRig` (`.../camera/ThirdPersonRig.kt`, issue #248) sits behind and above the entity it
follows, at a fixed distance, turned by the mouse. It moves a `ModelCamera`; hand the same
`ModelCamera` to `ModelRenderSystem` and the models are drawn through it. Its own KDoc gives the
wiring:

```kotlin
val camera = ModelCamera()
lateinit var rig: ThirdPersonRig
registry.register(RenderPhase.PreRender, { r -> ThirdPersonRig(r, netIds, registry.frameTime, camera).also { rig = it } })
registry.register(RenderPhase.World, { r -> ModelRenderSystem(r, camera, light) })
// Once the backend is up, on the render thread:
val pointer = KoolPointer(ui)
rig.motion = pointer
rig.buttons = pointer
rig.target = playerNetId
```

### The frame

Z is up and the ground is the XY plane, as `Transform3D` has it.

- `yawDegrees` — the direction the camera faces across the ground, counter-clockwise from +X.
  Default 90, which faces +Y.
- `pitchDegrees` — how far it looks down. Default 20, kept within `minPitchDegrees` (default -5) and
  `maxPitchDegrees` (default 75). Both limits stay strictly inside straight up and down, where "up"
  on the screen would be along the line of sight.
- `distance` — world units from the eye to the focus. Default 6.
- `focusHeight` — world units above the target's position the camera looks at, so it frames a chest
  rather than a pair of feet. Default 1.
- `followHalfLife` — seconds for the focus to close half the distance to a moved target. Default
  0.1.

The camera looks at its **focus**: the followed entity's `Transform3D` position raised by
`focusHeight`. The focus eases after the target; the turn does not ease, because a view that lags the
hand turning it reads as a slow mouse. A new target is framed at once rather than eased to.

Where the target is comes from the same `ModelPlacer` `ModelRenderSystem` draws with, at the same
render alpha, so the model and the middle of the picture cannot drift apart.

### Turning it with the mouse

`motion` is a `PointerMotion` — how far the mouse moved since the last frame — and `buttons` is a
`PointerState`. `KoolPointer` is both. `degreesPerPixel` (default 0.2) scales the turn.

`turnButton` decides when the mouse turns the view: `null` means whenever it moves, which suits a
captured cursor; a button number means only while that button is held, which suits a cursor that is
also used to point. Kool numbers them `0` left, `1` right, `2` middle. Hollow uses the right button
(`hollow/desktop/src/main/kotlin/dev/wildware/hollow/desktop/HollowLaunch.kt`).

The motion is spent every frame whether it was used or not, so motion made with the button up does
not land the moment it goes down. See [Input](Input).

### Camera-relative movement

The rig exposes its facing on the ground as four plain floats: `forwardX`, `forwardY`, `rightX`,
`rightY`. That is how W means "away from the camera" instead of "north".

**Read them where the game samples input, never in a simulation system.** The intent is what gets
recorded, replayed and sent to a server, so the turn from screen axes to world axes belongs in the
`IntentSource`. Hollow's `CameraRelativeIntent`
(`hollow/game/src/commonMain/kotlin/dev/wildware/hollow/render/CameraRelativeIntent.kt`) is the whole
of it:

```kotlin
override fun sample(into: Intent) {
    device.sample(into)
    val turning = rig() ?: return
    val screenX = into.axisX(HollowControls.MOVE_AXIS)
    val screenY = into.axisY(HollowControls.MOVE_AXIS)
    // Nothing held: leave the axis at the zero the device wrote.
    if (screenX == 0f && screenY == 0f) return
    into.setAxis(
        HollowControls.MOVE_AXIS,
        turning.forwardX * screenY + turning.rightX * screenX,
        turning.forwardY * screenY + turning.rightY * screenX,
    )
}
```

A simulation system that read the camera would be reading presentation state a server and a replay do
not have.

## Smoothing, and why it is a half-life

Both rigs ease the same way, and neither uses the obvious form. `position += (target - position) *
0.1f` per frame makes a camera twice as tight at 120Hz as at 60Hz, so a game tuned on one machine
feels wrong on another.

Instead the step is `1 - 2^(-dt / halfLife)`: after `followHalfLife` seconds the camera has closed
half the remaining distance, whatever the frame rate. Halving the frame time halves the step, so two
frames at 120Hz move the camera exactly as far as one frame at 60Hz. The shared function is
`halfLifeStep` in `CameraMath.kt`, internal to `udea-render`, with `wrapDegrees` beside it — which
folds an angle into (-180, 180] so a view turned round and round keeps its precision.

Both rigs take a `FrameTime` and read `frameSeconds`. Seconds, not ticks: this is a wall-time
behaviour. See [Tick Model and Determinism](Tick-Model-and-Determinism).

## EditorCamera: the Scene tab

`EditorCamera` (`udea-render/src/commonMain/kotlin/dev/wildware/udea/render/view/EditorCamera.kt`) is
the editor's own look at the world, apart from the game's. A person moving it changes only what the
Scene tab shows: nothing in it is a `Tick`, nothing reaches a snapshot or a capture.

It holds two cameras, because a world can be drawn both ways at once, and `dimension`
(`ViewDimension.TwoD` or `ThreeD`) says which one a drag moves.

- **2D:** `camera2D` and an `ExtendViewport` — the same two things `CameraRig` projects with, so a
  Scene view at the game's position and zoom frames exactly what the Game view frames. It copies them
  off the game's rig on the first frame a Scene view is drawn. `pan(dxPixels, dyPixels)` and
  `zoomAt(factor, viewX, viewY)` move it.
- **3D:** an orbit. The eye sits `distance` from `(targetX, targetY, targetZ)`, turned `yawDegrees`
  about Z and raised `pitchDegrees`. `orbit(yawDelta, pitchDelta)` and `dolly(factor)` move it;
  `fovYDegrees`, `near` and `far` shape it.

It also answers the questions a pointer asks:

- `project(x, y, z, out)` — where a world point lands, in view pixels.
- `unproject(viewX, viewY, out)` — the ground-plane point under a pixel.
- `ray(viewX, viewY, out)` — the `ViewRay` a pixel looks along, which is what a 3D gizmo drag holds a
  handle's line or plane to.
- `unitsPerPixelAt(x, y, z)` — how big a world unit is on screen there, for sizing a handle.

The projection arithmetic is written out here rather than asked of Kool, because this is what a
pointer is hit-tested with while Kool's camera is what draws. `GlViewportOrbitTest` holds the two to
agreeing: a model drawn at a world point shows up at the pixel `project` names for it.

Render thread only, like the view it belongs to.

## See also

- [Rendering with Kool](Rendering-with-Kool) — phases, interpolation, the render thread.
- [Models and Animation](Models-and-Animation) — `ModelCamera` and `ModelLight`.
- [Input](Input) — `PointerMotion`, `IntentSource` and where input is sampled.
- [The Editor](The-Editor) — the Scene and Game tabs.
- [Agent Tool Surface](Agent-Tool-Surface) — `render.set_camera` and `render.follow_entity`.
