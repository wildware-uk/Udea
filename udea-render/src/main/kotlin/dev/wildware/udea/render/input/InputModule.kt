package dev.wildware.udea.render.input

import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaModule

/**
 * Contributes the input model: one service and one system.
 *
 * ## Why a module of its own and not part of `RenderModule`
 *
 * `RenderModule` is in every `moba` process including the headless server, because its
 * `InterpSnapshotSystem` has to be there for the three modes to run the identical simulation. An
 * input sampler is not in that category: a dedicated server has no controller and no business
 * sampling one, and folding this in would have made "the server samples input from
 * `IntentSource.NONE` sixty times a second" a thing nobody chose. A game lists this module when
 * it has a player.
 *
 * It is a `UdeaModule` and not a `RenderSystem` even though it lives in `udea-render`, and that
 * is deliberate rather than sloppy: sampling has to happen at a *tick* boundary (see
 * [IntentState]), and a `RenderSystem` runs at a frame boundary. The device-reading half - the
 * one class that names `Gdx.input` - is `GdxKeyboard`, and nothing here references it.
 */
public class InputModule(
    /** What this game binds. */
    public val bindings: InputBindings,
    /** Where input comes from. A client swaps in [DeviceIntent] once its window exists. */
    source: IntentSource = IntentSource.NONE,
) : UdeaModule {

    /** The service, built here so a composition root can reach it before the host exists. */
    public val state: IntentState = IntentState(bindings, source)

    override val name: String get() = "udea-render/input"

    override fun context(builder: GameContextBuilder) {
        builder.service(IntentState.KEY, state)
    }

    override fun simulation(registry: SimRegistry) {
        registry.add(SimPhase.Intent, { IntentSampleSystem(state) })
    }
}
