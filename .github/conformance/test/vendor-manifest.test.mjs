/**
 * `VENDORED.json` describes the bytes in `vendor/`, and can be shown to.
 *
 * ## The hole this closes
 *
 * `verify-vendor` is the first step of the `game-bridge-mcp conformance` job, and it had never
 * passed. Three of the six recorded hashes - `client.ts`, `config.ts`, `manifest.ts` - describe
 * the upstream sources with CRLF line endings; the files were committed with LF, which is what
 * upstream has. Nobody edited anything: `git log --follow` returns exactly one commit for each
 * vendored file *and* for `VENDORED.json`, so the two have never diverged. They were wrong when
 * they were written, and the gate stopped there every time it ran. Issue #171.
 *
 * That made the entire job dead. Not the launch declaration, not the bridge's own reader, not
 * the headless boot, not the real client against `/health` - none of it had ever executed, and
 * the whole workflow was red for other reasons, so one more red step read as the same red.
 *
 * ## Why these assertions and not just "verify-vendor passes"
 *
 * `verify-vendor` compares two numbers. It cannot tell you that the number it was compared
 * against came from these bytes rather than from somebody's terminal, and that is exactly the
 * failure that shipped. So the suite asserts the whole shape:
 *
 *   - the recorded hashes describe the bytes (the bug itself);
 *   - the committed `VENDORED.json` is byte-identical to what `record-vendor` writes, so a hand
 *     edited hash cannot survive - the recording route now has code behind it;
 *   - every `.ts` in `vendor/` appears in the manifest, so a file cannot be vendored and left
 *     unhashed;
 *   - no vendored file contains a CR, which is the specific route the wrong hashes came in by
 *     and the one shape that recording-and-verifying-raw-bytes would otherwise agree on.
 *
 * It needs no compile, no JVM and no running game: the manifest is right or wrong before
 * anything is started, which is why it is a separate suite from `bridge-contract.test.mjs`.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { join } from "node:path";

import {
  containsCarriageReturn,
  hashBytes,
  hashVendoredFiles,
  manifestPath,
  readManifest,
  renderManifest,
  vendorDir,
  vendoredFilenames,
} from "../scripts/vendor-hash.mjs";

test("every recorded hash describes the bytes of the file it names", async () => {
  const manifest = await readManifest();
  const wrong = [];

  for (const [name, recorded] of Object.entries(manifest.files)) {
    // A named file that is not there is a mismatch too, and reporting it as one keeps the
    // failure legible: an uncaught ENOENT here names node's internals, not the vendored copy.
    let actual;
    try {
      actual = hashBytes(await readFile(join(vendorDir, name)));
    } catch (e) {
      wrong.push(`${name}: recorded ${recorded}, but the file is missing (${e.message})`);
      continue;
    }
    if (actual !== recorded) wrong.push(`${name}: recorded ${recorded}, actual ${actual}`);
  }

  assert.deepEqual(
    wrong,
    [],
    `VENDORED.json does not describe vendor/. Either the copy was edited, or - as in #171 - the ` +
      `hashes were recorded from bytes that are not these:\n  ${wrong.join("\n  ")}`
  );
});

/**
 * The recorded side of the comparison has a generator, and the committed file is its output.
 *
 * Without this, `record-vendor` is advice. A hash typed into the file by hand is indisputably
 * how the three wrong ones arrived, and it would pass the assertion above the moment somebody
 * typed a *right* one - so agreeing with the bytes is not enough on its own. The manifest must
 * be the thing the tool writes.
 */
test("the committed manifest is byte-identical to what record-vendor writes", async () => {
  const manifest = await readManifest();
  const committed = await readFile(manifestPath, "utf8");

  const rendered = renderManifest(manifest.source, await hashVendoredFiles(await vendoredFilenames()));

  assert.equal(
    rendered,
    committed,
    "VENDORED.json is not what `npm run record-vendor` produces. Re-copy the sources, run it, " +
      "and review the diff - do not hand-edit a hash."
  );
});

/**
 * A vendored file the manifest does not mention is a vendored file nothing hashes.
 *
 * `verify-vendor` iterates the manifest, so adding `vendor/foo.ts` without adding a row leaves
 * it outside the gate entirely - the quiet fork the whole directory exists to prevent, arriving
 * through the one door the guard does not watch.
 */
test("every .ts in vendor/ is named by the manifest", async () => {
  const manifest = await readManifest();

  assert.deepEqual(await vendoredFilenames(), Object.keys(manifest.files).sort());
});

/**
 * No CR anywhere in the vendored copy.
 *
 * This is #171's cause expressed as a fence rather than as a corrected number. Hashing raw
 * bytes on both sides makes recording and verifying agree - but agree about whatever is on
 * disk. A refresh that came through a CRLF path would be recorded CRLF, verified CRLF, and pass
 * while silently differing from an upstream that has no CR in it. The gate would be green and
 * the copy would no longer be a copy.
 */
test("no vendored file has been through a line-ending conversion", async () => {
  const converted = [];

  for (const name of await vendoredFilenames()) {
    if (containsCarriageReturn(await readFile(join(vendorDir, name)))) converted.push(name);
  }

  assert.deepEqual(
    converted,
    [],
    `these vendored files contain CR: ${converted.join(", ")}. game-bridge-mcp is an LF ` +
      "repository, so a CR means the copy arrived through a converting route and is no longer " +
      "byte-for-byte upstream, whatever its hash says."
  );
});
