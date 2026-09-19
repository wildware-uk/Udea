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
// `AssetKind.of<Control>()` - a *published* kind - and `AssetCodecs` carries reader and writer
// entries for `Control`, `Axis2D`, `Binding` and `Axis2DBinding`, including the `inputKind`
// discriminator that tells a `key(...)` from a `mouse(...)`. So the packer could always publish
// these four kinds; nothing had ever asked it to, because this tree's only copy of the script sat
// in `src/main/assets`, the migrated corpus root that `udea { assetRoots }` did not name.
//
// `character`, `gameplayEffect` and `effect` were the kinds that really were unpublishable, and
// they were what kept that second root alive. They are published now, the corpus is merged into
// this root, and `src/main/assets` is deleted.
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
// ## The keys are named, and the numbers are the renderer's
//
// `key(InputKey.W)`, never `key(87)` (issue #228). This file used to carry integers: first
// libGDX's, where W was 51 - which under Kool is the digit 3 - and then Kool's desktop codes, which
// are GLFW's constant for a letter and a negative code of Kool's own for a special key, and which
// Kool's Android backend numbers differently again. Every one of those compiled, validated and ran,
// answering to whatever key the number happened to mean on that backend.
//
// A name has one meaning: the physical key. `udea-render`'s `KoolKeyTable` owns the number per
// backend, and `MobaKeyBindingTest` presses each of the eight below through the real input seam.

control(name = "attack")

control(name = "attack_2")

// The item bar. Issue #166 grants an item's active into an ability slot above a kind's own two,
// and a slot with no key bound to it is an active a human cannot cast - which is the state
// `attack_2` sat in until `PlayerControlSystem` was given a second slot to point it at, and the
// reason that KDoc says a bound control nothing reads is indistinguishable from an unbound key.
//
// E and R, next to WASD, and not 1 and 2: the digits are what a MOBA binds the *shop* to, and
// this game will want them.
control(name = "item_1")

control(name = "item_2")

axis2D(name = "move")

binding(
    name = "attack_binding",
    control = reference("control/attack"),
    input = key(InputKey.Space),
)

binding(
    name = "attack_2_binding",
    control = reference("control/attack_2"),
    input = key(InputKey.Q),
)

binding(
    name = "item_1_binding",
    control = reference("control/item_1"),
    input = key(InputKey.E),
)

binding(
    name = "item_2_binding",
    control = reference("control/item_2"),
    input = key(InputKey.R),
)

axis2DBinding(
    name = "move_left",
    axis = reference("control/move"),
    input = key(InputKey.A),
    direction = vec(-1.0F, 0.0F),
)

axis2DBinding(
    name = "move_right",
    axis = reference("control/move"),
    input = key(InputKey.D),
    direction = vec(1.0F, 0.0F),
)

axis2DBinding(
    name = "move_up",
    axis = reference("control/move"),
    input = key(InputKey.W),
    direction = vec(0.0F, 1.0F),
)

axis2DBinding(
    name = "move_down",
    axis = reference("control/move"),
    input = key(InputKey.S),
    direction = vec(0.0F, -1.0F),
)
