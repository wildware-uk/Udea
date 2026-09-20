# The Editor

Udea's editor is a window with panels round a view of your game. You click an entity, change a
number, press Play, watch it, press Stop.

The thing that makes it unusual: **the editor is a screen over the agent tool surface.** Every button
in it sends the same tool call an agent sends over HTTP. There is no second "spawn a unit" path
hiding behind a button. Whatever you can do by clicking, an agent can do by calling — and it lands in
the same undo history.

```
sh gradlew :moba:desktop:runEditor
```

It opens paused, before the first tick, so nothing moves until you say so.

## Opening it

`moba` is the game with an editor entry point on master. The task is `:moba:desktop:runEditor`, whose
main class is `dev.wildware.moba.editor.MobaEditor`. It runs `Windowed`, and `-PdebugPort=N` also
opens the agent HTTP surface, so you can drive the same session from a tool client while you look at
it.

The editor lives in `udea-editor`, and it **never ships**. A game reaches it only from an `editor`
source set — `:moba:desktop`'s is where `MobaEditor` is — and `UDEA-MG-010` fails the build if any
release classpath resolves the module. `hollow` has no editor entry point yet.

## The window

```
┌─────────────────────────────────────────────────────┐
│ File   Edit                                         │  menu bar
│ [Play] [Stop] [Step] [Play standalone]   status     │  toolbar
│ [Scene] [Game]  [2D] ☐Grid [1.0] ☐Angle [15.0] World│  tab headings
├─────────┬───────────────────────────────┬───────────┤
│ Create  │                               │  History  │
│         │        Scene or Game          ├───────────┤
│         │                               │ Inspector │
│         │                               ├───────────┤
│         │                               │  Changes  │
│         │                               ├───────────┤
│         │                               │   Asset   │
└─────────┴───────────────────────────────┴───────────┘
│ Paused - tick 0                                     │  status line
```

The panels are ComposeGL docked windows (`DebugWindowHost`). You can drag one off, tab two together,
re-dock it, and drag the dividers. The layout is kept in memory for the run only — writing it to a
file beside the game would dirty the working tree every time you opened the editor.

**The view sits in the gap the panels leave.** It is not drawn under them and it does not show
through them. The editor reads back where each docked panel actually landed and puts the view in the
one rectangle nothing covers, less ComposeGL's divider. A pointer over a panel or a divider is the
panel's, never the view's.

The panels are drawn into the window, never into either view, so **no screenshot ever contains a
panel.**

## Scene and Game

Two tabs on the same world at the same tick. One is composed at a time.

| | Scene tab | Game tab |
|---|---|---|
| Camera | The editor's own `EditorCamera` | The game's |
| Pointer | The editor's — it takes every event over the view | The game's — the view has no handler, so an event no widget took goes on to the game |
| Gizmos | Always | Behind the **Gizmos** checkbox |
| Screenshot | `editor.screenshot` with `view=scene` | `render.screenshot`, or `editor.screenshot` with `view=game` |

The Scene tab's camera is presentation state only. Moving it changes what that tab shows and nothing
a simulation, a snapshot or a capture reads.

The Scene tab's `2D`/`3D` button switches the editor camera between a flat view and an orbit camera.
In 2D the secondary and middle buttons pan and the wheel zooms about the point under the pointer; in
3D the secondary button orbits about the centre, the middle button pans the centre, and the wheel
moves the eye in and out.

**The Game tab forces no aspect ratio.** Both views are resized to the rectangle the tab occupies, so
the game's frame is whatever shape you drag the dividers to — and `render.screenshot`, which reads
that frame, follows. Both views are sized every frame, not only the showing one, so a screenshot is
the right shape whichever tab you happen to be looking at.

## Play, Stop, Step

Four buttons, each a tool call:

| Button | Tool |
|---|---|
| Play | `editor.play` |
| Stop | `editor.stop` |
| Step | `time.step` with `ticks=1` |
| Play standalone | saves the level and starts a separate game on it |

**Every button is always pressable**, and a press that does not apply is refused by the tool — Stop
with nothing playing answers `not_playing`, and that refusal is what the status line says. A button
greyed out from the window would mean the window keeping its own copy of whether a play is under way,
and an agent can start or stop one without the window knowing.

Play standalone is optional: an editor given no `StandaloneLauncher` leaves the button out. `moba`'s
starts `:moba:desktop:run`'s main class in a new JVM.

### Changes during Play, and Keep

Stop puts the world back to where Play started. That is usually what you want — and sometimes you
tuned a number while it was running and would like to keep *that*.

So the Scene tab and the Inspector stay live during a play, and every edit made then is listed in the
**Changes during Play** panel with a Keep toggle. The Inspector puts a pin under any field one of
those edits wrote. On Stop, the world is put back and then each kept edit is made again.

