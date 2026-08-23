/**
 * `close` is followed by silence on the port.
 *
 * Its own script rather than a test in `bridge-contract.test.mjs`, because it is the only
 * assertion that requires the instance to be **gone**: node's test runner gives no ordering
 * guarantee strong enough to put "take the game down" last, and a run where it went first would
 * fail every other test for the wrong reason.
 *
 * The assertion matters because `close` is the one command with no confirmation to wait for.
 * The game is on its way out, so nothing will ever report the command completed; the port going
 * quiet is the only success signal `GameClient.waitForSilence` has. A host that leaves its HTTP
 * server bound after the game has stopped - a non-daemon executor thread, a shutdown hook that
 * did not run - leaves the bridge listing an instance it will keep failing against, and nothing
 * on the Kotlin side would notice.
 *
 * The caller stops the instance; this only watches. Usage:
 *
 *     UDEA_AGENT_PORT=7820 node scripts/expect-silence.mjs
 */
import { GameClient } from "../dist/client.js";
import { GameOffline } from "../dist/errors.js";

const port = Number(process.env.UDEA_AGENT_PORT ?? 7820);
const client = new GameClient(port, { timeoutMs: 2000 });

const silent = await client.waitForSilence(30_000);
if (!silent) {
  console.error(
    `port ${port} is still answering 30s after the instance was stopped; the bridge would keep ` +
      `this instance listed and keep failing against it`
  );
  process.exit(1);
}

try {
  await client.state();
  console.error(`GET /state on port ${port} succeeded after waitForSilence reported silence`);
  process.exit(1);
} catch (e) {
  if (!(e instanceof GameOffline)) {
    console.error(`expected GameOffline once the port is quiet, got ${e?.constructor?.name}: ${e}`);
    process.exit(1);
  }
}

console.log(`port ${port} went quiet and GET /state reports GameOffline`);
