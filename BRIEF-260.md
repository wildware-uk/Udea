# BRIEF-260 — sockets: a part mounted on a named node of another entity's model

SHA: `a4a656c` - the change itself. This brief is committed on top of it, so the branch tip names the
brief commit and `a4a656c^..<tip>` is the whole branch.

Branch `issue-260-model-sockets`, off `origin/master`. Issue #260, part of epic #256 (robot-game).

## The evidence command

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew :udea-render:udeaGlTest --tests '*GlSocketMountTest*' -Pudea.render.requireGl=true
```

It draws the real thing: a chassis GLB with five `socket_*` Empties, a turret module GLB mounted on
each, two more parts mounted on the roof turret's own sockets, and the chassis's one animation
turning the ring the roof socket sits on. It asserts on the pixels and leaves four PNGs plus an
eight-frame spin sequence in `udea-render/build/reports/udea/gl/`.

**It goes red when the feature is reverted.** Two mutations, both run, both with their literal diff
and failing test below (M7 and M8). The sharpest is M7: neutralise `ModelStage.socket` so the
renderer never reads the live node, and the part on the turning mount stops moving — the assertion
prints the *identical* span before and after:

```
the block on the turning mount did not move: Span(count=589, left=367, right=393, top=175, bottom=200) then Span(count=589, left=367, right=393, top=175, bottom=200)
```

## What this adds

A part is an ordinary entity with its own `Transform3D` and its own model. `AttachedTo(parent,
node, offset…)` says which socket of which parent it sits in, and the engine keeps its transform
written from the parent's.

- **`ModelNode`** (`udea-core`) is the typed node handle: index, name, and the node's rest
  transform as plain floats. The asset build generates one per named node of a model, in an
  `object Nodes` beside the existing `object Clips` — `Chassis.Nodes.socket_roof` next to
  `Fox.Clips.Walk`.
- **`AttachedTo`** (`udea-core`, `@Replicated`, `@Serializable`) holds a `NetId` parent, the node
  index, the node's rest transform and the part's own offset. Every field is `@Net`.
- **`AttachmentSystem`** (`udea-core`, `SimPhase.PostPhysics`) writes each mounted part's
  `Transform3D` as parent transform × socket rest place × offset, resolving parents first so a
  part on a part on a chassis is right within the one tick. It takes no clock and no random.
- **The renderer** (`ModelRenderSystem`, `ModelStage`) draws a mounted part from the parent's
  **live** Kool node instead, after the pose is applied — so a socket on a turning ring or an
  animated bone carries what is mounted on it in the picture.
- **`UDEA0018`** is the build error for a node name the model does not have, with a did-you-mean,
  emitted by the same K2 checker that already does clips (`UdeaAnimationClipChecker` became
  `UdeaGeneratedMemberChecker`, parameterised, with a `Clips` and a `Nodes` instance).
- **`example-assets/models/chassis/`** is the fixture: `chassis.glb`, `turret.glb` and the
  headless-Blender `build_chassis.py` that makes them, checked in beside what it makes.

### Decisions (each also commented on the issue)

| Decision | Alternative rejected |
|---|---|
| The sim places a mount from the node's **rest** transform; the renderer from the **live** node. | Evaluating node matrices in the sim at `Animator.clipTime(now)` — that is #261, and it would put skeletons and node hierarchies in the headless kernel. |
| The node's rest transform is **baked into the component** (ten floats). | A runtime lookup — impossible in the sim, which never knows what model an entity draws: `ModelRenderer` is render-only and unreplicated. |
| An unresolvable parent leaves the part where it last stood, mount intact. | Removing `AttachedTo` from inside a system — a structural change a client that has not been sent the parent yet would apply wrongly and permanently. |
| A socket's **scale** scales the part, so a small socket takes a small module. | Ignoring it, and inventing a second concept for robot-game's `module_size`. |
| `GltfLoadConfig.removeEmptyNodes = false`. | Kool's default is `true` and deletes every socket Empty at load. M8 below is what that looks like. |
| The parent is a `NetId`, not a Fleks `Entity`, whatever the issue sketch says. | (Instructed, and it is the frozen entity-identity contract.) |

### Two things the issue asked for that are not here, deliberately

- **`module_size` custom properties** on the socket Empties. The issue lists them as chassis
  authoring data; nothing in the acceptance criteria reads one, and a socket's scale already
  expresses "this socket takes a small module" using a value the file carries natively. Adding a
  glTF `extras` reader is a self-contained follow-up.
- **Skin-joint parenting.** A socket parented to a *bone* is read from the file the same way any
  node is, and the renderer resolves whatever Kool node carries that name; the fixture exercises an
  animated **object** node (the ring), not a skinned joint. `GltfNodesTest` reads the Fox's bones
  and checks where its head bone lands, so the extraction path is exercised on a skinned model, but
  no picture in this branch shows a part on a moving bone.

## `sh gradlew build`

```
$ ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
    sh gradlew build --continue --max-workers=4 --console=plain
