package dev.wildware.moba

import com.badlogic.gdx.Input
import dev.wildware.udea.assets.AssetRegistry
import dev.wildware.udea.assets.Axis2D
import dev.wildware.udea.assets.BindingInput
import dev.wildware.udea.assets.Binding
import dev.wildware.udea.assets.Control
import dev.wildware.udea.render.input.ActionBinding
import dev.wildware.udea.render.input.Axis2DBinding
import dev.wildware.udea.render.input.InputBindings
import dev.wildware.udea.assets.Axis2DBinding as AuthoredAxis2DBinding

/**
 * Builds this game's [InputBindings] out of the packed asset graph.
 *
 * ## The regression this closes, and the claim it falsifies
 *
 * `MobaControls.BINDINGS` used to be six bindings written in Kotlin, under a KDoc that said
 * `control`, `binding` and `axis2DBinding` "are not among the kinds `udeaPackBundle` publishes
 * yet". That was not true when it was written. `AssetScope.control` declares
 * `AssetKind.of<Control>()` and `AssetScope.axis2DBinding` declares
 * `AssetKind.of<Axis2DBinding>()` - *published* kinds, unlike `character`, `gameplayEffect` and
 * `effect`, which are `AssetKind.Unpublishable` and are the real reason `src/main/assets` is a
 * separate root. `AssetCodecs` has carried a reader and a writer for all four types, including the
 * `inputKind` discriminator that separates a `key(...)` from a `mouse(...)`, for as long as those
 * types have existed. Nothing had ever asked the packer to publish one, because this tree's only
 * copy of the control script sat in the migrated-corpus root that `udea { assetRoots }` does not
 * name.
 *
 * `moba/assets/control/controls.udea.kts` is that script in the packed root, and this reads it
 * back. Rebinding attack to `E` is now an asset edit that `udeaPackBundle` carries into the game
 * with no Kotlin recompiled - and, because `AssetHotReload` swaps values into the very
 * [AssetRegistry] this reads, it is a change an agent can make over `assets.patch` against a
 * running process and see in the next input frame after a rebuild of the bindings.
 *
 * ## What still comes from code, stated rather than hidden
 *
 * **Gamepads.** `BindingInput` is a two-case sealed interface - a key code or a mouse button -
 * and there is no case for a pad button and no stick index on the authored `Axis2DBinding`. The
 * runtime types have all three ([ActionBinding.buttons], [Axis2DBinding.gamepadAxisX],
 * [Axis2DBinding.gamepadAxisY]), so the pad half of every binding is supplied here from
 * [GAMEPAD_BUTTONS] and [MOVE_STICK_X]/[MOVE_STICK_Y] rather than from the graph. Closing that is
 * a `BindingInput` case, a codec discriminator and a validator rule in `udea-assets`, which is an
 * engine change and not a game's; it is written down here because a loader that silently dropped
 * the pad mapping would look exactly like a loader that never had one.
 *
 * **Mouse buttons.** The asset model can say `mouse(...)` and [ActionBinding] has no mouse field
 * at all, so a `mouse(...)` binding is refused loudly by [actionsFrom] rather than dropped. This
 * game authors none.
 *
 * ## Names are namespaced on the way in
 *
 * An authored id is `control/attack`; a catalog name is `moba/attack`. `InputCatalog` numbers by
 * sorted name across the whole game, so two modules that both authored an axis called `move`
 * would otherwise collide and each would silently address the other's. The namespace is applied
 * here, once, and [MobaControls.MOVE] and its siblings stay the compile-time constants every
 * caller in this game already names.
 */
public object MobaControlAssets {

    /** The prefix every name from this game's graph gets. See the class KDoc. */
    public const val NAMESPACE: String = "moba/"

    /** The asset-id prefix the control graph lives under. */
    public const val GROUP: String = "control/"

    /**
     * Pad buttons per action name, because the asset model cannot say one.
     *
     * The south face button for attack and the west one for the special - the mapping
     * `MobaControls` carried inline before this file existed.
     */
    private val GAMEPAD_BUTTONS: Map<String, IntArray> = mapOf(
        MobaControls.ATTACK to intArrayOf(0),
        MobaControls.ATTACK_2 to intArrayOf(2),
    )

    /** The left stick's x index. Inert until something implements `GamepadState` against hardware. */
    public const val MOVE_STICK_X: Int = 0

    /** @see MOVE_STICK_X */
    public const val MOVE_STICK_Y: Int = 1

