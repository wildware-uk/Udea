# Agent Tool Surface

Every Udea game can be driven by a program instead of a person. Start it with a port, and it serves a
small HTTP surface that an AI agent — or a test, or a script — uses to look at the world, press keys,
step the clock and take screenshots. There is no IDE plugin, and the editor window is a screen over
this same surface rather than a second implementation of it.

A plain picture: it is a debug port into a running game, with a menu attached. The menu is generated
from the code, so it cannot drift from what the game can actually do.

```sh
sh gradlew :moba:desktop:run -PdebugPort=7841 --console=plain
curl 'http://127.0.0.1:7841/health'
curl 'http://127.0.0.1:7841/tools'
```

## The five endpoints

`udea-agent-host` serves these from a JDK `HttpServer`, on loopback.

| Endpoint | Answers |
|---|---|
| `GET /health` | Is it alive, which `RenderMode`, which tick and frame, whether it is paused, the session role, and `completedCommandId` |
| `GET /state` | The world digest: tick, paused, time scale, fps, entity count, recent events, recent command results, and whatever the game publishes |
| `GET /command?cmd=<tool>&...` | Fire and forget. Every other query parameter is passed to the tool as text |
| `GET /tools` | Every tool, grouped into toolsets, with the description and JSON Schema a model reads |
| `GET /artifact?id=cap_0007` | The bytes a JSON answer cannot carry — a screenshot, a long query result |

Two details that trip people up.

**The command name is keyed `cmd`, not `name`.** Tools routinely take a `name` argument of their own
(`render.follow_entity?name=hero`), and a duplicate query key would silently overwrite the command
being invoked.

**`/command` answers the moment the command is *queued*, not when it has run.** The command executes
later, on the simulation thread, inside a `SimBarrier` drain. Confirmation is polling `/state` (or
`/health`) until `completedCommandId` reaches the `commandId` you were given. A full queue answers
`{"accepted":false,"error":"queue_full"}` with **HTTP 200** on purpose: a 503 would be read by a
bridge as a sick port and reported as an offline game, whereas 200 with `accepted:false` says the port
is healthy and the *simulation* is behind — the actionable version of the same fact.

## The toolsets

The prefix before the first `.` is the toolset. It is derived from the tool's own name, not from the
Gradle module it lives in, because a module is how the *code* is arranged and tells an agent nothing.

| Toolset | Where it lives | What it is for |
|---|---|---|
| `world.*` | `udea-agent` | Query, inspect, mutate, spawn, destroy entities |
| `time.*` | `udea-agent` | Pause, resume, step, time scale, snapshot, list snapshots, rewind, fast-forward |
| `events.*` | `udea-agent` | Read, clear and assert on the event ring |
| `diag.*` | `udea-agent` | Frame report, system timings, entity counts, memory |
| `agent.*` | `udea-agent` | The caption a human watching a `Windowed` instance reads |
| `editor.*` | `udea-agent` | Select, edit sessions, spawn, delete, move, save, play/stop, history, undo, keep |
| `assets.*` | `udea-agent` (JVM) | List, search, read, patch, write, validate and create assets |
| `input.*` | `udea-agent-host` | Press, release, tap, set an axis, read the input state |
| `render.*` | `udea-agent-host` | Screenshot, screenshot a region, set the camera, follow an entity, toggle debug draw, compare artifacts |
| `net.*` | `udea-agent-host` | Spawn a client session, step it, set link conditions, read server and client state, desync report, relevancy |
| `nav.*` | `udea-nav` (JVM) | The route a unit would take, and what the pathfinder thinks is standing |
| `replay.*` | `udea-replay` (JVM) | Load, seek, step, rewind and verify a `.udearep` recording |

**Do not assume the list.** `/tools` is generated, and it grows. Read it from the running game.

A game's own tools appear here too, under whatever prefix they name. A tool with no prefix lands in
the toolset `game`.

### `nav.*`, the newest one

