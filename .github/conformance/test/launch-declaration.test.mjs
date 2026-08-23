/**
 * `gamebridge.json`, read by the code that really reads it.
 *
 * ## The hole this closes
 *
 * `launcher.ts` and `config.ts` were vendored, hashed and compiled, and then imported by
 * nothing. So the launch declaration - the one file that decides whether `launch_instance`
 * works at all - was the only part of the bridge contract with no test behind it: the manifest
 * had `ToolManifestBridgeParserTest` and a live conformance run, `/state` had the digest tests,
 * and the document that says how to *start* the game was verified by reading it.
 *
 * That matters more than it sounds, because every way this file can be wrong is silent.
 * `findProjectConfig` walks up the tree and returns `null` when it finds nothing, and the bridge
 * then reports "No launch command configured" - which reads as a bridge misconfiguration, not as
 * a missing generated file. A `cwd` resolved against the wrong base launches Gradle in the MCP
 * client's working directory, which is almost never the project. And a `command` without
 * `{port}` launches a game on the default port while the bridge waits on the one it picked, then
 * reports a boot timeout for a game that booted perfectly.
 *
 * So this parses the real file with the real functions. It needs no running game, which is why
 * it is a separate suite from `bridge-contract.test.mjs`: a launch declaration is wrong or right
 * before anything is started.
 *
 * ## It requires the file to exist, and that is the point
 *
 * `gamebridge.json` is generated, not committed - see the reasoning in `.gitignore` and in
 * `GradleWrapperCommand`, which is that the wrapper spelling differs between `cmd.exe` and a
 * POSIX shell and the bridge runs `launch.command` through a shell. "Generated" is only an
 * acceptable answer if something everyone runs generates it, so this suite failing with "no
 * launch declaration" is the correct outcome for a tree where nobody has built: it is the
 * assertion that the generating task really is on the path everyone takes.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join, resolve } from "node:path";

import { findProjectConfig, applyProjectConfig, CONFIG_FILENAMES } from "../dist/launcher.js";
import { parsePortRange } from "../dist/config.js";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..", "..", "..");

/** The ports a person hands out by hand. A launcher that claimed one would collide silently. */
const RESERVED = new Set([7777, ...Array.from({ length: 11 }, (_, i) => 7800 + i)]);

async function loadConfig() {
  const project = await findProjectConfig(repoRoot);
  assert.ok(
    project,
    `no launch declaration found walking up from ${repoRoot} (looked for ${CONFIG_FILENAMES.join(", ")}). ` +
      "It is generated, not committed: run `./gradlew :moba:udeaGenerateLaunchDeclaration` " +
      "(or any `assemble`/`run`, which depend on it) and try again."
  );
  return project;
}

test("the bridge's own finder locates the declaration and parses it", async () => {
  const project = await loadConfig();

  assert.equal(typeof project.name, "string");
  assert.ok(project.name.length > 0, "list_instances shows this name; an empty one is useless");
  assert.ok(project.launch, `the document has no "launch" block: ${JSON.stringify(project)}`);
  assert.equal(join(dirname(project.file), "gamebridge.json"), project.file);
});

/**
 * `{port}` is not decoration: it is the entire mechanism by which the bridge chooses a port.
 *
 * ## What this can and cannot prove
 *
 * It proves the substitution happens and that the port lands in a channel the run wiring
 * forwards - `-PdebugPort` / `-PagentPort`, which `AgentJvmArguments` turns into
 * `-Dudea.agent.port`, or that system property written directly. A spelling outside that set
 * reaches Gradle and stops there, and the game then listens on its default port while the bridge
 * polls the one it chose: reported as a boot timeout, for a game that booted perfectly.
 *
 * What no static check can prove is that the forwarding still *works* end to end - that is a
 * live launch of `:moba:run`, which needs a display this job does not have. It is stated here as
 * the remaining gap rather than papered over with a longer regex: `AgentJvmArguments` turning
 * `-PdebugPort` into `-Dudea.agent.port` is covered by `UdeaAgentPluginTest` in `udea-gradle`,
 * and the socket half is covered by the live instance the rest of this directory drives.
 */
