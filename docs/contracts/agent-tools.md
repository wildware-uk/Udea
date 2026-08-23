# Contract: the generated agent surface

**Status:** active (Phase 1 wave 1)
**Producer:** `udea-codegen`, package `dev.wildware.udea.codegen.agent`
**Consumer:** `udea-agent` (issues #65, #67, #68), then `udea-agent-host`
**Spec:** §3.2 (`@AgentTool` manifests + JSON Schema are KSP2's job), §5 (ServiceLoader
discovery, no magic package), §6 Phase 1 exit

`udea-codegen` emits four things from `@AgentTool` and `@AgentState`, all of them written
against types `udea-agent` owns. This page is the shape neither module may change alone: the
generator only ever *names* these types (`AgentNames`), so nothing but this document and the
golden files in `udea-codegen` connect the two sides.

---

## What is generated

| Output | One per | Dependency |
|---|---|---|
| `object <Owner><Fn>Tool : AgentToolDef<Owner>` | `@AgentTool` function | isolating |
| `object <Owner>AgentState : AgentStateSource<Owner>` | class declaring `@AgentState` | isolating |
| `class <Module>ToolModule : ToolModule` + its `META-INF/services` line | module | aggregating |
| `class <Module>StateModule : StateModule` + its `META-INF/services` line | module | aggregating |
| `udea/<Module>-agent-tools.json` | module | aggregating |

Both indexes are gated on a KSP option — `udea.toolModuleService` and
`udea.stateModuleService` — exactly as the `NetModule` index is gated on
`udea.netModuleService`. Generated code may only implement an interface that exists, and a
module contributing tools to a game that does not ship the agent surface must still compile.

The manifest fragment is **not** gated. It is data, and the CI diff against its checked-in
golden is the only thing that makes a reworded tool description a reviewable change.

## What it is written against, and what is still missing

Already real, in `udea-agent`, and used directly by generated code:

- `AgentCommand` — the dispatcher's parameter. Its `int`/`long`/`float`/`bool`/`str`
  accessors do every conversion, and `contains` is how an absent optional argument stays
  distinguishable from an empty one.
- `BadArgumentException(toolName, argument, supplied, expected)` — the only failure generated
  coercion throws. `AgentDispatcher` already turns it into `ok:false` with kind
  `bad_argument`, which is Phase 1's "a throwing tool lands as `ok:false` without stalling the
  loop", met without a second failure type for the dispatcher to learn.
- `dev.wildware.udea.agent.state.GameStateSink` — the digest's `game` block, one overload per
  scalar so publishing costs no boxing.

Declared in `udea-agent`'s `src/main` (`AgentToolDef.kt`, `AgentStateSource.kt`), and on
`udea-codegen`'s **test** classpath so generated code is compiled, `ServiceLoader`-loaded and
dispatched through the real runtime indexes:

```kotlin
package dev.wildware.udea.agent

public data class AgentToolArg(
    public val name: String,
    public val type: String,        // a JSON Schema type name
    public val description: String,
    public val required: Boolean,
    public val default: String?,    // null means "no default"
)

public interface AgentToolDef<in T> {
    public val name: String
    public val description: String
    public val args: List<AgentToolArg>
    public val inputSchema: String  // JSON, one line, served verbatim
    public val owner: KClass<*>     // the declaring class, as a class literal
    public fun invoke(receiver: T, command: AgentCommand): Any?
}

public interface ToolModule {
    public val moduleName: String
    public val tools: List<AgentToolDef<*>>   // ascending name
}

public interface AgentStateSource<in T> {
    public val names: List<String>            // sorted digest keys
    public val owner: KClass<*>               // the declaring class, as a class literal
    public fun write(source: T, out: GameStateSink)
}

public interface StateModule {
    public val moduleName: String
    public val states: List<AgentStateSource<*>>  // by declaring type name
}
```

### Why `owner` is there, and why it is not the reflection the tree bans

`T` is erased, so an index holding `AgentToolDef<*>` cannot recover which toolset instance a
tool belongs to. `owner` is a **class literal** — a constant reference, not a lookup. R8 follows
it and keeps the class, which is the ban's own rationale; it costs no allocation; and it is read
exactly once, when `ToolIndex`/`AgentStateIndex` is built, never on a call. The alternatives are
worse in the ban's terms: a class *name* is a string the shrinker cannot follow, and discovering
the receiver reflectively is the thing being banned.

Both source scanners carry a narrow, whole-line exemption for it, and both pin the exact set of
lines it covers so a widening shows up in a diff:
`GeneratedSourceShapeTest.the reflection exemption is one property on the agent surface and
nothing else`, and `NoReflectionInQueryPathTest.the KClass exemption covers the owner pairing
and nothing else`.

### The runtime indexes (`udea-agent`)

| Type | Discovers | Serves | Refuses, at build time |
|---|---|---|---|
| `dispatch.ToolIndex` | `ToolModule` via `ServiceLoader` | `ToolRegistry`, so `AgentDispatcher` calls generated tools | two modules publishing one tool name; a tool whose toolset was never registered; two registered instances fitting one tool |
| `state.AgentStateIndex` | `StateModule` via `ServiceLoader` | `GameStateSource`, so `StateDigest`'s `game` block carries `@AgentState` | two modules publishing one digest key; the same two binding failures |

Both cross-module checks are ones a KSP round structurally cannot make — a round sees one
module — so the runtime index is the first place the whole classpath is visible at once.

A tool's return value is rendered by `ToolIndex` from a closed set: `AgentResult` (passed
through), `String`, `Int`, `Long`, `Float`, `Boolean`, and `Unit`/`null` as `{}`. Anything else
is `ok:false` with kind `unrenderable_result` naming the type, because `@AgentTool` does not
constrain a return type and a `toString` that reads like data is worse than a refusal.

