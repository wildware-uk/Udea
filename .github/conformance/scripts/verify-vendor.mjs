/**
 * Fails when a vendored `game-bridge-mcp` source has been edited.
 *
 * "Vendored" has to mean something or the gate is theatre. The whole value of driving the real
 * client is that it is the code an agent actually runs; a local edit to make an assertion pass
 * turns this directory into a second implementation that agrees with itself while the shipped
 * bridge fails. So the hashes recorded in VENDORED.json are checked before anything is compiled.
 *
 * Refreshing the copy is deliberate and reviewable: re-copy from the upstream `src/`, run this,
 * and put the new hashes and the new commit in the same diff.
 */
import { readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const vendor = join(here, "..", "vendor");

const manifest = JSON.parse(await readFile(join(vendor, "VENDORED.json"), "utf8"));
const problems = [];

for (const [name, expected] of Object.entries(manifest.files)) {
  let actual;
  try {
    actual = createHash("sha256").update(await readFile(join(vendor, name))).digest("hex");
  } catch (e) {
    problems.push(`${name} is missing: ${e.message}`);
    continue;
  }
  if (actual !== expected) {
    problems.push(
      `${name} has been edited.\n    recorded ${expected}\n    actual   ${actual}\n` +
        `    Re-copy it from ${manifest.source.repository} ${manifest.source.path} at the ` +
        `recorded commit, or update VENDORED.json deliberately.`
    );
  }
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
