# Gizmos

A gizmo is the little grabbable thing drawn in the world when you select an entity: the arrows you
pull to move it, the ring you turn, the circle on a tower's range. In Udea they are an editor-only
feature with a small public API, and most of the time you get one by putting an annotation on a
component.

Two rules shape everything below:

- **A gizmo never touches the world.** Dragging a handle answers a *list of field writes*, which the
  editor feeds into `editor.begin_edit` / `editor.update_edit` / `editor.commit_edit`. So a drag and
  an agent's tool call reach the world by the same road and land in the same undo history.
- **A gizmo never ships.** It implements a `udea-editor` type, so it lives in a game's `editor`
  source set. `UDEA-MG-012` (`udeaVerifyEditorAbsent`, which runs on `check`) fails the build if a
  `Gizmo` reaches a release classpath.

## The quickest gizmo: an annotation

Put a handle annotation on a component and you get a gizmo, generated, with no other code.

```kotlin
@Serializable
@Replicated
@PositionHandle
public class Position(
    @Net(agentWritable = true) public var x: Float = 0f,
    @Net(agentWritable = true) public var y: Float = 0f,
    @Sim public var hp: Float = 100f,
) : Component<Position> {
    override fun type(): ComponentType<Position> = Position
    public companion object : ComponentType<Position>()
}
```

That is `moba`'s `Position`, and it is why selecting a unit in the editor gives you move arrows. The
annotation names nothing because `x` and `y` are its defaults.

### The annotations

| Annotation | On | Drags |
|---|---|---|
| `@PositionHandle(x, y, z)` | the class | Moves the entity. `z` empty means a 2D position on the ground plane |
| `@SizeHandle(width, height, depth)` | the class | Resizes a box centred on the entity. `depth` empty means 2D |
| `@RotationHandle(rotation, aboutX, aboutY)` | the class | Turns it. `rotation` alone is one ring about Z; naming `aboutX` and `aboutY` too gives three rings |
| `@ScaleHandle(x, y, z)` | the class | Three scale factors, defaults `scaleX`, `scaleY`, `scaleZ` |
| `@RadiusHandle` | a field | A circle of that radius, with a dot on the rim. For something the entity *is* — a collider, a hit radius |
| `@RangeHandle` | a field | The same drag, drawn as a spoke from the rim. For something the entity *reaches* — an attack range, an aggro radius |

A class annotation names its fields as **strings**, because a Kotlin annotation cannot hold a property
reference. That would normally mean typos are invisible — so `udea-codegen` checks every name at
compile time. A misspelled, missing, read-only or non-`Float` field fails the build with **`UDEA0017`**
at the annotation, with a did-you-mean. Without that check the generated gizmo would fail to compile
in a *different module*, a long way from the typo.

`Transform3D` (in `udea-core`) carries these already, which is why a 3D entity has translate arrows,
rotation rings and scale boxes out of the box.

### Where the generated gizmo goes

This is the interesting bit of plumbing. Your components live in the game's shipping module. Their
gizmos must not — a `Gizmo` implements a `udea-editor` type, and only the `editor` source set may see
that module.

So it happens in two KSP passes:

1. The **game module's** pass validates the handle annotations and lists the components that carry
   one on its generated `<Module>ModuleRegistry`, under `@HandleIndex`.
2. The **editor source set's** pass reads that list off each module registry the build names — by
   exact class name, the same lookup the launcher registry uses, no classpath scanning — and
   generates one `Gizmo` per annotation there.

The generated class is named after the component and the handle: `Crate` with `@SizeHandle` gives
`CrateSizeGizmo`, in the component's own package. A one-field handle takes the field's name too.

Everything is then listed in the game's generated `<Game>GizmoRegistry`: the generated gizmos, plus
every hand-written `Gizmo` in the `editor` source set, in ascending class-name order. **A gizmo is
registered by existing**, not by a line somebody has to remember.

`:moba:desktop` asks for this in its `ksp` block:

```kotlin
"kspEditor"(project(":udea-codegen"))

ksp {
    arg(UdeaModuleRegistry.GIZMO_REGISTRY_OPTION, "Moba")
    arg(UdeaModuleRegistry.REGISTRY_MODULES_OPTION, udeaRegistryModules("Moba", "editorRuntimeClasspath"))
}
```

## Writing one by hand

When an annotation does not fit — the value is not a position or a radius, or you want a particular
look — write a `Gizmo`. The API is small enough to fit on a page.

```kotlin
public interface Gizmo<C : Component<C>> {
    public val component: ComponentType<C>
    public fun GizmoScope<C>.build(target: GizmoTarget<C>)
}
```

`build` runs every frame the target is selected, and every frame of a drag, so a handle follows the
value it drives. Inside it you have exactly two calls: `handle(...)` for something to grab, and
`mark(...)` for a guide with nothing to grab.

Here is `moba`'s tower range gizmo, in `:moba:desktop`'s `editor` source set, in full:

```kotlin
internal object TowerRangeGizmo : Gizmo<Tower> {

    override val component: ComponentType<Tower> = Tower

    override fun GizmoScope<Tower>.build(target: GizmoTarget<Tower>) {
        val tower = target.origin
        val range = target.component.attackRange
        val across = target.axes.direction(Axis.X)
        mark(tower, HandleShape.Circle(range))
        handle(
            at = WorldPoint(tower.x + across.x * range, tower.y + across.y * range, tower.z + across.z * range),
            shape = HandleShape.Line(tower),
            constraint = DragConstraint.Along(Axis.X),
            axes = target.axes,
        ) { drag ->
            write(Tower::attackRange, maxOf(0f, range + drag.stretchFrom(tower)), Snap.Grid)
        }
    }
}
```

It declares no annotation. `MobaGizmoRegistry` lists it because it is a `Gizmo` in that source set.

### `GizmoTarget`

What you are building for:

| | |
|---|---|
| `entity` | The `NetId` — never a Fleks `Entity` |
| `component` | The component, **to read**. Never write it |
| `origin` | Where the entity is, from whatever places it. A gizmo for a radius or a range draws around this |
| `axes` | The frame the editor wants handles in: the world's, or the entity's own when the World/Local switch is on Local |

### `handle(at, shape, constraint, axes, respond)`

- `at` — where it sits, in world space.
- `shape` — what is drawn. `Point`, `Arrow(axis)`, `PlaneSquare(plane)`, `PlaneTab(plane)`,
  `Ring(normal)`, `BoxCorner`, `BoxEdge(axis)`, `Line(to)`, `Sphere`, `ScaleBox(axis)`, `UniformBox`,
  `Circle(radius, normal)`. **Every shape keeps its size on screen**, so a handle is grabbable however
  far away the camera is. A `Line` runs to its far end in world space and a `Circle` is drawn at its
  world radius.
- `constraint` — the line or plane the drag is held to: `Along(axis)`, `Across(plane)`, or
  `ViewPlane`.
- `axes` — the frame `shape` and `constraint` name their axes in. `AxisFrame.WORLD` by default; pass
  `target.axes` to follow the editor's switch.
- `respond` — turns a `Drag` into writes.

### The drag

```kotlin
public data class Drag(
    val start: WorldPoint,
    val at: WorldPoint,
    val unitsPerPixel: Float = 1f,
)
```

with `dx`, `dy`, `dz`, plus `stretchFrom(centre)` (how much further out it is), `along(direction)`,
and `turnAbout(centre)` / `turnAbout(centre, axis)` (the angle it went round).

**One gotcha, and it is the only real one.** The editor keeps the handle you pressed for the whole
drag while the component changes *live* underneath. So compute from what `build` read this frame,
captured in a local — never from the component inside the response, or the drag compounds its own
writes. Every built-in takes each field twice for exactly this reason: as a property reference, which
names it, and as the value read off the component, which everything is measured against.

### `write(field, value, snap)`

```kotlin
write(Tower::attackRange, maxOf(0f, range + drag.stretchFrom(tower)), Snap.Grid)
```