...
BUILD SUCCESSFUL in 49s
975 actionable tasks: 110 executed, 865 up-to-date
```

No exclusions. Spliced from
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue260/build-2.log`,
lines 1632-1633; the elision covers the task list. It is the same `build` task as the run below,
re-run after the three failures were fixed, so Gradle re-executed those tasks and everything
downstream of them - 110 of 975. `build-1.log`, `build-2.log` and `gl-suite.log` are all in that
directory.

**The run before it was red three times, all mine, and both causes are worth knowing:**

```
> Task :udea-core:compileTestKotlinIosArm64 FAILED
e: file:///.../udea-core/src/commonTest/kotlin/dev/wildware/udea/core/spatial/AttachmentSystemTest.kt:115:9 Name contains illegal characters: ",".
e: file:///.../udea-core/src/commonTest/kotlin/dev/wildware/udea/core/spatial/AttachmentSystemTest.kt:141:9 Name contains illegal characters: ",".
```

(spliced from `build-1.log` lines 954-956, with the absolute path shortened, which is the only edit)

Kotlin/Native refuses a comma in a backtick-quoted test name. JVM, Android and wasmJs all accept
one, so it only appears in a full build, in a module that has an iOS target.

I grepped every `commonTest` source set in the repository for a backtick name containing a comma.
There is exactly one other, and it is *not* a latent failure: `udea-agent`'s
`GeneratedToolDispatchTest > the index carries the generated description, not an empty one`.
`udea-agent` is on `udea.kotlin-multiplatform-no-ios`, so Kotlin/Native never compiles it - which is
the real reason `master` is green, rather than nobody having used a comma. `udea-core` is on the
full convention, iOS included, so mine had to go. Both renamed.

```
Execution failed for task ':udea-core:udeaCheckProtocolLock'.
> :udea-core: the generated protocol and the checked-in net-protocol.lock disagree. Every client already speaking this protocol and every recorded replay breaks with this change. If it was intended, rewrite the lock with `gradlew :udea-core:udeaWriteProtocolLock` and review the diff.
  first difference at line 16:
    checked in: protoHash 0x35f2
    generated:  protoHash 0xcd86
```

(spliced from `build-1.log` lines 1835-1839, one consecutive run, no elision)

There are three `net-protocol.lock` files, not one: `udea-codegen`, `udea-core` and `moba/game`.
Adding a replicated component to `udea-core` moves `udea-core`'s; see "Regenerated files".

## The GL run

```
$ xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
    ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
    sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true \
    --max-workers=3 --console=plain
...
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest
> Task :udea-editor:udeaEditorGlTest
...
BUILD SUCCESSFUL in 1m 58s
126 actionable tasks: 12 executed, 114 up-to-date
```

Spliced from `gl-suite.log`: the command as it was run, then lines 280-282 and 286-287. The `...`
covers a blank line and Gradle's "Problems report is available at" line. Counted out of the JUnit XML those tasks wrote:
`udeaGlTest` 25 tests, `udeaAgentGlTest` 2, `udeaEditorGlTest` 6, none skipped, none failed. They
really ran: with no `DISPLAY` and without `-Pudea.render.requireGl=true` they skip silently, which
is why this section exists.

