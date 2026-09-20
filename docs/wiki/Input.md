# Input

Input in Udea is one narrow seam. A keyboard, a gamepad, a replay and an agent all produce the same
thing — an `Intent`, once per tick — and the simulation cannot tell which one it is running against.
That is the whole design, and everything else on this page follows from it.

The analogy is a vending machine. The buttons on the front, a coin, an app on your phone: whatever
you use, what reaches the machine is one signal saying "row B, column 4". The machine does not know
or care where it came from.

## The seam: `IntentSource`

```kotlin
public fun interface IntentSource {
    public fun sample(into: Intent)
}
```

`sample` is called **exactly once per simulation tick**, from the simulation thread, at
`SimPhase.Intent`. It is handed an `Intent` that has already been cleared, it writes this tick's
state, and it returns. It must not block and it must not allocate: it is on the per-tick path.

Four implementations ship:

| Source | Where input comes from |
|---|---|
| `DeviceIntent` | A real keyboard, gamepad and pointer. |
| `InjectedIntent` | The agent's `input.*` tools, a replay, a scripted test. |
| `CompositeIntent` | Several sources at once — a human at the keyboard *and* an agent driving. |
| `IntentSource.NONE` | No input, ever. What a dedicated server uses. |

`NONE` is an object rather than a `null`, deliberately: a null source would make "is input wired?" a
question every reader has to ask, and a game whose input silently stopped working would look the
same either way.

### Why the agent goes through the same seam

Because otherwise the agent is testing a different game. The old approach posted synthetic key
events into the graphics library and hoped the game read them the same way. That only works while
the game polls a device, it cannot work headless at all, and it makes the agent's input arrive at
*frame* boundaries rather than tick boundaries — so an agent holding a key for "ten ticks" held it
for however many ticks ten frames happened to contain.

Here the agent writes an intent and the tick reads an intent, which is byte for byte what a human
produces.

`CompositeIntent` does not arbitrate. If a human holds "left" while an agent holds "right" the
character stands still, exactly as it would if one person pressed both keys. A "the human wins" rule
sounds obviously right and is not: it would make synthesised input behave differently from a human's,
which is the one property this model exists to guarantee.

## Controls are names, keys are named too

