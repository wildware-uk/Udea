import { readdir, readFile, unlink } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";

/**
 * The instance registry: how running games announce themselves.
 *
 * Port scanning is the weak form of discovery. It is bounded by whatever range
 * someone guessed at, it says nothing about a game until the game answers, and
 * during startup - the exact moment an agent is most likely to look - it
 * reports a false negative. So a game that has successfully bound its debug
 * port writes a small JSON file naming itself, and a reader gets the full list
 * of what is running without guessing at ports at all.
 *
 * Entries are advisory and never authoritative. A crash or a force-kill leaves
 * the file behind, so every entry is verified against the live port before it
 * is believed, and an entry whose port does not answer is a stale file, not a
 * running game.
 */
export interface RegistryEntry {
  name?: string;
  version?: string;
  protocol?: number;
  port: number;
  pid?: number;
  host?: string;
  started?: string;
  cwd?: string;
  /** Absolute path of the file this came from, so a stale one can be named or removed. */
  file: string;
}

export const DEFAULT_REGISTRY_SUBDIR = ".game-bridge";

/**
 * Where to look for entries.
 *
 * `GAME_BRIDGE_INSTANCES` points straight at the directory of entry files;
 * `GAME_BRIDGE_HOME` points at the directory that contains `instances/`. Both
 * exist because a game writing the registry will naturally have configured one
 * or the other, and a reader that only understood one of them would silently
 * find nothing.
 */
export function registryDir(env: Record<string, string | undefined> = process.env): string {
  if (env.GAME_BRIDGE_INSTANCES) return env.GAME_BRIDGE_INSTANCES;
  const home = env.GAME_BRIDGE_HOME ?? join(homedir(), DEFAULT_REGISTRY_SUBDIR);
  return join(home, "instances");
}

export interface RegistryRead {
  dir: string;
  entries: RegistryEntry[];
  /** Files that were present but unreadable or not entry-shaped. */
  unreadable: string[];
}

export interface RegistryIo {
  readdir?: (dir: string) => Promise<string[]>;
  readFile?: (path: string) => Promise<string>;
  unlink?: (path: string) => Promise<void>;
}

/**
 * Read every entry in the registry directory.
 *
 * Never throws for the ordinary cases - a missing directory, an empty one, a
 * half-written file caught mid-flush. Discovery that fails because one game
 * wrote a truncated JSON file would be worse than no discovery at all.
 */
export async function readRegistry(dir: string, io: RegistryIo = {}): Promise<RegistryRead> {
  const list = io.readdir ?? ((d: string) => readdir(d));
  const read = io.readFile ?? ((p: string) => readFile(p, "utf8"));

  let files: string[];
  try {
    files = (await list(dir)).filter((f) => f.endsWith(".json"));
  } catch {
    return { dir, entries: [], unreadable: [] };
  }

  const entries: RegistryEntry[] = [];
  const unreadable: string[] = [];

  await Promise.all(
    files.map(async (file) => {
      const path = join(dir, file);
      try {
        const raw = JSON.parse(await read(path)) as Record<string, unknown>;
        const port = Number(raw.port);
        if (!Number.isInteger(port) || port < 1 || port > 65535) {
          unreadable.push(path);
          return;
        }
        entries.push({
          port,
          name: typeof raw.name === "string" ? raw.name : undefined,
          version: typeof raw.version === "string" ? raw.version : undefined,
          protocol: typeof raw.protocol === "number" ? raw.protocol : undefined,
          pid: typeof raw.pid === "number" ? raw.pid : undefined,
          host: typeof raw.host === "string" ? raw.host : undefined,
          started: typeof raw.started === "string" ? raw.started : undefined,
          cwd: typeof raw.cwd === "string" ? raw.cwd : undefined,
          file: path,
        });
      } catch {
        unreadable.push(path);
      }
    })
  );

  // Two entries for one port means one of them is stale - a game crashed and a
  // new one took the port. The newer entry is the one that can still be true.
  const byPort = new Map<number, RegistryEntry>();
  for (const entry of entries.sort((a, b) => (a.started ?? "").localeCompare(b.started ?? ""))) {
    byPort.set(entry.port, entry);
  }

  return { dir, entries: [...byPort.values()].sort((a, b) => a.port - b.port), unreadable };
}

/**
 * Delete an entry file whose port has been verified as not answering.
 *
 * Opt-in, because the caller and the file's owner are different processes: a
 * game that is slow to bind its port looks exactly like a crashed one for a
 * second or two, and silently deleting its entry would be a race the game
 * cannot win.
 */
export async function pruneEntry(entry: RegistryEntry, io: RegistryIo = {}): Promise<boolean> {
  const remove = io.unlink ?? ((p: string) => unlink(p));
  try {
    await remove(entry.file);
    return true;
  } catch {
    return false;
  }
}
