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

## Materials and shaders are different things

Owner, on revision (dashboard, 2026-09-20): *"I think 'materials' and 'shaders' should be split
different, shaders are anything that is rendered, effects, screen effects etc, and materials are
specifically applied to objects, and have set fields (albedo, normal, diffuse etc), I'm talking
specifically about pixel, fragment and compute shaders."*

The first draft of this document conflated the two: it used "material" to mean "a surface shader a
game writes", which is why it then had to argue about how much of the vertex stage a game could be
trusted with. Split properly, that argument disappears.

- A **material** is **data on an object**. Named slots - albedo, normal, metallic, roughness,
  emissive - and nothing else. It contains no code, so it cannot break skinning, instancing or the
  shadow pass, because it is not in a position to.
- A **shader** is **a program**: fragment, vertex, compute. Screen effects, object effects, anything
  rendered.

## Why GLSL and not a Kotlin DSL

The first draft chose Kool's KSL - a Kotlin DSL each backend turns into its own shading language -
and gave three reasons. One of them was wrong, and it was the load-bearing one.

**The wrong reason: "one shader, every target."** KSL's real advantage is a backend that does not
speak GLSL: Kool's WebGPU path wants WGSL. **Udea has no such target.** Web is shelved (#223, #226),
Kool publishes no wasmJs artifact, and Kool has no iOS backend, so `udea-render` builds for `jvm` and
`android` alone - OpenGL and GLES, both of which take GLSL. The DSL was buying portability to
platforms this engine does not ship to, and charging a real cost for it.

**The cost it charged** is that a shader became Kotlin rather than a `.frag` anyone can paste in,
read, or carry between engines. For the thing games actually want first - a screen effect - that is
the whole ergonomic budget spent on a guarantee nobody is collecting.

Issue #266 asked for the right thing in its own words before this document was revised: *"Fragment
source (or an engine-wrapped shader object) plus typed uniforms."*

**What the DSL was genuinely protecting**, and how GLSL keeps it:

- **No Kool type in a game.** A GLSL shader is a string and a set of typed uniforms, so
  `UDEA-MG-002` is satisfied by construction rather than by care.
- **No string-built code.** `AGENTS.md` forbids *generating* code by concatenation. A shader authored
  as text in its own file is not generated code, any more than a `.udea.kts` is.
- **Errors that name a line.** This is the one the DSL got for free and GLSL has to earn: a compile
  failure must arrive as a `UdeaDiagnostic` with the file, the line and the driver's message, not a
  stack trace. That is a ticket requirement, below.

**The one real cost of GLSL, stated plainly:** GL and GLES differ in `precision` qualifiers and
version pragmas, so the engine prepends the right header per backend. A game that writes its own
`#version` line is a shader that works on desktop and fails on Android with a compiler error its
author cannot read.

## The shape

Two kinds, not three. A **material** is data; a **shader** is a program, and a compute shader is a
shader like the others. Every parameter a game supplies is a plain float, int, colour, vector or
texture handle. No Kool type crosses into a game, and no shader or material type goes in a component
- an entity carries an asset index and plain values, nothing else.

### 1. Material - data on an object

Fixed slots, no code:

```kotlin
val hull = UdeaMaterial(
    albedo = texture("robot/hull_albedo"),
    normal = texture("robot/hull_normal"),
    metallic = 0.9f,
    roughness = 0.35f,
    emissive = Color(0f, 0.8f, 1f),
)
```

`ModelRenderer` and the sprite renderer take a material instead of always using the built-in one.
The engine chooses the shading; the built-in PBR and unlit paths become the default material, so the
API is proved by the engine's own use of it rather than by an example.

Because a material holds no code, GPU skinning from `Animator`, instancing and the shadow pass are
untouched by anything a game can write here. That is the whole reason for the split.

### 2. Shader - a program

A fragment body in its own `.frag`, declared as an asset, plus typed uniforms declared in Kotlin.
The engine supplies the inputs and prepends the backend's header.

```kotlin
// assets/shaders/shaders.udea.kts
shader(name = "palette", file = "shaders/palette.frag")
```

```kotlin
val palette = UdeaShader.fragment(GameAssets.shaders.palette, assets) {
    float("uLevels", 16f)
    texture("uRamp", ramp)
}
```

The first draft of this section passed the *path* as `source`, which is the shape that made a game
read the file itself; see "Assets, editing and errors" below for why the text travels in the asset
instead.

```glsl
// shaders/palette.frag - the engine supplies uColor, uDepth and uMask
vec4 udeaMain(vec2 uv) {
    vec3 c = texture(uColor, uv).rgb;
    float edge = udeaOutline(uMask, uv, 1.0);
    return vec4(mix(quantise(c, uLevels), OUTLINE, edge), 1.0);
}
```

**As a screen effect**, it reads the colour buffer, depth, and an object/mask buffer - so a 1-pixel
outline does not need the scene rendered twice, which is #266's second acceptance criterion. A game
orders its passes in `WindowConfig`; they run at render resolution, before the upscale, and are
excluded from the editor's gizmo capture like every other presentation layer. **Issue #259 asks for
exactly this** and is built as its first user, with a palette and an outline shipped as built-ins.

**On an object**, the same fragment body, with the engine owning the vertex stage: the game receives
the already-skinned position, normal and UV as inputs rather than inheriting the job of computing
them. A game that wants a damage flash or a build-in dissolve writes twelve lines and keeps every
animated model working.

### 3. Compute - a shader that never touches the simulation

```kotlin
val fog = UdeaCompute(source = "shaders/fog.comp", groups = 64)

// in a RenderSystem:
compute.dispatch(fog, groups = tiles / 64)
```