A game binds **names**, and no key code appears in game code (issue #228).

`InputCatalog` turns names into ids. `ActionId` and `AxisId` are array indices, assigned by sorted
name across the whole game — so **namespace your names**: `"moba/move"`, not `"move"`. Two modules
that both called an axis `move` would collide, and a collision means one silently addressing the
other's axis.

Two kinds of control:

- An **action** is a button: `"moba/attack"`. `ActionBinding` lists the `InputKey`s, gamepad `buttons`
  and `pointerButtons` that fire it.
- An **axis** is a direction: `"moba/move"`. `Axis2DBinding` names four keys (`negativeX`, `positiveX`,
  `negativeY`, `positiveY`), an optional gamepad stick (`gamepadAxisX`, `gamepadAxisY`,
  `invertGamepadY`) and a `deadzone`.

`InputKey` (`udea-assets`) is an enum of physical keys: `A`..`Z`, `Digit0`..`Digit9`, `Space`,
`LeftShift` and the rest. A name has one meaning. A number does not — under the old code W was 51,
which under Kool is the digit 3, and Kool's Android backend numbers keys differently again. Every one
of those compiled, validated and ran, answering to whatever key the number happened to mean on that
backend.

`KoolKeyTable` (`udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolKeyTable.kt`) is
the one place a backend's key code becomes an `InputKey`, one table per backend. It maps Kool's
**universal** key code — which physical key it was, by its position on a US layout — not the local
one. So walking forward is the key above S, whatever is printed on it. A game that wants "the key
labelled Q" is asking for text, which the interface handles through the typed character.

### Declaring the bindings

Two ways, and both are in the tree.

**As an asset**, which is what `moba` does
(`moba/game/assets/control/controls.udea.kts`). Rebinding a key is then an asset edit with no Kotlin
recompiled:

```kotlin
control(name = "attack")
axis2D(name = "move")

binding(
    name = "attack_binding",
    control = reference("control/attack"),
    input = key(InputKey.Space),
)

axis2DBinding(
    name = "move_up",
    axis = reference("control/move"),
    input = key(InputKey.W),
    direction = vec(0.0F, 1.0F),
)
```

The asset model cannot express the gamepad half of a binding yet — there is no `button(...)` input
for a pad and no stick index on `axis2DBinding` — so `MobaControlAssets` supplies that from code, and
says so.

**In Kotlin**, which is what Hollow does
(`hollow/game/src/commonMain/kotlin/dev/wildware/hollow/HollowControls.kt`):

```kotlin
public val BINDINGS: InputBindings = InputBindings(
    actions = listOf(
        ActionBinding(
            name = RUN,
            keys = listOf(InputKey.LeftShift, InputKey.RightShift),
            buttons = intArrayOf(GAMEPAD_RUN_BUTTON),
        ),
    ),
    axes = listOf(
        Axis2DBinding(
            name = MOVE,
            negativeX = InputKey.A, positiveX = InputKey.D,
            negativeY = InputKey.S, positiveY = InputKey.W,
            gamepadAxisX = LEFT_STICK_X, gamepadAxisY = LEFT_STICK_Y,
        ),
    ),
)

public val MOVE_AXIS: AxisId = BINDINGS.catalog.axis(MOVE)
public val RUN_ACTION: ActionId = BINDINGS.catalog.action(RUN)
```

Hollow's own KDoc says why it is not an asset yet: the reader is two hundred lines living in
`moba:game`, which `hollow:game` cannot depend on, and copying it would be duplicated logic.

Resolve the ids **once**, in an object, and keep them. Two catalogs over the same names are still two
catalogs, and `Intent.copyFrom` refuses to cross them — which is the check that stops an agent's
injected intent being sampled into a simulation that numbered its actions differently.

## Reading an intent

`InputModule` publishes an `IntentState` on the `GameContext` and registers the sampling system at
`SimPhase.Intent`. A gameplay system reads the intent it produced:

```kotlin
val intent = ctx[IntentState.KEY].intent

if (intent.isJustPressed(MobaControls.ATTACK_ACTION)) { /* one press edge */ }
if (intent.isPressed(MobaControls.ATTACK_ACTION))     { /* held this tick */ }
val dx = intent.axisX(MobaControls.MOVE_AXIS)
val dy = intent.axisY(MobaControls.MOVE_AXIS)
```

A press is two things: a **level** (`isPressed`) and a counted **edge** (`pressCount`,
`isJustPressed`). Counting edges rather than latching a boolean is what makes a key pressed and
released between two ticks still register.

`InputModule` is a module of its own rather than part of `RenderModule`, because a dedicated server
has no controller and no business sampling one. A game lists it when it has a player.

## The interface gets first refusal

A key a menu took, or a click on a button, never becomes an intent (issues #227, #230).
`KoolKeyboard` and `KoolPointer` hand each event to the ComposeGL layer first and only then to the
game's bindings, so the keyboard and the menu cannot disagree about which key was pressed.

The verdict's granularity is the toolkit's: `used` covers everything one pointer did in one frame, so
a press on the scene in the same frame as a move a control handled is dropped with it. That is
conservative — it can cost a click, never leak one.

`DeviceIntent` itself names no backend type. It talks to `KeyboardState`, `GamepadState` and
`PointerState`, and `KoolKeyboard`/`KoolPointer` are what bind those to real hardware. That split is
what lets the whole input model — edge counting, vector accumulation, deadzone, normalisation — be
tested with no window, no context and no hardware.

One detail with teeth: `DeviceIntent.sample` spends the device's press edges **after** every binding
has read, never during the loop. Two actions on one key must both see the press; spending it inside
the loop would give it to whichever id sorted first.

## PointerMotion: how far the mouse moved

`PointerMotion`
(`udea-render/src/commonMain/kotlin/dev/wildware/udea/render/input/PointerMotion.kt`, issue #248) is
how far the mouse has moved in window pixels since whoever reads it last spent it:

```kotlin
public interface PointerMotion {
    public val motionX: Float      // right is positive
    public val motionY: Float      // DOWN the screen is positive
    public fun spendMotion()
    public companion object { public val NONE: PointerMotion }
}
```

It sits beside `PointerState` rather than inside it, because the two are read on different clocks.
Presses are spent once per **tick**, by `DeviceIntent`. Motion is spent once per **frame**, by the
presentation code that turns a view with it — `ThirdPersonRig` is the shipped reader. Every pointer's
motion is summed, so a finger dragging on a touch screen moves it as a mouse does.

**Motion never reaches an `Intent`.** Nothing in the simulation depends on it. A game that wants the
mouse to steer a unit turns a camera with it and makes the unit's intent relative to that camera, on
the input side. See [Cameras](Cameras) for Hollow's `CameraRelativeIntent`, which is fifteen lines.

Unlike a press, motion is **not** held back for the interface's verdict: a pointer moving across a
button is not a click on it. A reader that should not turn while the pointer is over the interface
turns only while a button is held, and a press the interface took never holds one. That is what
`ThirdPersonRig.turnButton` is for.

## Driving it from an agent

`udea-agent-host`'s `InputToolset` exposes `input.press`, `input.release`, `input.tap`,
`input.set_axis`, `input.release_all` and `input.state`. They write an `InjectedIntent`, which is
read by the next tick and by exactly one tick — so `input.tap` is a reliable single edge rather than
"probably one", and `input.press` then `input.release` two ticks later produces one `justPressed` and
three ticks of `pressed`.

`InjectedIntent` is written by the HTTP thread and read by the simulation thread, under one lock, and
`sample` takes it once and allocates nothing. A tick never sees an action's level from one moment and
its edge count from another.

## See also

- [Cameras](Cameras) — `PointerMotion`, `turnButton` and camera-relative movement.
- [Agent Tool Surface](Agent-Tool-Surface) — the `input.*` tools.
- [Assets](Assets) — the `control`, `axis2D`, `binding` and `axis2DBinding` asset kinds.
- [UI with ComposeGL](UI-with-ComposeGL) — who gets first refusal on a key or a click.
- [Replay and Time Travel](Replay-and-Time-Travel) — recorded input, replayed through the same seam.
- [Tick Model and Determinism](Tick-Model-and-Determinism) — why sampling is per tick.
