# Module retirement ledger

Every file in the old tree, with a named disposition, a destination and a phase.

D1 keeps one repository and deletes old modules **as they are replaced**. Spec section 7 rates
the six phases in which two module trees coexist as the highest-impact insidious risk on the
project: if anything new picks up `common`, the globals come back and headless breaks somewhere
far from the cause. Delete-as-replaced only works if every old file has a disposition *before*
the new tree grows past it, which is what this file is.

Two Gradle gates read it, both on `check`:

| Task | Refuses |
|---|---|
| `udeaLegacyReport` | an old-tree Kotlin file with no row, a row naming a file that is gone, a duplicated row |
| `udeaVerifyMigration` | a `udea-*`/`moba` file that is a near-duplicate of an old file without a current review record |

```
./gradlew udeaLegacyReport udeaVerifyMigration
```

---

## Retirement order, and the gate that settles each module

| Module | Replaced by | Retires in | The gate that settles it |
|---|---|---|---|
| `level-editor` | nothing — D6 | **Phase 0, done** | `./gradlew projects` lists no `:level-editor` |
| `idea-plugin` | nothing — D6 | **Phase 0, done** | `./gradlew projects` lists no `:idea-plugin` |
| `compose-ui` | nothing — D6 | **Phase 0, done** | `./gradlew projects` lists no `:compose-ui` |
| root `src/` | nothing — a checked-in copy of a whole sample project | **Phase 0, done** | no `integrationTest` source set in `build.gradle.kts` |
| `gradle-plugin` | `udea-codegen` + `udea-gradle` | Phase 6 | `udeaLegacyReport` shows `gradle-plugin` at 0 remaining |
| `example` | `moba` | Phase 6 | Phase 6 exit: a full 5v5 played end to end on `moba` |
| `common` | the whole `udea-*` tree | Phase 6 | Phase 6 exit: `settings.gradle.kts` contains only the new modules |

The three D6 modules went first because they are the only ones with **nothing to wait for**.
The MCP tool surface *is* the editor (spec section 1), so there was no replacement to build and
no reason to carry them through six phases. Deleting them also removed the broken
`:level-editor:compileKotlin` that every build had been working around with `-x` flags.

### Why `common`, `example` and `gradle-plugin` stay

They have live consumers, and unlike the three above they have replacements that do not exist
yet:

- **`gradle-plugin` is a KSP processor for both `common` and `example`.** Removing it stops
  both compiling. `udea-codegen` replaces its generator and `udea-gradle` its tasks, but until
  the old modules stop being built, the old processor has to keep running.
- **`example` is the only thing exercising `common`.** It goes when `moba` covers what it
  demonstrates, which the Phase 6 exit criterion states as a full 5v5 from spawn to nexus kill.
- **`common` is consumed by both of the above.** It compiles as one unit: a file cannot leave
  it while anything in it still refers to that file.

That last point is why most rows have a `deletedIn` **later** than their `replacedIn`. The
replacement landing is one event; the old file becoming deletable is a different, later one.
Spec section 6 names the only two exceptions itself — the Phase 3 exit deletes KryoNet and the
old `Network*System` files — and those rows carry `deletedIn` 3.

Deleting a module is not this file's job. Each replacing epic owns its own deletion issue; this
ledger is the tracking artefact the Phase 6 final gate closes out.

---

## The columns

Generated from `git ls-files`, so the file is reproducible:

```
python scripts/gen-migration-ledger.py
```

The judgement — which module takes over which old file, and when — lives in the `RULES` table
in that script rather than in 140 hand-typed rows, so changing a disposition is a reviewable
one-line edit. The review columns are hand-authored and are **preserved** across regeneration.

| Column | Meaning |
|---|---|
| `path` | repo-relative, forward slashes. The key. |
| `disposition` | `port`, `rewrite` or `delete`. Three words, so a 140-row table stays diffable. |
| `destination` | the module from the spec section 4 table that takes over its job, or `-`. |
| `replacedIn` | the phase in which the replacement lands. |
| `deletedIn` | the phase in which this file goes. Usually later — see above. |
| `copiedTo` | repo-relative path of the reviewed copy in the new tree. `port` rows only. |
| `sourceHash` | what this file hashed to when the copy was reviewed. |
| `reviewedBy` | who reviewed the copy. |
| `reviewedIn` | the commit or pull request the review happened in. |
| `notes` | free text. Never overwritten by the generator. |

