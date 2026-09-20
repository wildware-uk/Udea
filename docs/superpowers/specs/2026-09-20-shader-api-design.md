# A shader API for games on Udea

Owner request (dashboard, 2026-09-20): *"Let's design an api to allow users of the engine to use
shaders, compute and graphical, in a clean and simple API."*

The lead designed this without asking questions, per the dev-team rules. Every decision here can be
changed by a comment on the epic.

## The problem

A game cannot write a shader today. Everything that draws is `udea-render`'s own: `KslPbrShader` for
models (`ModelStage.kt:358`), `KslUnlitShader` for sprites (`SpriteBatchNode.kt`). There is no hook,
and there cannot be a casual one, because `UDEA-MG-002` bans every `de.fabmax.kool:*` artifact from
every module except `udea-render`. A game that took a Kool `KslShader` as a parameter would put Kool
on its own classpath and fail the build — correctly.

So the API is not "let the game reach Kool". It is a shader surface of Udea's own types that
`udea-render` implements over Kool's KSL.

## Why KSL and not GLSL strings

Kool's shaders are written in **KSL**, a Kotlin DSL that each backend turns into its own shading
language. Taking KSL as the base rather than a GLSL string gets three things for free:

- **One shader, every target.** Desktop GL, Android and any later WebGPU backend compile from the
  same source. A GLSL string would be desktop-only the day it was written.
- **Errors at build time, in Kotlin.** A misspelled uniform is a Kotlin compile error, not a black
  screen at run time.
- **No string-built code**, which `AGENTS.md` forbids anyway.

The cost is that a shader is Kotlin rather than a `.frag` an artist can paste in. That is the right
trade for this engine: everything else here — assets, levels, abilities — is typed and checked.

## The shape

Three kinds, one vocabulary. Every parameter is a plain float, int, colour, vector of floats, or a
texture handle. No Kool type crosses into a game, and no shader type goes in a component.

### 1. Material — how one thing looks

```kotlin
// hollow/game/src/commonMain/.../WaterMaterial.kt
object Water : UdeaMaterial("water") {
    val waveHeight by float(default = 0.3f, range = 0f..2f)
    val tint by color(default = Color(0.2f, 0.5f, 0.7f))
    val foam by texture()

    override fun surface(input: SurfaceInput): Surface = surface {
        val wave = sin(input.worldPosition.x * 4f + time) * waveHeight
        baseColor = tint * (1f + wave * 0.2f)
        roughness = 0.1f
        emissive = foam.sample(input.uv).rgb * wave
    }
}
```

- `ModelRenderer` and the sprite renderer take a material instead of always using the built-in one.
- `SurfaceInput` gives world position, normal, UV, vertex colour and the render-side `time` in
  seconds. It cannot read the simulation: seconds exist only in `udea-render` (`AGENTS.md`), and the
  signature is the enforcement, exactly as `OverlaySystem` does it.
- The built-in PBR and unlit shaders become two materials written against this same surface, so the
  API is proved by the engine's own use of it rather than by an example.

### 2. Screen pass — how the whole picture looks

The same declaration with a screen input instead of a surface one: colour, depth, an optional
object-id buffer, and the render resolution.

```kotlin
object Palette : UdeaScreenPass("palette") {
    val palette by texture()
    override fun pixel(input: ScreenInput): Color = nearestIn(palette, input.color)
}
```

Issue #259 (robot-game R3) asks for exactly this and should be built as the first user of it, with
`PalettePass` and `OutlinePass` shipped as built-ins. A game orders its passes in `WindowConfig`;
they run at render resolution, before the upscale, and are excluded from the editor's gizmo capture
like every other presentation layer.

### 3. Compute — work on the GPU that never touches the simulation

```kotlin
object ParticleStep : UdeaCompute("particle-step") {
    val particles by buffer<ParticleLayout>(readWrite = true)
    val dtSeconds by float()
    override fun main(id: ThreadId) { ... }
}

// in a RenderSystem:
compute.dispatch(ParticleStep, groups = particles.count / 64)
```

Two rules make this safe rather than a determinism hole:

- **A compute result may never reach the simulation.** `dispatch` is only callable from a
  `RenderSystem`, and there is no read-back into world state. A GPU is not bit-identical across
  drivers, so a simulation that read one would desync every client and break every replay. This is
  the single most important line in the design, and it is structural: the sim modules cannot see the
  API at all, because it lives in `udea-render`.
- **Compute is optional hardware.** It needs GL 4.3+ or WebGPU; some Android drivers have neither.
  `render.capabilities.compute` answers before use, a shader declares a CPU fallback or declares that
  it requires compute, and a game that requires it fails at load with a diagnostic rather than
  drawing nothing.

## Assets, editing and errors

- **A shader is an asset.** It is discovered by KSP like everything else and gets a typed accessor,
  so a material is `Materials.Water`, beside `Fox.Clips.Walk` and `Chassis.Nodes.socket_roof`.
- **A material instance is data on an entity**: an asset index plus its parameter values, which are
  plain floats — so it saves into a `.udealevel`, replicates if a game marks it `@Net`, and is
  editable by the agent tool surface without any new machinery.
- **The editor gets a material inspector**: every declared parameter becomes a slider, colour well or
  asset picker from its declaration, with the range the declaration gives. Editing writes through
  `editor.*` like every other edit, so undo and the agent see it.
- **Errors are `UdeaDiagnostic`s** with a rule id and a source span, capped and root-cause-first, the
  same as every other error in this engine. A shader that fails to compile on a backend names the
  backend and the line.
- **Hot reload** rides the existing asset delta path: a changed shader recompiles and swaps on the
  `SimBarrier`, no restart.

## Tickets, in order

| # | Ticket | Needs |
|---|---|---|
| S1 | `UdeaMaterial`: the declaration, parameters, the surface DSL over KSL, and the built-in PBR and unlit shaders rewritten as materials | - |
| S2 | Materials as assets: KSP accessors, the material component, level save, hot reload, diagnostics | S1 |
| S3 | `UdeaScreenPass` and the ordered post-process list, with `PalettePass` and `OutlinePass`; closes #259 | S1, #258 |
| S4 | The editor's material inspector, parameters driven from their declarations | S2 |
| S5 | `UdeaCompute`: buffers, dispatch from a `RenderSystem`, the capability gate, and a GPU particle step as its first user | S1 |

S1 is the ticket that decides whether this is clean or not; the rest follow its vocabulary. S5 is
last because a compute API with no user is a guess.

## What this deliberately does not do

- **No render-graph authoring.** Passes are an ordered list, not a graph. A graph is the right answer
  for a bigger engine and the wrong first API.
- **No node-graph shader editor.** Text first; a graph can emit the same declarations later.
- **No raw GLSL escape hatch in v1.** It would be the thing every game reached for, and it would be
  desktop-only. If a real need appears, it arrives as its own ticket with its own portability story.
