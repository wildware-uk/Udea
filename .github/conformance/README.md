# The `game-bridge-mcp` conformance gate

`game-bridge-mcp` is the MCP bridge an agent actually drives a Udea game through. It contains
no knowledge of any particular game: it reads `GET /health` to decide there is a game there,
`GET /tools` to learn what the game can be told to do, and `GET /command` + `GET /state` to do
it. Every one of those is a contract this engine implements on the other side.

Spec section 7 names silent drift from that contract as a top risk, and the reason is specific:
**the bridge fails quietly**. `normaliseManifest` drops a malformed tool rather than reporting
it, so a manifest bug makes a capability *invisible* instead of making anything red.
`commandAndSync` falls back from `completedCommandId` to "two frames have gone by" when the game
does not publish the field, so a regression that loses `completedCommandId` shows up as a bridge
that confirms commands before they have happened - a race that looks like a flaky test and is
not. Neither shows up in a Kotlin test, because neither is on the Kotlin side.

So this directory drives **the real client**, verbatim, against a **live headless instance**.

## What is vendored, and what "vendored" means here

`vendor/` holds the client's own TypeScript, copied byte for byte from `game-bridge-mcp`. Not a
re-implementation and not a summary: a re-implementation would drift in exactly the way this
gate exists to catch, and would agree with itself while the real client failed.

`vendor/VENDORED.json` records the upstream repository, commit and the SHA-256 of every copied
file. `npm run verify-vendor` recomputes them, so an edit to a vendored file - a "small fix" to
make a test pass - is a red gate rather than a quiet fork. Refreshing the copy is deliberate:
re-copy the files, re-run `verify-vendor`, and update the hashes and the commit in the same
diff somebody reads.

Only the files the contract assertions need are copied: `client.ts`, `manifest.ts`, `errors.ts`,
and `config.ts` with `registry.ts` behind it. `server.ts`, `bridge.ts` and `cli.ts` are the MCP
server around the client and depend on `@modelcontextprotocol/sdk`; they add a dependency and no
assertion, so they stay out.

## Running it

```
# terminal 1 - a live headless game with the agent surface bound on loopback
./gradlew :udea-agent-host:udeaPhase1Demo -Pudea.agent.port=7820

# terminal 2
cd .github/conformance
npm install
npm test               # UDEA_AGENT_PORT defaults to 7820
```

CI does the same thing in the `bridge-conformance` job of `.github/workflows/ci.yml`.

## What it asserts, and why each one

| assertion | the failure it catches |
|---|---|
| `health()` accepts `/health` | the client refuses anything that is not `{"ok":true,...}`; a renamed or restyled field takes the instance off the bridge's map entirely |
| `commandAndSync` returns `confirmed: true` | `completedCommandId` is present and advances. Without it the client silently degrades to frame-watching |
| the degraded path is exercised too | the fallback is asserted against a state document with `completedCommandId` removed, so we know which branch a passing test took |
| `/tools` survives `normaliseManifest` with **no tool dropped** | a malformed entry makes a capability invisible with nothing reporting it |
| every tool's `inputSchema` is a valid JSON Schema object | a strict MCP client rejects the tool before the call leaves the bridge |
| `waitForSilence` returns true after the instance stops | `close` has no confirmation to wait for; the port going quiet is the only success signal |
