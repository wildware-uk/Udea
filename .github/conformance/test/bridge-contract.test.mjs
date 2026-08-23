/**
 * The `game-bridge-mcp` contract, asserted with the real client against a live instance.
 *
 * Nothing here re-implements the bridge. `GameClient` and `normaliseManifest` are the compiled
 * vendored sources, so a failure means the shipped bridge would have failed the same way -
 * which is the only kind of evidence this gate is worth having.
 *
 * The instance is `:udea-agent-host:udeaPhase1Demo`, started by the caller (CI, or a second
 * terminal) and addressed by `UDEA_AGENT_PORT`. It is deliberately not launched from here: a
 * test that starts a JVM measures its own start-up, and a flaky launch would be read as a
 * broken contract.
 */
import test from "node:test";
import assert from "node:assert/strict";

import { GameClient } from "../dist/client.js";
import { normaliseManifest, toolIndex, toInputSchema, SUPPORTED_PROTOCOL } from "../dist/manifest.js";

const PORT = Number(process.env.UDEA_AGENT_PORT ?? 7820);
const client = new GameClient(PORT, { timeoutMs: 10_000 });

/**
 * The instance answers `/health` the way the client insists on.
 *
 * `GameClient.health` throws `NotAGameSurface` for anything that is not an object with
 * `ok === true`. That is not a formality: an instance the bridge cannot identify is dropped
 * from discovery entirely, so a renamed field does not degrade the surface, it removes it.
 */
test("health identifies this as a game surface", async () => {
  const health = await client.health();

  assert.equal(health.ok, true);
  assert.equal(typeof health.frame, "number", `/health must carry a numeric frame: ${JSON.stringify(health)}`);
});

/**
 * The confirmed path: `completedCommandId` exists, and it advances past the id `/command`
 * acknowledged.
 *
 * `confirmed: true` is the whole reason the agent surface publishes `completedCommandId` at
 * all. Without it `commandAndSync` degrades to watching `frame` advance twice, which means
 * "the loop is running", not "your command ran" - and a paused game advances `frame` while
 * running no ticks at all, so the degraded path can confirm a command that has not happened.
 */
test("commandAndSync takes the completedCommandId path, not the frame fallback", async () => {
  const result = await client.commandAndSync("time.pause", {});

  assert.equal(result.confirmed, true, "the bridge fell back to frame-watching; completedCommandId is missing or stalled");
  assert.equal(typeof result.commandId, "number");
  assert.ok(
    result.state.completedCommandId >= result.commandId,
    `completedCommandId ${result.state.completedCommandId} is behind commandId ${result.commandId}`
  );
});

/**
 * A tick is a tick.
 *
 * `time.step` is the tool every determinism measurement rests on, and the recorded defect it
 * replaces is a harness that waited on *render frames* and so made `step(n)` approximate. The
 * assertion is therefore about `tick` and never about `frame`.
 */
test("time.step advances exactly the ticks asked for, confirmed", async () => {
  await client.commandAndSync("time.pause", {});
  const before = await client.state();
  const stepped = await client.commandAndSync("time.step", { ticks: 25 });

  assert.equal(stepped.confirmed, true);
  assert.equal(
    stepped.state.tick - before.tick,
    25,
    `expected 25 ticks, got ${before.tick} -> ${stepped.state.tick}`
  );
  // Both names, because the client's own summariser reads `simFrame` and has no branch for
  // `tick`: a document carrying only one of them costs a full /state on every step.
  assert.equal(stepped.state.simFrame, stepped.state.tick);
});

/**
 * The degradation, exercised on purpose.
 *
 * Asserting only the good path leaves you unable to say which branch a green run took. This
 * drives the same client against a state document with `completedCommandId` removed - through
 * the injectable `fetchImpl` the client already exposes for its own tests - and asserts it
 * comes back `confirmed: false` rather than hanging or throwing. That is the behaviour a game
 * built before this contract gets, and the reason a lost `completedCommandId` is invisible
 * unless something checks for it.
 */
