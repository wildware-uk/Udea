/**
 * The one procedure that turns a vendored file into the string written in `VENDORED.json`.
 *
 * ## Why this is a module and not two copies of four lines
 *
 * `VENDORED.json` shipped with three of its six hashes describing bytes that are not in this
 * repository and never were: `client.ts`, `config.ts` and `manifest.ts` were recorded as
 * SHA-256 of the upstream source *with CRLF line endings*, while the files themselves were
 * committed with LF - which is what upstream has. `verify-vendor` hashes the bytes on disk, so
 * it disagreed with the manifest on those three from the day both were added, and the
 * conformance job stopped on its first step every time it ran. Issue #171.
 *
 * The recorded side of that comparison had no code behind it. Somebody produced six hex strings
 * by hand, out of band, and three of them came through a route that rewrote the line endings on
 * the way. That is not a mistake a careful person avoids reliably; it is a mistake a missing
 * tool guarantees. So recording and verifying now call the same function, in the same file, and
 * cannot disagree about what "the hash of this file" means.
 *
 * `containsCarriageReturn` is the second half. Hashing raw bytes on both sides makes the two
 * agree, but it makes them agree about whatever bytes are on disk: a future re-copy that
 * arrived through a CRLF path would be recorded as CRLF, verified as CRLF, and pass - a silent
 * fork from an upstream that has no CR in it. The fence rejects that shape outright, and the
 * `.gitattributes` beside `vendor/` stops a checkout reintroducing it.
 */
import { readFile, readdir } from "node:fs/promises";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));

/** The directory holding the copied `game-bridge-mcp` sources and their manifest. */
export const vendorDir = join(here, "..", "vendor");

export const manifestPath = join(vendorDir, "VENDORED.json");

/** SHA-256, hex, over the file's bytes exactly as they sit on disk. No normalisation. */
export function hashBytes(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

/** True when the bytes carry a CR. Upstream has none; a CR here means a converted copy. */
export function containsCarriageReturn(bytes) {
  return bytes.includes(0x0d);
}

export async function readManifest() {
  return JSON.parse(await readFile(manifestPath, "utf8"));
}

/** Every `.ts` file sitting in `vendor/`, sorted, whether or not the manifest mentions it. */
export async function vendoredFilenames() {
  const entries = await readdir(vendorDir);
  return entries.filter((name) => name.endsWith(".ts")).sort();
}

/** `{ [name]: sha256 }` over the bytes on disk, in the same sorted order the manifest uses. */
export async function hashVendoredFiles(names) {
  const hashes = {};
  for (const name of names) {
    hashes[name] = hashBytes(await readFile(join(vendorDir, name)));
  }
  return hashes;
}

/**
 * The exact text of `VENDORED.json`. Recording writes this; the test compares the committed
 * file against it, so a hand-edited hash - the way the three wrong ones got in - is a failure
 * rather than something only a later CI run would notice.
 */
export function renderManifest(source, hashes) {
  return `${JSON.stringify({ source, files: hashes }, null, 2)}\n`;
}