A property reference, so the compiler has checked the name. Only its *name* is read — a callable
reference carries it, with no reflection library — and it is never used to set anything.

Writing the same field twice in one drag, or writing a value that is not finite, throws. That is a
gizmo defect, reported where it happened rather than written into the world.

`snap` says what **kind** of value this is, so the editor can round it: `Snap.None`, `Snap.Grid` (a
distance or a position) or `Snap.Angle` (radians). **The gizmo never rounds anything itself.** The
steps and whether snapping is on at all are the person's preferences, so one gizmo snaps to a one-unit
grid in one project and a sixteen-unit grid in another without knowing either. Tool calls are never
snapped: an agent's `editor.update_edit` takes the exact value.

### `mark(at, shape)`

A guide, never hit-tested, never answering a write. A read-only gizmo is one that declares marks and
no handles — the skeleton overlay in the Animation panel is one.

## The built-ins

Each built-in is a public extension function on `GizmoScope` that declares handles and marks and does
nothing else. The generated gizmos are one call to one of these, and your own gizmo may call the same
function the same way.

| Function | What you get |
|---|---|
| `moveHandles` | 2D: an arrow along X and Y, and a square between them for a free drag |
| `translateHandles` | 3D: an arrow along X, Y and Z, and a square in each of the XY, YZ and XZ planes |
| `sizeHandles` | A handle on each corner of a centred box and on the middle of each side, with an outline between them |
| `rotationHandle` | One ring about Z, on the ground plane |
| `rotationRings` | 3D: one ring per Euler angle, each turning its own field alone |
| `scaleHandles` | A box out along each axis, and one in the middle that scales all three |
| `radiusHandle` | A circle, and a dot on its rim |
| `rangeHandle` | The same drag, with a spoke from the rim back to the entity |

Two details that took thought and are worth knowing:

**An arrow writes only the fields its line can change.** With the world's axes, the X arrow writes `x`
alone — so snapping the drag never nudges `y` sideways onto the grid. Turned with the entity, that
arrow runs across both, and writes both.

**Each rotation ring is about the axis its field really turns the entity about**, which is not always
a world axis. `rotationZ` is applied last, so its ring is about Z. `rotationY` is applied before it,
so its ring is about Y turned by `rotationZ`. `rotationX` is applied first, so its ring is about X
turned by both. The rings follow the entity's angles whether the switch says World or Local, because a
ring about any other axis would have to change more than one angle to turn the entity about it.

**Scales are never snapped.** A scale is a factor; the grid is a distance.

## Snapping and axes

The toolbar over the Scene tab holds grid snapping and its step in world units, angle snapping and its
step in degrees, and a World/Local button. Both are off until you turn them on, and **holding Ctrl
during a drag turns both off for that move**.

These are per project and per person, kept in `<project>/.udea/editor-preferences.properties`, written
the moment you change one. `.udea/` is gitignored — your grid is not something a level or an asset
should carry. An editor built with no file keeps them for the run only, which is what a test wants.

## Testing a gizmo headless

`Gizmo.handles(target)` and `Gizmo.marks(target)` run `build` and give you what it declared, in order.
`Handle.drag(drag)` gives you the field writes. None of it touches the world, so a gizmo test needs no
GL context and no editor window.

```kotlin
val handles = TowerRangeGizmo.handles(GizmoTarget(netId, tower, WorldPoint(0f, 0f, 0f)))
val writes = handles.single().drag(Drag(start, at))
```

## See also

- [[The-Editor]] — the window the handles are drawn in, and what a drag becomes
- [[Agent-Tool-Surface]] — `editor.begin_edit` and friends, which every drag goes through
- [[ECS-and-Components]] — components, and what makes a field writable
- [[Diagnostics-and-Compiler-Plugin]] — `UDEA0017` and the did-you-mean rule
- [[Build-and-Verification]] — `UDEA-MG-010` and `UDEA-MG-012`, the gates that keep editor code out of a release