`T` is the **declaring class** — the toolset — because a tool is a member function and needs a
receiver. The host owns the instance and therefore owns the pairing; that is also why
`AgentStateSource` is not a `GameStateSource`, which has no receiver to read from.

## A tool that must run outside the barrier drain (`udea-agent`, additive)

Nothing above changes. This is a second interface *beside* `AgentToolDef`, declared in
`udea-agent`, so a tool that does not ask for a context is unaffected:

```kotlin
public interface ContextualToolDef<in T> : AgentToolDef<T> {
    public fun invoke(receiver: T, command: AgentCommand, context: AgentContext): Any?
}
```

A `@AgentTool` function asks for one by declaring an `AgentContext` parameter; `udea-codegen`
leaves that parameter out of the published schema - there is nothing an agent could put in it -
and emits an object implementing this interface, whose two-argument `invoke` throws. (Amended:
this interface was originally specified as one `udea-codegen` never emits, on the assumption
that only hand-written engine tools would need a context. Routing the engine's own toolsets
through the same KSP pass as a game's removed that assumption without changing a signature, a
tool name, a schema or the manifest.)

`ToolIndex.invoke` passes the context to a tool of this type and to nothing else. The original
reason to be one was that the engine's `time.step`, `time.fast_forward` and `time.rewind`
**run the simulation**, and a tool call executes inside a `SimBarrier` drain, and
`Simulation.step` drains the barrier, and `SimBarrier.drain` refuses to re-enter.
`TimeControl.rewind` states the same requirement from the other side - it forces a drain of its
own, safe only with the loop stopped between frames.

(Amended: there is a second reason, and `render.screenshot` and `render.screenshot_region` are
in that position. A capture must be queued *before* the frame that serves it is drawn and
answered *after* it, and on an `Offscreen` or `Windowed` host the thread running the drain is
the render thread - so the tool cannot wait for that frame where it is called, for the same
reason a stepping tool cannot step there. It queues, returns, and answers from
`answerLater`, which runs after `GameLoop.frame` has rendered. As with the amendment above, no
signature, tool name, schema or manifest shape changes: the interface, the dispatch rule and the
meaning of `completedCommandId` are exactly as specified.)

