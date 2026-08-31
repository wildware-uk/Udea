/**
 * Rewrites `VENDORED.json`'s hashes from the bytes now in `vendor/`.
 *
 * ## Why a writer exists at all
 *
 * Because the absence of one is what issue #171 was. `verify-vendor` had code behind it and the
 * recorded hashes did not, so three of six were produced by hand through a route that converted
 * line endings, described bytes that were never in this repository, and failed the gate every
 * time it ran. A number a human types is a number nobody can reproduce.
 *
 * This is the same arrangement as `udeaWriteProtocolLock` in the Gradle build, and it carries
 * the same warning: **review the diff.** Running this after editing a vendored source records
 * the edit and turns the gate green over a fork, which is precisely what the gate exists to
 * stop. The order is: re-copy from upstream `src/`, update `source.commit` and
 * `source.commitDate` by hand, run this, read what changed.
 *
 * It refuses to record bytes containing a CR. `game-bridge-mcp` is an LF repository, so a CR
 * means the copy came through a converting route; recording it would make verification agree
 * with a file that is no longer upstream's, which is the one failure raw-byte hashing cannot
 * see on its own.
 *
 *     npm run record-vendor
 */
import { readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";

import {
  containsCarriageReturn,
  hashVendoredFiles,
  manifestPath,
  readManifest,
  renderManifest,
  vendorDir,
  vendoredFilenames,
} from "./vendor-hash.mjs";

const manifest = await readManifest();
const names = await vendoredFilenames();

const converted = [];
for (const name of names) {
  if (containsCarriageReturn(await readFile(join(vendorDir, name)))) converted.push(name);
}

if (converted.length > 0) {
  console.error(
    `Refusing to record: CR found in ${converted.join(", ")}. ${manifest.source.repository} is ` +
      "an LF repository, so this is a converted copy rather than upstream's bytes. Re-copy " +
      "without line-ending translation and run this again."
  );
  process.exit(1);
}

const before = manifest.files;
const after = await hashVendoredFiles(names);
await writeFile(manifestPath, renderManifest(manifest.source, after), "utf8");

// A newly vendored file is "changed" too: `before[name]` is undefined and `after[name]` is not.
const changed = names.filter((name) => before[name] !== after[name]);
const removed = Object.keys(before).filter((name) => !names.includes(name));

console.log(
  `recorded ${names.length} file(s) at ${manifest.source.repository}@${manifest.source.commit}`
);
for (const name of changed) {
  console.log(`  ${name}: ${before[name] ?? "(new)"} -> ${after[name]}`);
}
for (const name of removed) console.log(`  ${name}: removed`);

if (changed.length === 0 && removed.length === 0) {
  console.log("  no change");
} else {
  console.log(
    "Review the diff: it is the claim that this directory holds upstream's code and not a fork."
  );
}