## Images

All in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`, all produced by the evidence command
above (the mutation frame by M8).

| File | What it shows | What it proves |
|---|---|---|
| `issue260-chassis-five-modules.png` | The chassis with an orange turret module on each of its five sockets - two on the flanks facing outwards, two on the nose and tail at 0.6 scale, one on the roof ring - a green block on the roof turret's own top socket and a violet one on its muzzle. | Five modules sit at their own sockets, turned the way the socket is turned and scaled the way it is scaled; and a part on a part on a chassis is placed correctly (two levels). |
| `issue260-animated-socket-half-turn.png` | Half of the ring's turn later. The violet muzzle block has crossed to the other side of the ring; the four hull modules are exactly where they were. | A part on an animated node follows the animation, two mounts down, while parts on fixed nodes do not move. |
| `issue260-assembly-rides-the-chassis.png` | The chassis driven 2.5m across the frame with every part still on it. | The whole assembly rides the parent. |
| `issue260-spin-sequence.png` | Eight frames, one every 30 ticks of the 240-tick spin, tiled. | The same thing as a sequence: the violet block goes right round the vehicle, the green one stays on the axis, the hull modules never move. |
| `issue260-spin-00.png` ... `issue260-spin-07.png` | The eight frames of that sequence at full size, one per known tick. | As above, tile by tile. |
| `issue260-mutation-kool-removes-empties.png` | Mutation M8: Kool's default `removeEmptyNodes = true`. Every socket Empty is deleted at load, no part finds a node, and the whole assembly collapses onto the hull - no orange visible at all. | That the `removeEmptyNodes = false` line is load-bearing, not decoration. |

## The issue, criterion by criterion

| Criterion | Proof |
|---|---|
| **Mount 5 modules on a chassis. The modules follow the chassis as it moves and turns** | `AttachmentSystemTest > five modules on five sockets each sit where their own socket is` and `> a part mounted on a socket rides the parent as it moves and turns` (headless, exact numbers). Pictures: `issue260-chassis-five-modules.png`, `issue260-assembly-rides-the-chassis.png`. |
| **...and a module on an animated node follows the animation** | `GlSocketMountTest` step 3, which measures the violet block's span before and after half a ring turn and requires more than 100px of travel; `issue260-animated-socket-half-turn.png` and `issue260-spin-sequence.png`. Mutation M7 turns that assertion red with a zero-pixel move. |
| **A module mounted on another module's socket (two levels) sits in the right place** | `AttachmentSystemTest > a part mounted on another part's socket lands two levels down` and `> a chain is right on the tick it is built whatever order the parts were made in` (the second builds the chain in every creation order, so it fails if resolution is not parent-first - mutation M2). Picture: the green and violet blocks in every frame. |
| **Swapping a module at runtime keeps determinism (replay equality)** | `AttachmentDeterminismTest > two independent runs of a mount, a swap and a detach agree tick for tick` (two separately built worlds, hash-for-hash over 90 ticks, `DivergenceReport` on failure, plus a guard that the world actually moved) and `> a run resumed from a snapshot taken before the swap reproduces the world it was captured from`. `AttachmentSystemTest > swapping the part in a socket puts the new one where the old one was` is the behaviour itself. |
| **Generated socket names** | `udea-assets-compiler/src/test/resources/golden/Fox.kt.txt` now carries an `object Nodes` with the Fox's bones; `ModelClipAccessorsTest > the fox generates the golden Fox object`, `> a model with nodes and no clips still gets an object, with its nodes and no Clips`, `> a node name that cannot be an identifier is made into one, and a clash is numbered`. `GltfNodesTest` (13 tests) pins the numbers those come from, against the real files. |
| **...and a build error for a missing node name** | `UdeaGeneratedMemberCheckerTest > a misspelled socket is an error at the name, with a did-you-mean` (asserts `Did you mean 'socket_roof'?`, the rule id `UDEA0018`, and the exact line and column of the squiggle), `> a name like no node at all lists the nodes the model has`, `> correctly spelled sockets compile clean`, `> Suppress by the node rule id silences the did-you-mean but not the compile error`. Mutation M6 takes the node checker out of the registrar and three of them go red. |
| **Replicates over `udea-net`, survives snapshots and replays** (proposal text) | `AttachedToSnapshotTypeTest` (3 tests, including the per-field name/mask-bit/store-column alignment loop the replicator contract requires), `udea-core/net-protocol.lock` (component 28, 18 fields), and the snapshot-resume determinism test above. |
| **Detaching turns the part into a free entity at its current world transform** (proposal text) | `AttachmentSystemTest > a detached part stays where it was and becomes a free entity` and `> a part whose parent is gone keeps its last transform and its mount`. |