Such a tool calls `AgentContext.answerLater { … }`, which runs the work after the tick (outside
any drain, before the state document is published) and completes the command with what it
returned. `AgentDispatcher` does not complete a command whose tool deferred its answer, so
`completedCommandId` still means *it happened*; a throw inside the deferred block still
completes the command, as `tool_threw`, because a command that never completes is a healthy game
reported as frozen.

## The description gate applies at runtime as well as at the symbol

`UDEA0008` and `UDEA0009` are compile errors at the `@AgentTool` symbol. They can only see a
tool that passed through a KSP round, and this contract explicitly allows a hand-written
`AgentToolDef`, which any game may write. So `ToolIndex.Builder.build` applies the same two rules
to every tool it indexes, refusing under the same rule ids. One defect, one name, wherever it
surfaces.

(Amended: the engine's own `world`/`time`/`events`/`diag` toolsets were the example of a
hand-written `AgentToolDef` here. They are generated now - a tool name may carry its own toolset,
`@AgentTool(name = "world.query_entities")`, which is what made that possible - so the runtime
gate covers a game's hand-written tools alone. It is not redundant: the contract still allows
one, and a gate whose only test subject has been removed is a gate nothing can fail.)

## `@AgentState` is not in the `Replicator` field space

The frozen `Replicator` contract makes `fieldNames[i]`, `FieldMask` bit `i` and `FieldStore`
index `i` the same index, and `desync_report` depends on it. A property with a name in that
space but no bit and no slot cannot exist there, so `@AgentState` is a separate one-way
channel: never captured, never diffed, never written to a packet, never restored by a rewind.
A property may carry `@Net` and `@AgentState` at once and means two unrelated things.
`AgentStateIsolationTest` holds the two apart.

## The bridge's rules this must satisfy

`game-bridge-mcp`'s `normaliseManifest` is tolerant, which means **silent**: a tool whose name
is missing is dropped, a toolset with an empty name is skipped, and a `tools` that is not an
array reads as no tools — none of it reported anywhere. So each rule is obeyed by
construction, and `ToolManifestBridgeParserTest` re-applies the parser to the generated file:

- `toolsets[].name` non-empty; `toolsets[].tools` an array;
- `tools[].name` non-empty; `tools[].description` present;
- `tools[].args[]` objects of `{name, type, description, required, default}`;
- `tools[].inputSchema` an **object** in the manifest, though the generated Kotlin holds the
  same document as a one-line string;
- a default is folded into the schema property's `description`, never emitted as `default` —
  the bridge does the same, because a `default` on a strictly-typed property is something a
  strict client may reject.

## Closed worlds

| | Accepted |
|---|---|
| `@AgentTool` parameter | `Int`, `Long`, `Float`, `Double`, `Boolean`, `String`, an enum, `NetId`, and `List` of any of those |
| `@AgentState` property | `Int`, `Long`, `Float`, `Boolean`, `String`, an enum |

Anything else is a build error at the symbol under a registered rule id. `Double` is missing
from the second column because `GameStateSink` publishes floats: accepting it would mean
generating a `.toFloat()` nobody wrote.

An optional parameter must be nullable, or declare `@Arg(default = "…")`. KSP can see *that* a
Kotlin parameter has a default and never the expression behind it, so the value the manifest
advertises has to be written where both the manifest and the dispatcher can read it.

## Rule ids

| Id | Defect |
|---|---|
| `UDEA0008` | `@AgentTool` description blank or under `UdeaRules.MIN_TOOL_DESCRIPTION` |
| `UDEA0009` | an `@AgentTool` parameter with no `@Arg` description |
| `UDEA0010` | an `@AgentTool` parameter type outside the closed world |
| `UDEA0011` | `@AgentState` on a non-scalar |
| `UDEA0012` | two tools, or two digest keys, resolving to one effective name |