### The disposition vocabulary

- **`port`** — copied forward largely as it stands. Spec section 4 permits this only "file by
  file, with the copy reviewed", so a `port` row is incomplete without all four review columns.
- **`rewrite`** — the concept survives, the code does not. The replacement is written against
  the new design; nothing is copied. **Every row is `rewrite` or `delete` today**: nothing has
  been copied out of the old tree, and the point of `udeaVerifyMigration` is that the first
  copy cannot happen quietly.
- **`delete`** — nothing replaces it. It goes when its module goes.

### How a copy is checked

`udeaVerifyMigration` normalises both files — dropping the package declaration, imports,
whole-line comments, blank lines and indentation, all of which change for free when a file
moves module — and compares them. A file whose normalised content hashes identically to an old
file, or whose significant lines overlap it by more than `MigrationLedger.SIMILARITY_THRESHOLD`,
is a copy. It then demands a `port` row whose `copiedTo` names it and whose `sourceHash`
matches what the old file hashes to **today**.

That last clause is the part that keeps earning its keep after Phase 2. `common` keeps changing
while the new tree is built, and a copy reviewed in Phase 0 against a file that has since been
fixed is exactly the bug spec section 7 describes as appearing far from its cause. A stale
review fails as `UDEA-MIG-004` and names both hashes.

A genuine rewrite does not trip the threshold, and that is the intent: this gate enforces that a
review *happened and is current*, never that the review was good.

Issue #146 words the source column as `copiedFrom` and the hash as the git blob sha. Two
deliberate departures: the table is already keyed by the source path, so a `copiedFrom` column
would only ever repeat the key — the direction is inverted into `copiedTo` instead. And the hash
is a SHA-256 of the *normalised* content, not a git blob sha, because a blob sha changes when a
line ending or a trailing space does, which would fail every reviewed copy on the first
`git checkout` on the other operating system.

### Rule ids

| Id | Refuses |
|---|---|
| `UDEA-MIG-001` | an old-tree Kotlin file with no row |
| `UDEA-MIG-002` | a row naming a file that is not in the tree, or a duplicated row |
| `UDEA-MIG-003` | a near-duplicate of an old-tree file with no complete review record |
| `UDEA-MIG-004` | a reviewed copy whose source has changed since the review |

These are `build-logic` rule ids, in the same space as `UDEA-MG-*` and `UDEA-REL-*`, rather than
`UdeaRules` ids from `udea-diagnostics`. They have to be: `build-logic` is the included build
that configures the main build, so it compiles before `:udea-diagnostics` exists at all, and a
dependency on it would be a cycle. Only the rendered shape is shared.

---

## Rows

