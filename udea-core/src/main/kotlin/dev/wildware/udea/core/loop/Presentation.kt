package dev.wildware.udea.core.loop

/**
 * Everything the simulation loop knows about drawing: one call, one number.
 *
 * Declared in `udea-core` so [GameLoop] can hold a nullable reference to it, and declared
 * with **no GL type in its signature** so that holding one does not put graphics on the
 * kernel's compile classpath. `udea-render`'s `RenderPipeline` is the implementation; a
 * headless simulation — dedicated server, CI, the agent's `SimHarness`, fast-forward —
 * passes `null` and the identical [Simulation] runs (spec 3.5).
 *
 * This is also why presentation systems are **not Fleks systems**: they live behind this
 * interface rather than in the world's system list, so `world.update(dt)` is pure simulation
 * by construction and not by convention (spec 3.3).
 */
public interface Presentation {

    /**
     * Draws one frame.
     *
     * @param alpha how far the render is between the last simulated tick and the next one,
     *   always in `[0, 1)`. A renderer interpolates its transforms by it; a renderer that
     *   ignores it draws at the last tick and judders. It is *not* simulation state and
     *   never enters a snapshot.
     */
    public fun render(alpha: Float)
}
