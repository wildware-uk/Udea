/**
 * Fails when the vendored `game-bridge-mcp` sources are not the bytes `VENDORED.json` describes.
 *
 * "Vendored" has to mean something or the gate is theatre. The whole value of driving the real
 * client is that it is the code an agent actually runs; a local edit to make an assertion pass
 * turns this directory into a second implementation that agrees with itself while the shipped
 * bridge fails. So the hashes are checked before anything is compiled.
 *
 * Refreshing the copy is deliberate and reviewable: re-copy from the upstream `src/`, update
 * `source.commit`, run `npm run record-vendor`, and put the new hashes in the same diff.
 *
 * ## Three checks, where there used to be one that had never passed
 *
 * This script and the manifest it reads disagreed from the day both were committed - issue
 * #171. `client.ts`, `config.ts` and `manifest.ts` were recorded as the SHA-256 of upstream's
 * sources *with CRLF line endings*, and committed with LF; the other three were recorded from
 * raw bytes and matched. Neither the vendored bytes nor the recorded hashes were touched again,
 * so the conformance job stopped on this step every time it ran and nothing behind it - not the
 * launch declaration, not the headless boot, not the live client - had ever executed.
 *
 * Hashes alone were not enough to notice, so two more checks stand beside them:
 *
 *   - a vendored file the manifest does not name is a file nothing hashes, which is the quiet
 *     fork this directory exists to prevent arriving through the door the guard did not watch;
 *   - a CR in a vendored file means the copy came through a converting route. Recording and
 *     verifying both hash raw bytes, so they would agree about a converted copy for ever; this
 *     is the one shape the hashes cannot see, and it is the shape that actually happened.
 */
import { readFile } from "node:fs/promises";
import { join } from "node:path";

import {
  containsCarriageReturn,
  hashBytes,
  readManifest,
  vendorDir,
  vendoredFilenames,
} from "./vendor-hash.mjs";

const manifest = await readManifest();
const problems = [];

for (const [name, expected] of Object.entries(manifest.files)) {
  let bytes;
  try {
    bytes = await readFile(join(vendorDir, name));
  } catch (e) {
    problems.push(`${name} is missing: ${e.message}`);
    continue;
  }

  const actual = hashBytes(bytes);
  if (actual !== expected) {
    problems.push(
      `${name} does not match its recorded hash.\n    recorded ${expected}\n    actual   ${actual}\n` +
        `    Re-copy it from ${manifest.source.repository} ${manifest.source.path} at the ` +
        `recorded commit, or re-record deliberately with \`npm run record-vendor\`.`
    );
  }

  if (containsCarriageReturn(bytes)) {
    problems.push(
      `${name} contains a CR. ${manifest.source.repository} is an LF repository, so this copy ` +
        "came through a line-ending conversion and is no longer byte-for-byte upstream, " +
        "whatever its recorded hash says."
    );
  }
}

const unlisted = (await vendoredFilenames()).filter((name) => !(name in manifest.files));
for (const name of unlisted) {
  problems.push(
    `${name} sits in vendor/ and VENDORED.json does not name it, so nothing hashes it. ` +
      "Add it with `npm run record-vendor` or delete it."
  );
}

if (problems.length > 0) {
  console.error(
    `The vendored game-bridge-mcp client does not match VENDORED.json ` +
      `(commit ${manifest.source.commit}):\n  - ${problems.join("\n  - ")}`
  );
  process.exit(1);
}

console.log(
  `vendored client verified: ${Object.keys(manifest.files).length} file(s) at ` +
    `${manifest.source.repository}@${manifest.source.commit}`
);