## Self-review against the reject list

Read against `docs/engineering-standards.md` section 8 and the `AGENTS.md` do-not list, on my own
diff, because these are the cheap ones to catch here rather than in a review round.

- **`public` nobody outside the module uses.** `ModelNode`, `AttachedTo` and `AttachmentSystem` are
  the game-facing API. `MountFrame` (the 3x4 the composition runs in) is `internal`; so are
  `GltfNode` and `GltfNodes` in the asset compiler and the new members of `ModelStage.Placed`.
- **A swallowed exception.** There is one `catch` in the new code,
  `GltfNodes.nodesOf`'s `catch (_: SerializationException)`. It turns a malformed file into a
  `Result.failure` carrying "is not a glTF 2.0 file: its JSON does not parse", and it is a literal
  copy of what `GltfClips.clipsOf` already does five files away - the same handling for the same
  input, deliberately not a second spelling of it.
- **Wall clock, unseeded randomness, reflection on a per-tick path.** None: `AttachmentSystem` reads
  components and writes one, its family and its three `MountFrame`s are resolved once at
  construction, and it allocates nothing per tick. Grep of the new `udea-core` files for
  `currentTimeMillis|nanoTime|Instant.now|Math.random|Random.Default` returns nothing.
- **GL outside `udea-render`.** The new `udea-core` files import no Kool and no GL; the live-node
  resolution is entirely inside `ModelStage`, and `ModelRenderSystem` is a `RenderSystem`, not a
  Fleks system.
- **A bare `Int` for a domain concept.** `AttachedTo.node` is an `Int`. The typed value is
  `ModelNode`, which is what a caller passes (`AttachedTo(chassis, Chassis.Nodes.socket_roof)`); the
  component stores the lowered index and ten floats because a replicated component's fields are
  primitives with a `FieldKind` each, exactly as `Transform3D` stores nine floats rather than a
  vector. The secondary constructor is the seam, and `ModelNode.NONE` is the empty value.
- **A `docs/contracts/` file changed.** None. `udeaVerifyContracts` passes on `check`.
- **`fieldNames[i]` == mask bit *i* == store column *i*.** `AttachedToSnapshotTypeTest >
  each field's name, mask bit and store column agree` walks every field and asserts it, the way
  `AnimatorSnapshotTypeTest` does.
- **`AGENTS.md` left stale.** Updated in the same commit: a "What the engine does today" entry for
  #260, and the `example-assets/` paragraph, whose "kept for two readers" was **already wrong before
  this branch**: `udea-render`'s GL tests read `models/fox` out of it too
  (`GlImportedModelRenderTest`, `GlSkinnedModelRenderTest`, `ModelShot`, `SkinnedPoseTest`).
  Replaced the count with the list, which is the shape that cannot go stale the same way.

### Why there is no bridge session in this brief

The table in my brief says a ticket about something a person sees should be driven for real.
It was: `GlSocketMountTest` boots a real Kool context over LWJGL, loads two real `.glb` files
through the real model loader and captures the real frame buffer. What it is *not* is `moba`,
because `moba` has no mounted parts to look at - nothing in the example game builds a unit out of
modules yet. Launching it over the bridge would have produced screenshots of a MOBA that this change
does not alter.