`udea-nav` (#264) adds two read-only tools, and they exist because of a specific blind spot: a unit
that will not go where it was sent looks identical from the outside whatever the cause — the goal is
inside a building, the gap is too narrow for that unit, the grid still has a demolished building on
it, or the route is simply long. `world.describe_entity` shows the order and the position and says
nothing about any of them.

- **`nav.path`** — the route a ground unit of a given radius would take between two points, as cells
  and world positions, with its cost; or, when there is none, which of off-grid, blocked start,
  blocked goal or no-route it was.
- **`nav.grid`** — what the pathfinder currently believes is standing.

It is the same `Navigation` and the same A* the units use, so a reported route is *the* route rather
than a second implementation that could drift. What it cannot promise is the exact cells a unit will
pass through — a unit re-asks from every cell it enters, open ground holds many routes of identical
length, and a crowd shoulders its members off the line. The length and the destination are what hold.

## Render modes decide which tools work

`/health` reports the mode, so you know before you call.

| Mode | GL context | Window | Screenshots |
|---|---|---|---|
| `Headless` | none | none | typed `no_render_context` |
| `Offscreen` | real, Kool over LWJGL | hidden | full |
| `Windowed` | real, Kool over LWJGL | visible | full |

All three run the identical `Simulation`. `moba.agent` defaults to **Offscreen**: a real Kool context,
no window, real pixels.

In `Headless`, `render.screenshot` answering `no_render_context` **is the contract working**, not a
fault to route around. An agent diffing screenshots would read a blank image as a black screen and act
on it, so the refusal is typed and names the mode.

One structural exclusion worth knowing: the agent activity overlay draws only in `Windowed`, and only
onto the screen target, which the frame capture never reads. **An agent cannot see its own narration
in a screenshot it diffs.** Likewise `render.screenshot` never holds a gizmo; `editor.screenshot` with
`view=scene` or `view=game` is the one that does.

## Results and errors

A tool answers an `AgentResult`: either `Ok` with a rendered JSON value, or `Failed` with an
`AgentError`.

An error carries both a machine-readable `kind` and a human-readable `message`, and both are required
— an agent that can only read prose has to guess whether to retry, and one that can only read a kind
cannot tell the user what went wrong.

The kinds are an open vocabulary (a value class over a lowercase string, not an enum), so a module or
a game declares its own. Ones the engine uses include `queue_full`, `no_such_tool`, `bad_argument`,
`tool_threw`, `no_such_entity`, `no_such_field`, `bad_query`, `no_render_context` and
`field_not_writable`.

A refusal names the tool to reach for instead where there is one. "Permission denied" with no
alternative costs an agent a round trip and a guess.

## Writability

`agentWritable = false` is the default on `@Net`. Most fields refuse a write, and that is one
declaration rather than three opinions: `world.set_component_field` reads exactly the flag
`world.describe_entity` publishes beside the value, which is exactly the flag the annotation set.

```kotlin
@Net(agentWritable = true) public var x: Float = 0f
@Sim public var hp: Float = 100f      // snapshotted, never agent-writable
```

Every successful mutation writes one line into the event ring naming the tool, the entity and the
field. When somebody later asks why an entity is wrong, the ring is the only place that can answer
"the agent did it at tick 412".

## When a mutation lands

One barrier hop, not two.

```
bridge.submit(command)          // the only way in, from any thread
  -> AgentRuntime.beforeFrame   // drain onto the SimBarrier
    -> SimBarrier.drain         // at a tick boundary, before any system
      -> AgentDispatcher        // contains every throw as ok:false
        -> ToolRegistry.invoke  // the generated or hand-written tool
```

By the time a tool function runs, the simulation is *already* at the top of a step, in the drain,
before any system. So a mutation asked for while tick 412 was running is seen by every system on tick
413 and by none on tick 412. Submitting a second barrier action from inside a tool would push the
mutation to the *following* tick and complete the command before it had happened — a confirmation
that is a lie, and the one thing an agent cannot recover from.

## Sessions

Every command carries an `AgentSessionId`. Pass `&session=<label>` on `/command` and yours is that
label; pass nothing and it is your IP address.

The session is the **author** of an edit. `editor.history` and `editor.undo` are per author, so two
agents editing the same world do not undo each other's work.

The editor window interns the label `editor` and files every button press under it. So an agent that
sends `session=editor` is the *same* author: it sees the window's edits in `editor.history` and can
undo them.

## Writing a tool

One annotation on a function.

```kotlin
@AgentTool(
    name = "nav.path",
    description = "Find the route a ground unit of the given radius would take from one point " +
        "to another, over the live nav grid. ...",
)
public fun path(
    @Arg(description = "World x the route starts at, in metres.")
    fromX: Float,
    @Arg(
        description = "The unit's radius in metres, which decides the gaps it fits through.",
        default = "0.5",
    )
    radius: Float = 0.5f,
): AgentResult
```

`udea-codegen`'s KSP2 processor emits one `object <Toolset><Fn>Tool` per function, with the JSON
Schema, the manifest fragment, the argument coercion and the dispatch. **The generated dispatcher
calls the function directly**, so the tool surface survives R8 — nothing is looked up by name or by
reflection at run time.

There is one mechanism, not an engine one and a game one: the engine's own `world.*`, `time.*`,
`events.*` and `diag.*` tools are generated by the same KSP pass, so a reworded engine description
arrives as a diff in the generated manifest like anybody else's.

Things worth knowing about `@AgentTool` and `@Arg`:

- An empty `name` derives one from the function name. A name may carry its own toolset
  (`"world.query_entities"`), which is how a class called `WorldToolset` publishes tools called
  `world.*`.
- An empty `description` takes the function's KDoc summary — `udea-compiler-plugin` propagates it,
  because KSP cannot read KDoc.
- **A blank `@Arg` description is a build error.** A schema property with no description tells the
  model nothing about what to put in it.
- An optional, non-nullable argument **must** declare `default`, written as the text an agent would
  have sent. KSP can see *that* a parameter has a default but never the expression that produces it,
  so this is the one place the dispatcher and the published manifest can both read it. A nullable
  parameter is published as optional with no default: absent means `null`.
- Both annotations are `BINARY` retention. Nothing reads them at run time.

## Wiring the surface into a game

The template game (`templates/new-game/game/src/agent/`) is the smallest complete example. The shape:

```kotlin
val host = NewGame.host(RenderMode.Headless)
val bridge = AgentBridge()
val digest = StateDigest(bridge = bridge, sources = DigestSources(), timings = AgentTimings())

val tools: ToolIndex = EngineToolModules
    .wireAll(
        ToolIndex.builder(),
        TimeToolset(host.time, host.ctx.clock, bridge),
        EventsToolset(bridge, host.ctx.clock),
        LifecycleToolset(bridge, shutdown),
    )
    .build()

val identity = GameIdentity("new-game", "0.1.0")
val agentHost = AgentHost.startIfRequested(
    bridge = bridge,
    config = { port ->
        AgentHostConfig(
            port = port,
            identity = identity,
            renderMode = RenderMode.Headless,
            manifest = ToolManifest.of(identity, tools.tools),
            paused = { host.time.paused },
        )
    },
    agentAllowed = UdeaAgentBuildFlags.AGENT_ALLOWED,
)
```

You wire only the toolsets you have the pieces for. `TimeToolset` needs the host alone, so every game
can have it. `WorldToolset` needs an `AgentComponentIndex` over `@Replicated` components, a
`NetIdIndex` and a `SimClock`; the template leaves it out and says so, and
`moba/desktop/src/agent/.../MobaAgent.kt` is the worked example that wires it.

`ToolIndex.Builder.build` **refuses a tool whose toolset was never registered**, deliberately — a
misconfigured host fails at start-up rather than answering `no_such_tool` weeks later.

### It is debug-only, three times over

1. **It binds `127.0.0.1` and there is no way to ask for anything else.** No host parameter, no config
   key, no `0.0.0.0` path.
2. **It is off unless a JVM argument turns it on.** `AgentHost.startIfRequested` needs
   `-Dudea.agent.port` *and* the generated `AGENT_ALLOWED` flag. There is deliberately no environment
   variable, because a player's launcher script sets environment variables and does not normally pass
   `-D`.
3. **The classes are absent from a release build.** `udeaVerifyRelease` fails a release assemble if
   `dev/wildware/udea/agent/` appears in the packaged jar.

The flag removes the code path; the missing classes make the flag irrelevant. Either alone has failed
before — a prior project's debug server gated on a system property and shipped in the game.

That is also why an agent launcher lives in its own `agent` source set rather than in `main` with a
`compileOnly` dependency: that arrangement ships a `main` class whose first statement throws
`NoClassDefFoundError`, and calls it absence.

## `gamebridge.json`

`udea-gradle` emits `gamebridge.json` at the project root. It is what the unmodified
`game-bridge-mcp` reads to find and launch the game — the command, the working directory, and the port
range to scan.

The bridge is not modified to suit Udea. Udea conforms to the bridge, and CI asserts it against a
vendored copy of the bridge's TypeScript client.

A project states its port range in its build script:

```kotlin
udeaAgent {
    name.set("moba")
    flagsPackage.set("dev.wildware.moba.agent")
    portRange.set("7840-7859")
}
```

`moba` is deliberately off the engine default of `7820-7839`: the box that develops this repository
also runs another project whose bridge scans an overlapping range, and a bridge does not check whose
game answered a port — it lists whatever is listening. Two overlapping ranges mean either project's
bridge can enumerate, drive and stop the other's game, and nothing warns you. It looks like an
instance vanishing.

## The test harness is the same code path

`SimHarness` (in `udea-agent`) drives a headless game through **exactly** the entry point an HTTP
command uses. It holds an `AgentBridge` and an `AgentRuntime` and has no reference to the tool index
at all, so there is nowhere to add a shortcut.

It is synchronous with no sleeps and no threads: `call` pumps the host loop until
`completedCommandId` covers the id it submitted, on the caller's thread.

And a pumped iteration **does not advance the tick**. A tool call must not move the clock as a side
effect of being delivered, or `time.step(200)` would advance by 200 plus however many commands were in
flight. The simulation advances when, and only when, something asks it to.

The point of all this: a scenario an agent found is a test you can check in, and a test that passes is
evidence about the agent surface rather than about a parallel path that resembles it.

## See also

- [The Editor](The-Editor) — the window that is a screen over these tools
- [Gizmos](Gizmos) — what a handle drag becomes in `editor.*` calls
- [Example Games](Example-Games) — `moba`, which serves the surface, and Hollow, which does not yet
- [Architecture](Architecture) — where `udea-agent` and `udea-agent-host` sit
- [Build and Verification](Build-and-Verification) — `udeaVerifyRelease` and the debug-only gates
- [Replay and Time Travel](Replay-and-Time-Travel) — the `replay.*` toolset
- [Tutorial: Make a Game](Tutorial-Make-a-Game) — wiring the surface into a new game