The panel is `editor.play_edits`' answer; a toggle is `editor.keep` or `editor.unkeep`. An edit that
cannot be kept says why.

## Selecting

Click an entity in the Scene tab.

- **Click** — replaces the selection with the front-most entity under the pointer. A click on nothing
  clears it.
- **Click the same spot again** — selects the next entity *behind* the last one picked there, round
  to the front after the back-most. That is how you reach a unit hidden under another.
- **Shift-click** — adds the entity under the pointer, or takes it out if it is already in.
- **Drag from empty space** — draws a box and selects everything whose drawn rectangle it touches, or
  adds to the selection with Shift held. A drag that starts *on* an entity is a gizmo drag, not a box.

Picking asks the view's own render systems what is under the pointer, so it picks what you can see.
Every change to the selection is an `editor.select` call.

## The Inspector

Shows what the selected entities have in common: one box per field, keyed `Component.field`.

**There is no Set button.** Typing into a box writes the value to every selected entity as you type;
Enter, or leaving the box, keeps it as one undoable edit. A field the selection disagrees on shows an
empty box with the placeholder **Mixed**. A field the engine will not let you write is shown as plain
text rather than a box.

Underneath, `editor.begin_edit` / `editor.update_edit` / `editor.commit_edit` — the same three calls a
gizmo drag makes, which is why live typing and a live drag both end up as a single history entry.

## History and undo

The **History** panel is `editor.history`'s answer, and the Undo button — and `Ctrl+Z`, and
`Edit > Undo` — is `editor.undo`.

History is **per author**. The editor interns a session label (`editor`) and files every call under
it. An agent that sends `session=editor` over HTTP is therefore the *same* author: it sees the
editor's edits in `editor.history` and can undo them. An agent using its own session has its own
history and cannot undo yours.

## The Asset panel

Open an asset by id, change its values, Save (`Ctrl+S`), or save a copy as a new asset.

It shows `assets.fields`' answer: one row per value, with the line it is on. A value that is not a
plain literal — computed, or set by a `val` further up the script — is read-only and says which line
decided it. Saving is an `assets.set` per changed value, or `assets.create` for a copy, and the
editor writes the exact value back into the `.udea.kts`.

The tools hot-reload what they write where they can. When one could not — a new asset is a new shape,
which a running game only picks up next launch — the message says so in those words rather than
leaving you wondering why nothing changed.

## The Create panel

One button, which spawns the game's configured blueprint at a position, as an `editor.spawn` call.

## The Animation panel

Present only when the game has animated models: a clip list, a scrub bar, and a bone overlay on the
Scene tab. An editor built without an `EditorAnimation` leaves the panel out entirely.

## Gizmos

Given gizmos, the Scene tab draws the handles of every gizmo the selection shares, and a drag on one
is one edit session — live while it moves, one undo entry when you let go, cancelled by Escape. The
toolbar over the Scene tab holds grid snapping and its step, angle snapping and its step, and a
World/Local axes switch, all kept in the project's editor preferences.

Gizmos have their own page: [[Gizmos]].

## Building an editor for your game

`EditorSession` is the whole window. A launcher opens two views on its render backend, builds the
session over an `AgentBridge`, and drives it from the frame callback:

```kotlin
val views = EditorViews(backend.openSceneView(EditorCamera()), backend.openGameView())
val editor = EditorSession(
    EditorTools(bridge, sessions.intern("editor")),
    tick = { host.ctx.clock.tick },
    paused = { host.time.paused },
    spawn = ...,
    views = views,
)
backend.show(ui)
ui.show(editor.window)
backend.drive { delta ->
    loop.pump(delta)
    editor.frame()
}
```

The optional arguments decide which panels exist: `standalone` for Play standalone, `animation` for
the Animation panel, `gizmos` for the handles. Pass none and you get the rest.

`EditorViews` checks the obvious mistake at construction — the Scene view must have an editor camera
and the Game view must not. `EditorViews.detached()` builds both with no GL behind them, for headless
tests of the window.

## Tests and GL

`udea-editor`'s tests that need a real Kool context are in `dev.wildware.udea.editor.gl` and run under
`udeaEditorGlTest`, one JVM per test class — Kool allows one context per process, for that process's
lifetime. With no display they **skip** and the build stays green, so a real run sets
`-Pudea.render.requireGl=true`:

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true
```

## See also

- [[Gizmos]] — handles, the annotations that generate them, and writing your own
- [[Agent-Tool-Surface]] — the `editor.*` tools the window calls, and how to call them yourself
- [[UI-with-ComposeGL]] — the toolkit the panels are written in
- [[Cameras]] — `EditorCamera` and the Scene tab's 2D/3D switch
- [[Assets]] — what the Asset panel is editing
- [[Levels]] — what Save and Play standalone write