```ledger
path	disposition	destination	replacedIn	deletedIn	copiedTo	sourceHash	reviewedBy	reviewedIn	notes
common/src/main/kotlin/dev/wildware/builders.kt	rewrite	udea-core	2	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/Mouse.kt	rewrite	udea-render	3	6	-	-	-	-	input capture is presentation-side
common/src/main/kotlin/dev/wildware/udea/UdeaGame.kt	rewrite	udea-core	0	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/UdeaGameManager.kt	rewrite	udea-core	0	6	-	-	-	-	the lateinit gameManager global (standards section 1); GameContext replaces it
common/src/main/kotlin/dev/wildware/udea/ability/Ability.kt	rewrite	udea-gas	3	6	-	-	-	-	re-denominated in Tick (spec section 5)
common/src/main/kotlin/dev/wildware/udea/ability/AttributeModificationExec.kt	rewrite	udea-gas	3	6	-	-	-	-	re-denominated in Tick (spec section 5)
common/src/main/kotlin/dev/wildware/udea/ability/AttributeSet.kt	rewrite	udea-gas	3	6	-	-	-	-	re-denominated in Tick (spec section 5)
common/src/main/kotlin/dev/wildware/udea/ability/Attributes.kt	rewrite	udea-gas	3	6	-	-	-	-	re-denominated in Tick (spec section 5)
common/src/main/kotlin/dev/wildware/udea/ability/GameplayEffectCue.kt	rewrite	udea-gas	3	6	-	-	-	-	re-denominated in Tick (spec section 5)
common/src/main/kotlin/dev/wildware/udea/ability/GameplayEffectSpec.kt	rewrite	udea-gas	3	6	-	-	-	-	re-denominated in Tick (spec section 5)
common/src/main/kotlin/dev/wildware/udea/ability/GameplayTag.kt	rewrite	udea-gas	3	6	-	-	-	-	re-denominated in Tick (spec section 5)
common/src/main/kotlin/dev/wildware/udea/ability/util.kt	rewrite	udea-gas	3	6	-	-	-	-	re-denominated in Tick (spec section 5)
common/src/main/kotlin/dev/wildware/udea/assets/LazyList.kt	rewrite	udea-assets	2	6	-	-	-	-	the object Assets global goes with it (standards section 1)
common/src/main/kotlin/dev/wildware/udea/assets/ability.kt	rewrite	udea-assets	2	6	-	-	-	-	the object Assets global goes with it (standards section 1)
common/src/main/kotlin/dev/wildware/udea/assets/animationSets.kt	rewrite	udea-assets	2	6	-	-	-	-	the object Assets global goes with it (standards section 1)
common/src/main/kotlin/dev/wildware/udea/assets/animations.kt	rewrite	udea-assets	2	6	-	-	-	-	the object Assets global goes with it (standards section 1)
common/src/main/kotlin/dev/wildware/udea/assets/assets.kt	rewrite	udea-assets	2	6	-	-	-	-	the object Assets global goes with it (standards section 1)
common/src/main/kotlin/dev/wildware/udea/assets/audio.kt	rewrite	udea-assets	2	6	-	-	-	-	the object Assets global goes with it (standards section 1)
common/src/main/kotlin/dev/wildware/udea/assets/audioSet.kt	rewrite	udea-assets	2	6	-	-	-	-	the object Assets global goes with it (standards section 1)
common/src/main/kotlin/dev/wildware/udea/assets/blueprints.kt	rewrite	udea-assets	2	6	-	-	-	-	the object Assets global goes with it (standards section 1)
common/src/main/kotlin/dev/wildware/udea/assets/character.kt	rewrite	udea-assets	2	6	-	-	-	-	the object Assets global goes with it (standards section 1)
common/src/main/kotlin/dev/wildware/udea/assets/classes.kt	rewrite	udea-assets	2	6	-	-	-	-	the object Assets global goes with it (standards section 1)
common/src/main/kotlin/dev/wildware/udea/assets/controls.kt	rewrite	udea-assets	2	6	-	-	-	-	the object Assets global goes with it (standards section 1)
common/src/main/kotlin/dev/wildware/udea/assets/dsl/UdeaDsl.kt	rewrite	udea-assets-compiler	2	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/assets/dsl/assetBuilder.kt	rewrite	udea-assets-compiler	2	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/assets/dsl/script/scriptDef.kt	rewrite	udea-assets-compiler	2	6	-	-	-	-	D4 keeps .udea.kts and kills the runtime script host
common/src/main/kotlin/dev/wildware/udea/assets/dsl/script/scriptHost.kt	rewrite	udea-assets-compiler	2	6	-	-	-	-	D4 keeps .udea.kts and kills the runtime script host
common/src/main/kotlin/dev/wildware/udea/assets/gameConfig.kt	rewrite	udea-assets	2	6	-	-	-	-	the object Assets global goes with it (standards section 1)
common/src/main/kotlin/dev/wildware/udea/assets/levels.kt	rewrite	udea-assets	2	6	-	-	-	-	the object Assets global goes with it (standards section 1)
common/src/main/kotlin/dev/wildware/udea/assets/particle.kt	rewrite	udea-assets	2	6	-	-	-	-	the object Assets global goes with it (standards section 1)
common/src/main/kotlin/dev/wildware/udea/assets/render.kt	rewrite	udea-assets	2	6	-	-	-	-	the object Assets global goes with it (standards section 1)
common/src/main/kotlin/dev/wildware/udea/assets/serializers.kt	rewrite	udea-assets	2	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/assets/ui.kt	rewrite	udea-assets	2	6	-	-	-	-	the object Assets global goes with it (standards section 1)
common/src/main/kotlin/dev/wildware/udea/command/Command.kt	delete	-	-	6	-	-	-	-	the in-game console; the MCP tool surface replaces it (spec section 1, D6)
common/src/main/kotlin/dev/wildware/udea/command/ConnectCommand.kt	delete	-	-	6	-	-	-	-	the in-game console; the MCP tool surface replaces it (spec section 1, D6)
common/src/main/kotlin/dev/wildware/udea/command/Console.kt	delete	-	-	6	-	-	-	-	the in-game console; the MCP tool surface replaces it (spec section 1, D6)
common/src/main/kotlin/dev/wildware/udea/command/HostCommand.kt	delete	-	-	6	-	-	-	-	the in-game console; the MCP tool surface replaces it (spec section 1, D6)
common/src/main/kotlin/dev/wildware/udea/contextReceivers.kt	delete	-	-	6	-	-	-	-	the language feature it wraps is gone
common/src/main/kotlin/dev/wildware/udea/ecs/UdeaSystem.kt	rewrite	udea-core	0	6	-	-	-	-	SimSystem
common/src/main/kotlin/dev/wildware/udea/ecs/component/ability/Abilities.kt	rewrite	udea-gas	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/component/ability/Attributes.kt	rewrite	udea-gas	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/component/ai/AIPerception.kt	rewrite	moba	5	6	-	-	-	-	bots are Phase 5
common/src/main/kotlin/dev/wildware/udea/ecs/component/ai/Agent.kt	rewrite	moba	5	6	-	-	-	-	bots are Phase 5
common/src/main/kotlin/dev/wildware/udea/ecs/component/animation/AnimationMapHolder.kt	rewrite	udea-render	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/component/animation/Animations.kt	rewrite	udea-render	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/component/audio/AudioMapHolder.kt	rewrite	udea-render	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/component/base/Blueprint.kt	rewrite	udea-core	0	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/component/base/Dead.kt	rewrite	udea-core	0	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/component/base/Debug.kt	rewrite	udea-core	0	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/component/base/Networkable.kt	rewrite	udea-core	0	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/component/base/Transform.kt	rewrite	udea-core	0	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/component/control/CharacterController.kt	rewrite	udea-core	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/component/control/Controller.kt	rewrite	udea-core	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/component/lights/ConeLight.kt	delete	-	-	6	-	-	-	-	box2dlights; no lighting is planned in Phases 0-7
common/src/main/kotlin/dev/wildware/udea/ecs/component/lights/DirectionalLight.kt	delete	-	-	6	-	-	-	-	box2dlights; no lighting is planned in Phases 0-7
common/src/main/kotlin/dev/wildware/udea/ecs/component/lights/LightComponent.kt	delete	-	-	6	-	-	-	-	box2dlights; no lighting is planned in Phases 0-7
common/src/main/kotlin/dev/wildware/udea/ecs/component/lights/PointLight.kt	delete	-	-	6	-	-	-	-	box2dlights; no lighting is planned in Phases 0-7
common/src/main/kotlin/dev/wildware/udea/ecs/component/networkComponent.kt	rewrite	udea-codegen	0	6	-	-	-	-	component identity is generated now: ComponentTypeId and the Replicator
common/src/main/kotlin/dev/wildware/udea/ecs/component/physics/Body.kt	rewrite	udea-core	3	6	-	-	-	-	Box2D is demoted behind PhysicsWorld; CharacterMover is authoritative (spec section 3.4)
common/src/main/kotlin/dev/wildware/udea/ecs/component/physics/Box.kt	rewrite	udea-core	3	6	-	-	-	-	Box2D is demoted behind PhysicsWorld; CharacterMover is authoritative (spec section 3.4)
common/src/main/kotlin/dev/wildware/udea/ecs/component/physics/Capsule.kt	rewrite	udea-core	3	6	-	-	-	-	Box2D is demoted behind PhysicsWorld; CharacterMover is authoritative (spec section 3.4)
common/src/main/kotlin/dev/wildware/udea/ecs/component/physics/Chain.kt	rewrite	udea-core	3	6	-	-	-	-	Box2D is demoted behind PhysicsWorld; CharacterMover is authoritative (spec section 3.4)
common/src/main/kotlin/dev/wildware/udea/ecs/component/physics/Circle.kt	rewrite	udea-core	3	6	-	-	-	-	Box2D is demoted behind PhysicsWorld; CharacterMover is authoritative (spec section 3.4)
common/src/main/kotlin/dev/wildware/udea/ecs/component/physics/PhysicsComponent.kt	rewrite	udea-core	3	6	-	-	-	-	Box2D is demoted behind PhysicsWorld; CharacterMover is authoritative (spec section 3.4)
common/src/main/kotlin/dev/wildware/udea/ecs/component/render/AnimationHolder.kt	rewrite	udea-render	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/component/render/Camera.kt	rewrite	udea-render	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/component/render/ParticleEffect.kt	rewrite	udea-render	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/component/render/SpriteRenderer.kt	rewrite	udea-render	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/component/udeaComponent.kt	rewrite	udea-codegen	0	6	-	-	-	-	component identity is generated now: ComponentTypeId and the Replicator
common/src/main/kotlin/dev/wildware/udea/ecs/component/udeaTypes.kt	rewrite	udea-codegen	0	6	-	-	-	-	component identity is generated now: ComponentTypeId and the Replicator
common/src/main/kotlin/dev/wildware/udea/ecs/system/AbilitySystem.kt	rewrite	udea-gas	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/system/AnimationSetSystem.kt	rewrite	udea-render	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/system/AnimationSystem.kt	rewrite	udea-render	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/system/AttributeSystem.kt	rewrite	udea-gas	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/system/BackgroundDrawSystem.kt	rewrite	udea-render	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/system/Box2DLightsSystem.kt	delete	-	-	6	-	-	-	-	box2dlights
common/src/main/kotlin/dev/wildware/udea/ecs/system/Box2DSystem.kt	rewrite	udea-core	3	6	-	-	-	-	spec section 3.4
common/src/main/kotlin/dev/wildware/udea/ecs/system/CameraTrackSystem.kt	rewrite	udea-render	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/system/CharacterAnimationControllerSystem.kt	rewrite	udea-render	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/system/CharacterControllerSystem.kt	rewrite	udea-core	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/system/CleanupSystem.kt	rewrite	udea-core	0	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/system/ControllerSystem.kt	rewrite	udea-core	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/system/DebugDrawSystem.kt	rewrite	udea-agent	1	6	-	-	-	-	the agent activity overlay (spec section 3.7)
common/src/main/kotlin/dev/wildware/udea/ecs/system/NetworkClientSystem.kt	rewrite	udea-net	3	3	-	-	-	-	Phase 3 exit names this file; carries the TODO() on a reachable path
common/src/main/kotlin/dev/wildware/udea/ecs/system/NetworkServerSystem.kt	rewrite	udea-net	3	3	-	-	-	-	Phase 3 exit names this file
common/src/main/kotlin/dev/wildware/udea/ecs/system/ParticleSystemSystem.kt	rewrite	udea-render	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/system/SoundSystem.kt	rewrite	udea-render	3	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/ecs/system/SpriteBatchSystem.kt	rewrite	udea-render	3	6	-	-	-	-	becomes a RenderSystem, not a Fleks system (spec section 3.3)
common/src/main/kotlin/dev/wildware/udea/ecs/system/TransformSystem.kt	rewrite	udea-core	0	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/input.kt	rewrite	udea-render	3	6	-	-	-	-	input capture is presentation-side
common/src/main/kotlin/dev/wildware/udea/json.kt	delete	-	-	6	-	-	-	-	Jackson; one Replicator serves all five consumers (spec section 3.1)
common/src/main/kotlin/dev/wildware/udea/network/InPlaceSerializer.kt	rewrite	udea-net	3	3	-	-	-	-	Phase 3 exit deletes KryoNet and the old network stack outright
common/src/main/kotlin/dev/wildware/udea/network/PacketUtil.kt	rewrite	udea-net	3	3	-	-	-	-	Phase 3 exit deletes KryoNet and the old network stack outright
common/src/main/kotlin/dev/wildware/udea/network/packets.kt	rewrite	udea-net	3	3	-	-	-	-	Phase 3 exit deletes KryoNet and the old network stack outright
common/src/main/kotlin/dev/wildware/udea/network/serde/ArrayListSerializer.kt	rewrite	udea-net	3	3	-	-	-	-	Phase 3 exit deletes KryoNet and the old network stack outright
common/src/main/kotlin/dev/wildware/udea/network/serde/AssetReferenceSerializer.kt	rewrite	udea-net	3	3	-	-	-	-	Phase 3 exit deletes KryoNet and the old network stack outright
common/src/main/kotlin/dev/wildware/udea/network/serde/SerializableSerializer.kt	rewrite	udea-net	3	3	-	-	-	-	Phase 3 exit deletes KryoNet and the old network stack outright
common/src/main/kotlin/dev/wildware/udea/network/serde/Vector2Serializer.kt	rewrite	udea-net	3	3	-	-	-	-	Phase 3 exit deletes KryoNet and the old network stack outright
common/src/main/kotlin/dev/wildware/udea/network/serializers.kt	rewrite	udea-net	3	3	-	-	-	-	Phase 3 exit deletes KryoNet and the old network stack outright
common/src/main/kotlin/dev/wildware/udea/properties.kt	rewrite	udea-core	0	6	-	-	-	-	named in the spec section 4 table
common/src/main/kotlin/dev/wildware/udea/reflection.kt	delete	-	-	6	-	-	-	-	UdeaReflections; no reflection on a per-tick path (standards section 1)
common/src/main/kotlin/dev/wildware/udea/screen/LoadingScreen.kt	rewrite	moba	5	6	-	-	-	-	Trello #22 loading screen and #27 in-game UI are both scheduled Phase 5
common/src/main/kotlin/dev/wildware/udea/screen/UIScreen.kt	rewrite	moba	5	6	-	-	-	-	Trello #22 loading screen and #27 in-game UI are both scheduled Phase 5
common/src/main/kotlin/dev/wildware/udea/screen/scene2d.kt	rewrite	moba	5	6	-	-	-	-	Trello #22 loading screen and #27 in-game UI are both scheduled Phase 5
common/src/main/kotlin/dev/wildware/udea/udeaTypes.kt	rewrite	udea-core	0	6	-	-	-	-	-
common/src/main/kotlin/dev/wildware/udea/utils.kt	delete	-	-	6	-	-	-	-	linear family scan per inbound packet (standards section 1)
example/src/main/kotlin/dev/wildware/udea/example/ExampleGame.kt	rewrite	moba	3	6	-	-	-	-	D7: the example game becomes the 5v5 MOBA
example/src/main/kotlin/dev/wildware/udea/example/ability/CharacterAttributeSet.kt	rewrite	moba	3	6	-	-	-	-	champion kits are moba content
example/src/main/kotlin/dev/wildware/udea/example/ability/DamageCue.kt	rewrite	moba	3	6	-	-	-	-	champion kits are moba content
example/src/main/kotlin/dev/wildware/udea/example/ability/ExampleTags.kt	rewrite	moba	3	6	-	-	-	-	champion kits are moba content
example/src/main/kotlin/dev/wildware/udea/example/ability/KnockbackCue.kt	rewrite	moba	3	6	-	-	-	-	champion kits are moba content
example/src/main/kotlin/dev/wildware/udea/example/ability/MeleeDamageCue.kt	rewrite	moba	3	6	-	-	-	-	champion kits are moba content
example/src/main/kotlin/dev/wildware/udea/example/ability/OnHitEffect.kt	rewrite	moba	3	6	-	-	-	-	champion kits are moba content
example/src/main/kotlin/dev/wildware/udea/example/ability/OrcSpinAttack.kt	rewrite	moba	3	6	-	-	-	-	champion kits are moba content
example/src/main/kotlin/dev/wildware/udea/example/ability/PriestHeal.kt	rewrite	moba	3	6	-	-	-	-	champion kits are moba content
example/src/main/kotlin/dev/wildware/udea/example/ability/PriestHealCue.kt	rewrite	moba	3	6	-	-	-	-	champion kits are moba content
example/src/main/kotlin/dev/wildware/udea/example/ability/SoldierFireArrow.kt	rewrite	moba	3	6	-	-	-	-	champion kits are moba content
example/src/main/kotlin/dev/wildware/udea/example/ability/UnitMeleeAttack.kt	rewrite	moba	3	6	-	-	-	-	champion kits are moba content
example/src/main/kotlin/dev/wildware/udea/example/assets/Effect.kt	rewrite	moba	2	6	-	-	-	-	-
example/src/main/kotlin/dev/wildware/udea/example/character/GameUnitAnimationMap.kt	rewrite	moba	3	6	-	-	-	-	-
example/src/main/kotlin/dev/wildware/udea/example/character/GameUnitSoundMap.kt	rewrite	moba	3	6	-	-	-	-	-
example/src/main/kotlin/dev/wildware/udea/example/component/AIUnit.kt	rewrite	moba	3	6	-	-	-	-	-
example/src/main/kotlin/dev/wildware/udea/example/component/Effect.kt	rewrite	moba	3	6	-	-	-	-	-
example/src/main/kotlin/dev/wildware/udea/example/component/GameUnit.kt	rewrite	moba	3	6	-	-	-	-	-
example/src/main/kotlin/dev/wildware/udea/example/component/Player.kt	rewrite	moba	3	6	-	-	-	-	-
example/src/main/kotlin/dev/wildware/udea/example/component/Projectile.kt	rewrite	moba	3	6	-	-	-	-	-
example/src/main/kotlin/dev/wildware/udea/example/component/Team.kt	rewrite	moba	3	6	-	-	-	-	-
example/src/main/kotlin/dev/wildware/udea/example/system/EffectSystem.kt	rewrite	moba	3	6	-	-	-	-	-
example/src/main/kotlin/dev/wildware/udea/example/system/GameUnitSystem.kt	rewrite	moba	3	6	-	-	-	-	-
example/src/main/kotlin/dev/wildware/udea/example/system/HealthbarSystem.kt	rewrite	moba	3	6	-	-	-	-	-
example/src/main/kotlin/dev/wildware/udea/example/system/PlayerControlSystem.kt	rewrite	moba	3	6	-	-	-	-	-
example/src/main/kotlin/dev/wildware/udea/example/system/ProjectileSystem.kt	rewrite	moba	3	6	-	-	-	-	-
example/src/main/kotlin/dev/wildware/udea/example/system/UnitAISystem.kt	rewrite	moba	3	6	-	-	-	-	-
example/src/main/kotlin/dev/wildware/udea/example/util.kt	delete	-	-	6	-	-	-	-	no Util grab-bag types (standards section 3)
gradle-plugin/src/main/kotlin/dev/wildware/udea/UdeaPlugin.kt	rewrite	udea-gradle	0	6	-	-	-	-	the old plugin leaked gradleApi onto the game runtime (spec section 4)
gradle-plugin/src/main/kotlin/dev/wildware/udea/assets/AssetScanner.kt	rewrite	udea-assets-compiler	2	6	-	-	-	-	build-time asset scan
gradle-plugin/src/main/kotlin/dev/wildware/udea/dsl/CreateDsl.kt	rewrite	udea-codegen	0	6	-	-	-	-	@CreateDsl and UdeaDslProcessor
gradle-plugin/src/main/kotlin/dev/wildware/udea/dsl/DslInclude.kt	rewrite	udea-codegen	0	6	-	-	-	-	@CreateDsl and UdeaDslProcessor
gradle-plugin/src/main/kotlin/dev/wildware/udea/dsl/UdeaDslProcessor.kt	rewrite	udea-codegen	0	6	-	-	-	-	@CreateDsl and UdeaDslProcessor
gradle-plugin/src/main/kotlin/dev/wildware/udea/dsl/UdeaSync.kt	rewrite	udea-codegen	0	6	-	-	-	-	@CreateDsl and UdeaDslProcessor
gradle-plugin/src/main/kotlin/dev/wildware/udea/network/NetworkGenerator.kt	rewrite	udea-codegen	0	6	-	-	-	-	String-concatenated codegen (standards section 1); replaced by the KotlinPoet emitter
gradle-plugin/src/main/kotlin/dev/wildware/udea/network/annotations.kt	rewrite	udea-codegen	0	6	-	-	-	-	String-concatenated codegen (standards section 1); replaced by the KotlinPoet emitter
```