test("the command carries {port} into a property the run wiring forwards", async () => {
  const project = await loadConfig();
  const command = project.launch.command ?? project.launch.argv?.join(" ");

  assert.ok(command, "neither launch.command nor launch.argv is present");
  assert.ok(command.includes("{port}"), `launch.command has no {port}: ${command}`);

  const substituted = command.replaceAll("{port}", "7831");
  assert.ok(
    /-(?:P(?:debugPort|agentPort)|Dudea\.agent\.port)=7831\b/.test(substituted),
    "substituting the port must set -PdebugPort, -PagentPort or -Dudea.agent.port; anything " +
      `else stops at Gradle and never reaches the game: ${substituted}`
  );
  assert.ok(!substituted.includes("{port}"), `an unsubstituted {port} remains: ${substituted}`);
});

/**
 * `cwd` resolves against the declaration, not against wherever the MCP client was started.
 *
 * This is the assertion the vendored `applyProjectConfig` exists to make true, and the reason
 * the generator writes `"."` rather than an absolute path: several checkouts of one game coexist
 * on a developer's machine, and an absolute path in a generated file sends every one of them to
 * the checkout that happened to build last.
 */
test("cwd resolves relative to the declaration and lands on the project root", async () => {
  const project = await loadConfig();
  assert.equal(project.launch.cwd, ".", "a generated cwd must stay relative to the file");

  // Started somewhere that is deliberately not the project root, which is the case that breaks.
  const applied = await applyProjectConfig({ configPath: project.file }, here);

  assert.equal(resolve(applied.launch.cwd), resolve(repoRoot));
});

test("the port range is a real range and stays clear of the ports people hand out", async () => {
  const project = await loadConfig();
  const ports = parsePortRange(project.launch.portRange);

  assert.ok(ports.length > 0);
  const collisions = ports.filter((p) => RESERVED.has(p));
  assert.deepEqual(
    collisions,
    [],
    `the launch range claims ${collisions.join(", ")}, which are the conventional single-instance ` +
      "port and the block a team hands out by hand - a collision there is silent, because both " +
      "sides are the same game"
  );
});

/**
 * The timeout is long enough for the thing it is timing.
 *
 * A cold Gradle build plus a JVM genuinely takes tens of seconds, and a launch that times out is
 * reported as a boot failure with the child's output attached - which sends the reader looking
 * for a crash that did not happen.
 */
test("readyTimeoutMs leaves room for a cold Gradle build", async () => {
  const project = await loadConfig();

  assert.equal(typeof project.launch.readyTimeoutMs, "number");
  assert.ok(
    project.launch.readyTimeoutMs >= 60_000,
    `readyTimeoutMs is ${project.launch.readyTimeoutMs}ms; a cold build takes longer than that`
  );
});

/**
 * The document is JSON the bridge's own `JSON.parse` accepts, escaping included.
 *
 * `LaunchDeclaration` renders by hand rather than through a serialiser, and `launch.cwd` is full
 * of backslashes on Windows. `findProjectConfig` already parsed it above; this reads the bytes
 * back so a failure names the file rather than an absent config.
 */
test("the rendered document is valid JSON with no stray escaping", async () => {
  const project = await loadConfig();
  const text = await readFile(project.file, "utf8");

  const parsed = JSON.parse(text);
  assert.equal(parsed.name, project.name);
  assert.equal(typeof parsed.launch.command, "string");
  // `LaunchDeclaration.quote` escapes control characters; a raw one in the file would be a
  // `JSON.parse` failure on somebody else's machine and a mystery on this one.
  const rawControl = [...parsed.launch.command].some((c) => c.codePointAt(0) < 0x20);
  assert.equal(rawControl, false, "a raw control character reached the command");
});