    /**
     * Reads every `control`, `axis2D`, `binding` and `axis2DBinding` out of [registry].
     *
     * @throws IllegalStateException when the graph declares no control at all, when a binding
     *   points at a control the graph does not hold, or when an axis binding contributes a
     *   direction this build cannot express as one of four keys. Every one of those is loud on
     *   purpose: an input graph that half-loaded would ship as a game where one key does nothing,
     *   which is found by a player rather than by a build.
     */
    public fun load(registry: AssetRegistry = MobaAssets.registry): InputBindings {
        val ids = registry.ids.filter { it.value.startsWith(GROUP) }
        val controls = HashMap<String, String>()
        val axes = HashMap<String, String>()
        val bindings = ArrayList<Binding>()
        val axisBindings = ArrayList<AuthoredAxis2DBinding>()
        for (id in ids) {
            when (val asset = registry.at(registry.indexOf(id))) {
                is Control -> controls[id.value] = nameOf(id.value)
                is Axis2D -> axes[id.value] = nameOf(id.value)
                is Binding -> bindings += asset
                is AuthoredAxis2DBinding -> axisBindings += asset
                else -> Unit
            }
        }
        check(controls.isNotEmpty() || axes.isNotEmpty()) {
            "the bundle holds no `control/` assets, so this game would boot with no input at " +
                "all. `moba/assets/control/controls.udea.kts` declares them and " +
                "`:moba:udeaPackBundle` is what puts them in the bundle."
        }
        return InputBindings(
            actions = actionsFrom(controls, bindings),
            axes = axesFrom(axes, axisBindings),
        )
    }

    /** `control/attack_2` becomes `moba/attack_2`. */
    private fun nameOf(id: String): String = NAMESPACE + id.substringAfterLast('/')

    /**
     * One [ActionBinding] per authored `control`, carrying every key bound to it.
     *
     * A control with no binding is kept rather than dropped: the game asks about it by name
     * through [MobaControls.ATTACK_ACTION], and a catalog missing the name throws at start-up
     * where an unbound-but-present action correctly reads as never pressed.
     */
    private fun actionsFrom(
        controls: Map<String, String>,
        bindings: List<Binding>,
    ): List<ActionBinding> {
        val keys = HashMap<String, MutableList<Int>>()
        for (binding in bindings) {
            val target = binding.control.id.value
            check(target in controls) {
                "`${binding.id.value}` binds a control the graph does not hold: '$target'"
            }
            when (val input = binding.input) {
                is BindingInput.Key -> keys.getOrPut(target) { ArrayList() } += input.code
                is BindingInput.MouseButton -> error(
                    "`${binding.id.value}` binds mouse button ${input.code}, and `ActionBinding` " +
                        "has no mouse field - see `MobaControlAssets`. Refused rather than " +
                        "dropped: a binding silently ignored is a control that does nothing.",
                )
            }
        }
        return controls.entries.sortedBy { it.key }.map { (id, name) ->
            ActionBinding(
                name = name,
                keys = (keys[id] ?: emptyList<Int>()).toIntArray(),
                buttons = GAMEPAD_BUTTONS[name] ?: IntArray(0),
            )
        }
    }

    /**
     * One [Axis2DBinding] per authored `axis2D`, with its four keys taken from the directions.
     *
     * The runtime axis is four key slots and a stick; the asset is any number of contributions,
     * each with a direction vector. This build maps the four cardinals and refuses anything else,
     * because a diagonal contribution would have to be dropped or rounded and both are a key that
     * does not do what the asset says.
     */
    private fun axesFrom(
        axes: Map<String, String>,
        bindings: List<AuthoredAxis2DBinding>,
    ): List<Axis2DBinding> {
        val negativeX = HashMap<String, Int>()
        val positiveX = HashMap<String, Int>()
        val negativeY = HashMap<String, Int>()
        val positiveY = HashMap<String, Int>()
        for (binding in bindings) {
            val target = binding.axis.id.value
            check(target in axes) {
                "`${binding.id.value}` binds an axis the graph does not hold: '$target'"
            }
            val code = when (val input = binding.input) {
                is BindingInput.Key -> input.code
                is BindingInput.MouseButton -> error(
                    "`${binding.id.value}` drives an axis from mouse button ${input.code}; " +
                        "`Axis2DBinding` is four keys and a stick - see `MobaControlAssets`.",
                )
            }
            val (x, y) = binding.direction.x to binding.direction.y
            val slot = when {
                x < 0f && y == 0f -> negativeX
                x > 0f && y == 0f -> positiveX
                y < 0f && x == 0f -> negativeY
                y > 0f && x == 0f -> positiveY
                else -> error(
                    "`${binding.id.value}` contributes ($x, $y); this build's `Axis2DBinding` is " +
                        "four cardinal keys plus a stick, so a diagonal contribution has no slot " +
                        "to go in. Refused rather than rounded.",
                )
            }
            slot[target] = code
        }
        return axes.entries.sortedBy { it.key }.map { (id, name) ->
            Axis2DBinding(
                name = name,
                negativeX = negativeX[id] ?: Axis2DBinding.UNBOUND,
                positiveX = positiveX[id] ?: Axis2DBinding.UNBOUND,
                negativeY = negativeY[id] ?: Axis2DBinding.UNBOUND,
                positiveY = positiveY[id] ?: Axis2DBinding.UNBOUND,
                gamepadAxisX = MOVE_STICK_X,
                gamepadAxisY = MOVE_STICK_Y,
            )
        }
    }

    /**
     * The key codes this game's asset writes, for a test that wants to name one.
     *
     * `Input.Keys` is referenced here and nowhere in the asset - a control script compiles
     * against the asset model alone, so it writes bare integers with a comment saying why.
     * This is the one place the two spellings meet.
     */
    public val EXPECTED_KEYS: Map<String, Int> = mapOf(
        MobaControls.ATTACK to Input.Keys.SPACE,
        MobaControls.ATTACK_2 to Input.Keys.Q,
    )
}
