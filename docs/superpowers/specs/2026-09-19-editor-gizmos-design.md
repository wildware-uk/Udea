# Editor gizmos — design

Designed in a brainstorm with the owner on 2026-09-19. Every decision below was agreed then.
This is the "scene viewer (selection, picking, move handles)" that epic #190 deferred to its own
brainstorm. It builds on the editor shell (#190): #193's `editor.*` tools, #194's `udea-editor`
window and ComposeGL `SceneView` viewport, and #196's Play/Stop/Step.

## The one idea

A **gizmo** is an editor-only UI drawn in world space — a handle you drag to move, resize or rotate
an entity, or to change a field such as a radius. Two rules shape everything else:

1. **The editor is still a screen over the tool surface** (#190). A gizmo never mutates the world;
   a drag produces field writes that go through `editor.*` tools. A person and an agent can never
   get different results.
2. **Built-ins have no private path.** Every gizmo the editor ships — move, resize, rotate, in 2D and
   3D — is written only against the public gizmo API a game author uses. If a built-in needs
   something, it goes into the public API.

## Decisions

| Topic | Decision |
|---|---|
| Who defines gizmos | Built-in transform gizmos **and** component-declared ones, all through one public API |
| During a drag | **Live**: the world updates every frame through an edit session; one undo entry per drag; Escape cancels |
| Dimensions | **2D and 3D, both in this epic**; handles live in 3D world space, a 2D game uses the ground plane |
| Where gizmo code lives | Compile-time annotations on components; custom `Gizmo` classes in the game's `editor` source set; never on a release classpath |
| Selection | Click, Shift-click, box select; handles act on the whole selection as one edit |
| Multi-edit | The inspector edits fields common to the selection; different values show **Mixed**; a write is one edit |
| Snapping | Grid and angle snapping with configurable steps, Ctrl to ignore; world/local axes toggle |
| Views | **Scene tab** (editor camera, interactive gizmos) and **Game tab** (game camera, game input; gizmos off by default, optional read-only overlay) |
| Play | Gizmos stay live in the Scene tab during Play; edits are discarded on Stop unless **kept** |

## 1. Where the parts live

| Part | Module | On a release classpath? |
|---|---|---|
| Handle annotations: `@PositionHandle`, `@SizeHandle`, `@RotationHandle`, `@RadiusHandle`, `@RangeHandle` | `udea-annotations` | Compile-time marker only |
| Public gizmo API: `Gizmo`, `GizmoScope`, `Handle`, handle shapes, `GizmoTarget`, `PickBounds` | `udea-editor` | No |
| Built-in gizmos (2D and 3D move, resize, rotate) | `udea-editor`, public API only | No |
| Generated `<Game>GizmoRegistry` | KSP (`udea-codegen`), emitted into the game's `editor` source set | No |
| A game's custom gizmos | the game's `editor` source set (moba: `:moba:desktop`'s) | No |
| Edit sessions, selection, multi-entity edits, play edits | `editor.*` tools in `udea-agent` | Debug only, as today |
| Release-classpath gate | a `udea-gradle` module-graph rule | — |

### API shape

```kotlin
interface Gizmo<C : Component<C>> {
    val component: ComponentType<C>
    fun GizmoScope.build(target: GizmoTarget<C>)   // declares handles each frame
}

// inside build():
handle(at = worldPoint, shape = Ring(radius), axis = Plane.XY) { drag ->
    write(C::radius, valueFrom(drag))              // field writes, never a mutation
}
```

- A drag yields **field writes**; the editor feeds them into an edit session (section 2).
- Handles are drawn from **public shapes**: point, arrow, plane square, ring, box corner, line,
  sphere. They keep a constant size on screen.
- Positions are world-space floats in 3D. A 2D game's handles sit on the ground plane (z = 0).
- Components store plain floats, never vector objects (owner, 2026-09-19); the API reads and writes
  those fields.

### Annotations

A handle that drives **one field** marks the field. A handle that drives **several** marks the class
and names the fields, with defaults for the common case:

```kotlin
@PositionHandle                              // x = "x", y = "y", no z: a 2D handle
class Position(var x: Float, var y: Float)

@PositionHandle(z = "z")                     // 3D
class Transform3D(var x: Float, var y: Float, var z: Float /* … */)

@PositionHandle(x = "worldX", y = "worldY")  // fields with other names
class Spawner(var worldX: Float, var worldY: Float)

class Tower(@RangeHandle var attackRange: Float)
```

Kotlin annotations cannot hold property references, so multi-field handles name fields as strings.
**KSP validates them at compile time**: a misspelled, missing or non-`Float` field fails the build
with a `UdeaDiagnostic`, a stable rule id and a did-you-mean. KSP turns each annotation into a
generated `Gizmo` class with direct field access, so nothing is looked up by name at run time, and
an annotation is only shorthand for what a user could write by hand.

## 2. Edit sessions and tools

| Tool | Does |
|---|---|
| `editor.begin_edit(entities, fields, author)` | Opens a session, records each field's starting value, returns a `sessionId` |
| `editor.update_edit(sessionId, values)` | Applies values live; no undo entry |
| `editor.commit_edit(sessionId)` | Closes as **one** undo entry whose reverse is the starting values |
| `editor.cancel_edit(sessionId)` | Restores the starting values; no undo entry. Escape calls it |
| `editor.set_field` (extended) | Accepts several entities: one write, **one** undo entry |
| `editor.select(entities, mode = replace\|add\|remove)`, `editor.selection` | Selection is per author and visible to every author |
| `editor.common_fields(entities)` | Each shared field with its value, or `Mixed` |

- Every change is applied **between ticks** through `SimBarrier`; no system sees a half-applied drag.
- **One open session per author.** Beginning another commits the first.
- **Sessions do not leak.** A session whose author disconnects, or that gets no update for 30
  seconds, is cancelled and its values restored.
- **Conflicts:** if another author writes a field mid-session, the last write wins; the undo entry
  still records the session's starting values, and #193's per-author undo check ("changed since —
  Overwrite?") covers the rest.
- **Snapping is applied by the editor before `update_edit`.** Tools take exact values, so an agent
  never needs snapping.
- Entities are addressed by `NetId`, never a Fleks `Entity`.

## 3. Views, selection, drawing

| | Scene tab | Game tab |
|---|---|---|
| Camera | Editor camera: pan/zoom in 2D, orbit/pan/zoom in 3D; a 2D/3D toggle | The game's camera |
| Input | The editor: picking, gizmos, box select | The game |
| Gizmos | Drawn and interactive | Hidden by default; a toggle draws them **read-only** and input still goes to the game |
| During Play | The live world, still editable | The game being played |

- Both tabs are ComposeGL `SceneView`s drawing the same world with different cameras.
- Gizmos draw only into the view that asked for them. **`render.screenshot` and `FrameCapture` never
  contain them**, the same structural exclusion as the agent activity overlay.
  `editor.screenshot(view = scene)` returns a picture with gizmos.

**Picking.** Handles are hit-tested before entities. Entities are picked by **pick bounds** that render
systems report per entity (sprite rectangles, model boxes) through the public `PickBounds` interface,
so a user's render system can make its entities pickable too. Front-most wins; clicking again on the
same spot cycles to the next one behind. Click selects, Shift-click toggles, dragging on empty space
box-selects.

**Several entities selected.** Handles sit at the selection's centre; a drag moves, rotates or
resizes all of them in one session. A component handle appears only when every selected entity has
that component; with a single entity selected, all of its handles appear.

**Snapping and axes.** Grid snapping for position and size, angle snapping for rotation, each with a
step; Ctrl ignores snapping while held; a world/local switch aligns handles to world axes or the
entity's rotation. These are per-project editor preferences, not game data.

**Inspector.** Built on `editor.common_fields`: shared fields show their value or **Mixed**; setting
one writes all selected entities as one edit.

## 4. Play and Keep

- During Play the Scene tab stays interactive; edits go through sessions between ticks, so the
  running game sees them on the next tick.
- Every edit made during Play or Step is a **play edit**, listed in a "Changes during Play" panel with
  a **Keep** toggle per entry; the inspector shows a Keep pin on a field changed during Play.
- **On Stop:** (1) the world is restored from the snapshot taken at Play (#196, unchanged); (2) each
  kept edit is re-applied as a normal edit — undoable, in its author's history, saved by Save;
  (3) the rest are gone.
- Keep stores the **final value**, not the delta. Only edits to entities that existed when Play began
  can be kept; the panel says so for the others.
- `editor.play_edits`, `editor.keep(editId)` and `editor.unkeep(editId)` give agents the same.

## 5. Testing and proof

- **Tools, headless:** begin → update ×N → commit is one undo entry; cancel restores exactly; an idle
  session times out and restores; a multi-entity write is one entry; `common_fields` reports `Mixed`
  correctly; kept edits survive Stop and unkept ones do not. Each seen red first.
- **Determinism:** sessions applied between ticks are recorded like other editor edits; a replay of
  an editing session reproduces the world, under the existing replay-equality proof.
- **API:** KSP golden tests; a misspelled field fails with a did-you-mean; a hand-written `Gizmo` and
  its annotation-generated twin behave identically; the **dogfood proof** is a custom gizmo in moba's
  editor source set (the tower attack-range ring) built only from public API.
- **GL, under xvfb with `-Pudea.render.requireGl=true`:** a handle is hit where it is drawn; a drag
  moves the entity by the expected world distance, in 2D and in 3D; gizmos are absent from
  `render.screenshot`. Screenshots of each gizmo and of both tabs go to the gallery.
- **Release gate:** proven red by putting a gizmo class on a release classpath.

## Tickets, in order

| # | Ticket | Needs |
|---|---|---|
| G1 | Edit sessions, multi-entity `set_field`, selection and `common_fields` tools — no UI | #193 |
| G2 | Gizmo API, handle annotations, KSP registry and validation, release-classpath gate | #194 |
| G3 | Scene and Game tabs: two `SceneView`s, editor camera 2D/3D, read-only overlay toggle, capture exclusion, `editor.screenshot` | #194 |
| G4 | Picking and selection in the Scene tab, `PickBounds`, inspector multi-edit with Mixed | G1, G3 |
| G5 | Built-in 2D gizmos (move, resize, rotate, radius/range), snapping, world/local, multi-selection drags, moba range-ring dogfood | G2, G4 |
| G6 | Built-in 3D gizmos (translate arrows and planes, rotation rings, scale) on `Transform3D` | G2, G4, the textured-model work |
| G7 | Play edits and Keep | #196, G1, G5 |

## Relationship to the port

These tickets live under a new epic, **"Editor gizmos"**, a sibling of #190. They are **not** part of
epic #199 and do not block #214 (kmp → master): the port finishes with the editor shell, and gizmos
build on it. If the owner wants gizmos before master, add the epic to #199's list.

## Out of scope

- Pivot choice for multi-selection rotate/scale (centre vs each origin) — cheap to add later on the
  same API.
- Gizmos in the Game tab that accept input — ruled out: the Game tab's input belongs to the game.
- Asset viewer and blueprint editor — their own epics (#190).