Two rules make this safe rather than a determinism hole:

- **A compute result may never reach the simulation.** `dispatch` is callable only from a
  `RenderSystem`, and there is no read-back into world state. A GPU is not bit-identical across
  drivers and vendors, so a simulation that read one would desync every client and break every replay
  and every rewind - silently, which is the worst way. Compute may light, cull, and decide what is
  drawn; it may not decide what happens. This is structural, not a convention: the simulation modules
  cannot see the API at all, because it lives in `udea-render`.
- **Compute is optional hardware.** It needs GL 4.3+ or GLES 3.1; some Android drivers have neither.
  `render.capabilities.compute` answers before use, a shader either declares a CPU fallback or
  declares that it requires compute, and a game that requires it fails at load with a diagnostic
  rather than drawing nothing.

## Assets, editing and errors

- **A shader is an asset, and it is declared.** Not discovered: a `.frag` is written into a
  `.udea.kts` with `shader(name = "scanlines", file = "shaders/scanlines.frag")`, and the asset
  build reads it, checks it, packs it and generates the accessor - the same five passes every
  other file-backed asset goes through. The accessor follows the one naming convention the
  generator already has, `GameAssets.<folder>.<name>`, so it is `GameAssets.shaders.scanlines`
  beside `GameAssets.models.fox`. An earlier draft of this document said "discovered by KSP" and
  named it `Materials.Water`; both were wrong. KSP processes annotated Kotlin and has never seen
  an asset tree, and a second naming scheme for one asset kind is the defect #269 is about.
- **The GLSL travels inside the asset, not as a path to it.** This is the one place a shader
  differs from a model, and the reason is multiplatform. A `Model` names a file and `udea-render`
  opens it on the platform it is running on; a game doing the same for a `.frag` writes
  `javaClass.getResource(...).readText()`, which is JVM-only, so it writes that line once per
  platform to load a file that is byte-identical everywhere. The build reads the file instead and
  packs the text as an ordinary string field, so it comes back out of `BundleReader` in
  `commonMain` on every target and there is no shader loader at all. A game writes
  `UdeaShader.fragment(GameAssets.shaders.scanlines, assets)`.
- **The `path`/`source` overload stays**, for GLSL a game genuinely makes up at run time - a
  material permutation, a node graph's output - and for the engine's own built-ins, whose source
  is a constant in `udea-render`. It is not what the documentation shows.
- **A material instance is data on an entity**: an asset index plus its parameter values, which are
  plain floats — so it saves into a `.udealevel`, replicates if a game marks it `@Net`, and is
  editable by the agent tool surface without any new machinery.
- **The editor gets a material inspector**: every declared parameter becomes a slider, colour well or
  asset picker from its declaration, with the range the declaration gives. Editing writes through
  `editor.*` like every other edit, so undo and the agent see it.
- **Errors are `UdeaDiagnostic`s** with a rule id and a source span, capped and root-cause-first, the
  same as every other error in this engine. A shader that fails to compile on a backend names the
  backend and the line (`UDEA0019`), a uniform declared in Kotlin that the source never declares is
  `UDEA0040`, and a declared `.frag` that is missing, empty, not a `.frag`, states its own
  `#version` or defines no `udeaMain` is `UDEA0041` at the declaration, with a did-you-mean over
  the `.frag` files that are there. The last one is a **build** failure rather than a launch
  failure on purpose: an asset shader is compiled into the `.udeapak` long before anything
  registers it, so a check left to registration ships a pack nobody can use.
- **Hot reload** rides the existing asset delta path: a changed shader recompiles and swaps on the
  `SimBarrier`, no restart.

## Tickets, in order

| # | Ticket | Needs |
|---|---|---|
| S1 | `UdeaShader.fragment` as a screen effect: GLSL source, typed uniforms, the ordered pass list, engine-supplied colour/depth/mask, the per-backend header, and compile failures as `UdeaDiagnostic`s naming file and line. Ships a palette and an outline. **Closes #259 and the first half of #266** | - |
| S1a | A `.frag` is a declared asset: the `shader(...)` DSL word, the `Shader` runtime type carrying the text the build read, `GameAssets.shaders.<name>`, `UdeaShader.fragment(ref, assets)` in `commonMain`, and `UDEA0041` for a `.frag` the build cannot use. The docs and the template stop showing `javaClass.getResource`. **Recorded as a decision on #269** | S1 |
| S2 | `UdeaMaterial`: the fixed slots, the default material the engine's own PBR and unlit paths are rewritten as, and materials as assets - KSP accessors, the component, level save, hot reload | - |
| S3 | A shader on an object: the same fragment body over an engine-owned vertex stage, with skinning, instancing and the shadow pass intact | S1, S2 |
| S4 | The editor's material inspector, slots driven from their declarations | S2 |
| S5 | `UdeaCompute`: buffers, dispatch from a `RenderSystem`, the capability gate, and one real first user | S1 |

S1 is the ticket that decides whether this is clean or not, and it is first because it is what
unblocks a real game today. S5 is last because a compute API with no user is a guess.

## What this deliberately does not do

- **No render-graph authoring.** Passes are an ordered list, not a graph. A graph is the right answer
  for a bigger engine and the wrong first API.
- **No node-graph shader editor.** Text first; a graph can emit the same declarations later.
- **No WGSL, and no second shading language.** GLSL is the language because GL and GLES are the
  backends. If a WebGPU backend ever lands, that is when a translation story is designed - and it is
  a real one, not a line in this document.
- **No shader authored as a Kotlin DSL.** The first draft chose that and it was wrong for the reason
  given above. A game writes GLSL in a file.
