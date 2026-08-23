// What this game binds, in the asset graph, where the old game declared it.
//
// ## The regression this closes
//
// `example/src/main/resources/assets/control/controls.udea.kts` declared the controls and their
// bindings as assets, and the port lost that: `MobaControls.BINDINGS` hard-coded the same six
// bindings in Kotlin, and its KDoc explained the loss with
//
//   > `control`, `binding` and `axis2DBinding` are not among the kinds `udeaPackBundle`
//   > publishes yet
//
// which is no longer true and can be checked in two places. `AssetScope.control` declares
// `AssetKind.of<Control>()` - a *published* kind, not `AssetKind.Unpublishable`, which is what
// `character`, `gameplayEffect` and `effect` are - and `AssetCodecs` carries reader and writer
// entries for `Control`, `Axis2D`, `Binding` and `Axis2DBinding`, including the `inputKind`
// discriminator that tells a `key(...)` from a `mouse(...)`. So the packer could always publish
// these four kinds; nothing had ever asked it to, because this tree's only copy of the script sat
// in `src/main/assets`, the migrated corpus root that `udea { assetRoots }` does not name.
//
// This file is that script, in the packed root. `MobaControlAssets` reads it back out of the
// bundle and builds the `InputBindings` the game runs on, so rebinding attack to `E` is an asset
// edit with no Kotlin recompiled - which is the property the asset graph exists for and the one
// the port had dropped.
//
// ## What the asset model still cannot say
//
// Gamepads. `Binding` carries a `BindingInput` that is a key code or a mouse button, and there is
// no `button(...)` or `axis(...)` input for a pad; `Axis2DBinding` has no stick index. The
// runtime types have all three (`ActionBinding.buttons`, `Axis2DBinding.gamepadAxisX/Y`), so the
// pad half of a binding is the one thing `MobaControlAssets` still supplies from code, and it says
// so in as many words rather than quietly dropping it. Closing that is a `BindingInput` case and
// a codec discriminator, and it is not a level's or a game's to add.
//
// The key codes are libGDX's `com.badlogic.gdx.Input.Keys` values, written as literals rather
// than imported: the asset compile classpath is the asset model and not the whole application
// (`AssetCompiler.scriptClasspath`), so a control script does not drag the renderer into the
// graph pass 2 has to compile against.

val KeyQ = 45
val KeyW = 51
val KeyA = 29
val KeyS = 47
val KeyD = 32
val KeySpace = 62

control(name = "attack")

control(name = "attack_2")

axis2D(name = "move")

binding(
    name = "attack_binding",
    control = reference("control/attack"),
    input = key(KeySpace),
)

binding(
    name = "attack_2_binding",
    control = reference("control/attack_2"),
    input = key(KeyQ),
)

axis2DBinding(
    name = "move_left",
    axis = reference("control/move"),
    input = key(KeyA),
    direction = vec(-1.0F, 0.0F),
)

axis2DBinding(
    name = "move_right",
    axis = reference("control/move"),
    input = key(KeyD),
    direction = vec(1.0F, 0.0F),
)

axis2DBinding(
    name = "move_up",
    axis = reference("control/move"),
    input = key(KeyW),
    direction = vec(0.0F, 1.0F),
)

axis2DBinding(
    name = "move_down",
    axis = reference("control/move"),
    input = key(KeyS),
    direction = vec(0.0F, -1.0F),
)