## Mutation table

Each row is a real run: the literal `git diff` of the mutation, taken from that run, and the
tests that went red. The tree was clean before and after each one (`git status` shows only this
brief).

### M1-no-registration

The system is never registered, so nothing places a mount.

`udea-core/src/commonMain/kotlin/dev/wildware/udea/core/module/CoreModule.kt`

```diff
@@ -107,5 +107,5 @@ public class CoreModule(
         // before `Gameplay` reads a socket (issue #260). Its dependency is this module's own id
         // index, so it names it in its constructor rather than reaching through the context.
-        registry.add(SimPhase.PostPhysics, { _ -> AttachmentSystem(netIds) })
+        // registry.add(SimPhase.PostPhysics, { _ -> AttachmentSystem(netIds) })
     }
 
```

**`sh gradlew :udea-core:jvmTest`** → 10 failing:

- `AttachmentSystemTest > a chain is right on the tick it is built whatever order the parts were made in()[jvm]`
- `AttachmentSystemTest > a detached part stays where it was and becomes a free entity()[jvm]`
- `AttachmentSystemTest > a part mounted on a socket rides the parent as it moves and turns()[jvm]`
- `AttachmentSystemTest > a part mounted on another part's socket lands two levels down()[jvm]`
- `AttachmentSystemTest > a part whose parent is gone keeps its last transform and its mount()[jvm]`
- `AttachmentSystemTest > a socket that faces out of the hull turns the part with it()[jvm]`
- `AttachmentSystemTest > five modules on five sockets each sit where their own socket is()[jvm]`
- `AttachmentSystemTest > swapping the part in a socket puts the new one where the old one was()[jvm]`
- `AttachmentSystemTest > two parts mounted on each other fail loudly rather than recursing for ever()[jvm]`
- `CoreModuleManifestGoldenTest > the CoreModule manifest matches the golden file()[jvm]`

### M2-no-parent-first

A mounted parent is no longer placed before its child, so a chain is one tick behind and the cycle guard never runs.

`udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/AttachmentSystem.kt`

```diff
@@ -74,5 +74,5 @@ public class AttachmentSystem(private val netIds: NetIdIndex) : SimSystem() {
                     "${parent[AttachedTo].parent}"
             }
-            place(parent, budget - 1)
+            if (budget < 0) place(parent, budget - 1)
         }
         // Read after the parent is placed: that call is what puts this tick's numbers in it.
```

**`sh gradlew :udea-core:jvmTest`** → 2 failing:

- `AttachmentSystemTest > a chain is right on the tick it is built whatever order the parts were made in()[jvm]`
- `AttachmentSystemTest > two parts mounted on each other fail loudly rather than recursing for ever()[jvm]`

### M3-no-socket

The socket is dropped from the composition: a part lands on its parent’s origin.

`udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/AttachmentSystem.kt`

```diff
@@ -78,5 +78,4 @@ public class AttachmentSystem(private val netIds: NetIdIndex) : SimSystem() {
         // Read after the parent is placed: that call is what puts this tick's numbers in it.
         world3d.set(parentTransform)
-            .mul(socket.setNode(mount))
             .mul(offset.setOffset(mount))
             .writeTo(part[Transform3D])
```

**`sh gradlew :udea-core:jvmTest`** → 8 failing:

- `AttachmentSystemTest > a chain is right on the tick it is built whatever order the parts were made in()[jvm]`
- `AttachmentSystemTest > a detached part stays where it was and becomes a free entity()[jvm]`
- `AttachmentSystemTest > a part mounted on a socket rides the parent as it moves and turns()[jvm]`
- `AttachmentSystemTest > a part mounted on another part's socket lands two levels down()[jvm]`
- `AttachmentSystemTest > a part whose parent is gone keeps its last transform and its mount()[jvm]`
- `AttachmentSystemTest > a socket that faces out of the hull turns the part with it()[jvm]`
- `AttachmentSystemTest > five modules on five sockets each sit where their own socket is()[jvm]`
- `AttachmentSystemTest > swapping the part in a socket puts the new one where the old one was()[jvm]`