test("without completedCommandId the client degrades to frames and says so", async () => {
  const stripped = new GameClient(PORT, {
    timeoutMs: 10_000,
    sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
    fetchImpl: async (url, init) => {
      const res = await fetch(url, init);
      const body = await res.text();
      if (!url.includes("/state")) {
        return { ok: res.ok, status: res.status, text: async () => body };
      }
      const doc = JSON.parse(body);
      delete doc.completedCommandId;
      return { ok: res.ok, status: res.status, text: async () => JSON.stringify(doc) };
    },
  });

  // Resumed, because the fallback watches `frame` and the demo host advances `frame` per
  // iteration; a paused instance would still satisfy it, which is exactly the weakness being
  // demonstrated rather than something to hide behind.
  await client.commandAndSync("time.resume", {});
  const result = await stripped.commandAndSync("diag.frame_report", {});

  assert.equal(result.confirmed, false, "the fallback branch was not taken, so this test proves nothing");
  assert.equal(result.state.completedCommandId, undefined);
});

/**
 * The manifest survives `normaliseManifest` with nothing dropped.
 *
 * The parser is tolerant, and tolerant means silent: a tool whose `name` is not a string is
 * dropped, not reported. So a manifest bug does not fail anything - it makes a capability
 * invisible, and the only way to notice is to count.
 */
test("every published tool survives the bridge's manifest normalisation", async () => {
  const raw = await client.getJson("/tools");
  const manifest = normaliseManifest(raw, PORT);

  assert.equal(manifest.game.protocol, SUPPORTED_PROTOCOL, "the bridge would report a manifest it cannot read");
  assert.ok(manifest.toolsets.length > 0, "no toolset survived normalisation");

  const publishedNames = [];
  for (const set of raw.toolsets ?? []) {
    for (const tool of set.tools ?? []) publishedNames.push(tool.name);
  }
  const kept = [...toolIndex(manifest).keys()];

  assert.deepEqual(
    kept.sort(),
    publishedNames.sort(),
    "the bridge dropped a tool it could not read; a dropped tool is a capability that exists and cannot be called"
  );
  // The engine's own surface, which every Udea game gets for free. Named explicitly so that a
  // toolset silently disappearing is a failure here rather than a smaller number nobody reads.
  for (const required of ["world.query_entities", "time.step", "events.recent_events", "diag.frame_report"]) {
    assert.ok(kept.includes(required), `${required} is missing from the published manifest`);
  }
});

/**
 * Every tool's schema is a JSON Schema object an MCP client will accept.
 *
 * `toInputSchema` passes a game-supplied `inputSchema` through verbatim, so a malformed one
 * reaches the MCP client unaltered and the tool is refused there - past the bridge, past this
 * repository, in a place with no useful error.
 */
test("every tool's inputSchema is a JSON Schema object a strict client accepts", async () => {
  const manifest = normaliseManifest(await client.getJson("/tools"), PORT);

  for (const [name, tool] of toolIndex(manifest)) {
    const schema = toInputSchema(tool);
    assert.equal(typeof schema, "object", `${name}: schema is not an object`);
    assert.equal(schema.type, "object", `${name}: schema.type must be "object"`);
    assert.equal(typeof schema.properties, "object", `${name}: schema has no properties object`);

    for (const [prop, def] of Object.entries(schema.properties)) {
      assert.equal(typeof def, "object", `${name}.${prop}: property is not an object`);
      assert.ok(
        ["string", "number", "integer", "boolean", "object", "array"].includes(def.type),
        `${name}.${prop}: ${def.type} is not a JSON Schema type`
      );
      assert.ok(
        typeof def.description === "string" && def.description.length > 0,
        `${name}.${prop}: no description, so the property tells the model nothing`
      );
      // A `default` on a strictly-typed property is something a strict client is entitled to
      // reject, which is why both sides fold it into the description instead.
      assert.equal(def.default, undefined, `${name}.${prop}: emitted a default`);
    }
    for (const required of schema.required ?? []) {
      assert.ok(
        Object.hasOwn(schema.properties, required),
        `${name}: required names ${required}, which is not a property`
      );
    }
  }
});