### M4-no-offset

The part’s own offset within the socket is dropped.

`udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/AttachmentSystem.kt`

```diff
@@ -79,5 +79,4 @@ public class AttachmentSystem(private val netIds: NetIdIndex) : SimSystem() {
         world3d.set(parentTransform)
             .mul(socket.setNode(mount))
-            .mul(offset.setOffset(mount))
             .writeTo(part[Transform3D])
     }
```

**`sh gradlew :udea-core:jvmTest`** → 1 failing:

- `AttachmentSystemTest > a socket that faces out of the hull turns the part with it()[jvm]`

### M5-conjugate-wrong-way

The Y-up to Z-up conjugation turns the wrong way (this is the sign error I made in the first draft).

`udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/gen/GltfNodes.kt`

```diff
@@ -202,6 +202,6 @@ internal object GltfNodes {
         for (column in 0 until STRIDE) {
             rows[column] = m[column]
-            rows[STRIDE + column] = -m[2 * STRIDE + column]
-            rows[2 * STRIDE + column] = m[STRIDE + column]
+            rows[STRIDE + column] = m[2 * STRIDE + column]
+            rows[2 * STRIDE + column] = -m[STRIDE + column]
         }
         val out = FloatArray(SIZE)
```

**`sh gradlew :udea-assets-compiler:test`** → 7 failing:

- `GltfNodesTest > a node under a parent carries the parent's turn and scale()`
- `GltfNodesTest > a node's place comes out in the world's Z-up frame, not the file's Y-up one()`
- `GltfNodesTest > a socket turned about the file's up axis comes out turned about the world's()`
- `GltfNodesTest > the chassis fixture's sockets are where its build script put them()`
- `GltfNodesTest > the fox's bones are read, each placed under every bone above it()`
- `GltfNodesTest > the turret fixture has a half-scale socket on its roof and one at its muzzle()`
- `ModelClipAccessorsTest > the fox generates the golden Fox object()`

### M6-no-node-checker

The node checker is taken out of the FIR registrar, so a misspelled socket gets only Kotlin’s own unresolved reference.

`udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaFirExtensionRegistrar.kt`

```diff
@@ -89,5 +89,5 @@ internal class UdeaFirAdditionalCheckers(
          */
         override val propertyAccessExpressionCheckers: Set<FirExpressionChecker<FirPropertyAccessExpression>> =
-            setOf(UdeaGeneratedMemberChecker.Clips, UdeaGeneratedMemberChecker.Nodes)
+            setOf(UdeaGeneratedMemberChecker.Clips)
 
         /** Issue #192: loops in a `.udea.kts`. [UdeaAssetLoopChecker] is silent in any other file. */
```

**`sh gradlew :udea-compiler-plugin:test`** → 3 failing:

- `UdeaGeneratedMemberCheckerTest > a missing clip is the clip rule and a missing node is the node rule()`
- `UdeaGeneratedMemberCheckerTest > a misspelled socket is an error at the name, with a did-you-mean()`
- `UdeaGeneratedMemberCheckerTest > a name like no node at all lists the nodes the model has()`

### M7-renderer-ignores-live-node

The renderer never resolves the live Kool node, so every part falls back to the rest-pose transform the simulation wrote.

`udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt`

```diff
@@ -267,5 +267,5 @@ internal class ModelStage(
      */
     fun socket(entity: Int, node: Int, out: MutableMat4f): Boolean {
-        if (node < 0) return false
+        if (node >= 0) return false
         val placed = drawnFor(entity) ?: return false
         val koolNode = placed.nodeAt(node) ?: return false
```

**`xvfb-run -a -s -screen 0 1280x720x24 sh gradlew :udea-render:udeaGlTest --tests *GlSocketMountTest* -Pudea.render.requireGl=true --max-workers=3 --console=plain`** → 1 failing:

- `GlSocketMountTest > five modules and a module on a module are drawn at their sockets, animated node included()`
- `org.opentest4j.AssertionFailedError: the block on the turning mount did not move: Span(count=589, left=367, right=393, top=175, bottom=200) then Span(count=589, left=367, right=393, top=175, bottom=200)`

### M8-kool-removes-empty-nodes

Kool's default `removeEmptyNodes = true`: every socket Empty is deleted at load.

`udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt`

```diff
@@ -714,5 +714,5 @@ internal fun gltfLoadConfig(model: ImportedModel, shadowMaps: List<ShadowMap>):
     // defaults to true in 0.19.0). Dropping them would delete every mounting point in the file
     // before anything could be mounted on one (issue #260).
-    removeEmptyNodes = false,
+    removeEmptyNodes = true,
     assetLoader = model.loader,
     // The file's material, with the same uniform ambient light the built-in shapes get; its
```

**`xvfb-run -a -s -screen 0 1280x720x24 sh gradlew :udea-render:udeaGlTest --tests *GlSocketMountTest* -Pudea.render.requireGl=true --max-workers=3 --console=plain`** → 1 failing:

- `GlSocketMountTest > five modules and a module on a module are drawn at their sockets, animated node included()`
- `org.opentest4j.AssertionFailedError: the modules are not drawn: Span(count=0, left=0, right=0, top=0, bottom=0)`

## Regenerated files

| File | What moved | How |
|---|---|---|
| `net-components.lock` | `dev.wildware.udea.core.spatial.AttachedTo` added between `spatial.Animator` and `spatial.Transform3D`, with a paragraph saying why the component is on the wire. This is the hand-edited, reviewed file. | by hand, as its header requires |
| `udea-core/net-protocol.lock` | `AttachedTo` becomes component **28** with 18 fields; `Transform3D` moves **28 to 29**; `protoHash 0x35f2` to `0xcd86`. Nothing else in that file moved: every other component in it sorts before `spatial.AttachedTo`. | `sh gradlew :udea-core:udeaWriteProtocolLock` |
| `udea-core/src/jvmTest/resources/golden/core-module-systems.txt` | one line added: `PostPhysics dev.wildware.udea.core.spatial.AttachmentSystem`. | `sh gradlew :udea-core:jvmTest -Dupdate.goldens=true` |
| `udea-assets-compiler/src/test/resources/golden/Fox.kt.txt` | gains `public object Nodes`, one `ModelNode` per named node of the Fox. | regenerated from the run's own `Fox.kt.actual.txt` |

**Three things that did *not* move, checked rather than assumed:**

- `udea-codegen/net-protocol.lock` - `udeaWriteProtocolLock` rewrote it and `git status` showed no
  change afterwards. It pins the *codegen fixtures'* protocol, and no fixture component sorts after
  the new name.
- `udea-codegen/src/test/resources/expected-generated-hashes.txt` - unchanged for the same reason:
  `:udea-codegen:test` passes with no `-Pudea.updateGeneratedHashes`.
- `moba/game/net-protocol.lock` - moba's own components are ids 0-15 and sort before every
  `dev.wildware.udea.*` name, so none of them moved and `:moba:game:udeaCheckProtocolLock` passes.
  moba's recorded replay fixtures did not need re-recording either; `:moba:desktop:test` is green in
  both full builds.

## What I did not exercise

- **A part on a skinned joint.** The animated node in the fixture is an object node (the ring). The
  node-reading path runs against the Fox's skeleton in `GltfNodesTest`, but nothing in this branch
  draws a part on a moving bone.
- **A mount over a real network transport.** The component is `@Net`, and the snapshot round trip
  and the field name/mask/column alignment are tested, but no test here sends one through `udea-net`.
- **A mount in a saved level file.** `AttachedTo` is `@Serializable`, so a level save carries it;
  there is no test in this branch that writes one out and reads it back.
- **`module_size`.** Not read anywhere - see the deliberate omissions above.
