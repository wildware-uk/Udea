import { spawn, type ChildProcess } from "node:child_process";
import { createWriteStream, type WriteStream } from "node:fs";
import { mkdir, readFile } from "node:fs/promises";
import { createServer as createNetServer } from "node:net";
import { tmpdir } from "node:os";
import { dirname, isAbsolute, join, resolve } from "node:path";

import { BridgeUsageError } from "./errors.js";

/**
 * Launching games, and owning what we launched.
 *
 * This exists because of three failures that kept recurring without it:
 * orphaned game windows nobody could close, a wedged instance holding a port
 * until someone killed it by hand, and parallel agents starting duplicates
 * because they could not tell what was already running. All three come from the
 * same root cause - the thing that started the game was not the thing that
 * could see or stop it. Here the bridge picks the port, starts the process,
 * knows its pid, and reaps it when the session ends.
 *
 * The rule about killing processes stands and is not weakened by any of this:
 * escalation past a graceful close is only ever applied to a child this process
 * spawned and is still tracking.
 */

export interface LaunchSpec {
  /** Shell command line. `{port}` is substituted. Use `argv` instead to avoid a shell. */
  command?: string;
  /** Argv form: [program, ...args]. `{port}` is substituted in each element. */
  argv?: string[];
  /** Working directory, resolved relative to the config file. Several checkouts of one game coexist. */
  cwd?: string;
  env?: Record<string, string>;
  /** Ports the launcher may claim, e.g. "7820-7839". */
  portRange?: string;
  /** How long to wait for GET /health after spawning. Gradle plus a JVM is slow. */
  readyTimeoutMs?: number;
  /** Extra arguments appended to the command line, for per-project flags. */
  extraArgs?: string[];
}

export interface ProjectConfig {
  name?: string;
  launch?: LaunchSpec;
  /** Where this came from, so errors can name the file the user has to fix. */
  file?: string;
}

/**
 * Ports the launcher claims by default.
 *
 * Deliberately clear of 7777 and 7800-7810: those are the conventional
 * single-instance port and the block people hand out by hand, so a launcher
 * that grabbed from there would take a port out from under a colleague who was
 * about to use it. Everything the launcher starts is discoverable through the
 * registry anyway, so it does not need to live in the scanned range.
 */
export const DEFAULT_LAUNCH_RANGE = "7820-7839";

export const CONFIG_FILENAMES = ["gamebridge.json", ".gamebridge.json"];

/**
 * Find the project's launch declaration by walking up from a starting
 * directory, the way every other JS tool finds its config.
 *
 * A project declares once how it is started, and no caller ever passes a
 * command string - which matters because the command is long, has flags that
 * are easy to get wrong, and differs between checkouts.
 */
export async function findProjectConfig(
  startDir: string,
  explicitPath?: string
): Promise<ProjectConfig | null> {
  if (explicitPath) {
    const path = isAbsolute(explicitPath) ? explicitPath : resolve(startDir, explicitPath);
    return readProjectConfig(path);
  }
  let dir = resolve(startDir);
  for (;;) {
    for (const name of CONFIG_FILENAMES) {
      const config = await readProjectConfig(join(dir, name), { quiet: true });
      if (config) return config;
    }
    const parent = dirname(dir);
    if (parent === dir) return null;
    dir = parent;
  }
}

async function readProjectConfig(path: string, opts: { quiet?: boolean } = {}): Promise<ProjectConfig | null> {
  let text: string;
  try {
    text = await readFile(path, "utf8");
  } catch {
    if (opts.quiet) return null;
    throw new BridgeUsageError(`No launch config at ${path}.`);
  }
  try {
    const raw = JSON.parse(text) as ProjectConfig;
    return { ...raw, file: path };
  } catch (e) {
    throw new BridgeUsageError(`Launch config ${path} is not valid JSON: ${(e as Error).message}`);
  }
}

/**
 * Fold a project's launch declaration into a config.
 *
 * A relative `cwd` in the file is resolved against the file, not against
 * wherever the MCP client happened to start this process - which is almost
 * never the project directory, and is the difference between "launches the
 * game" and "launches nothing with a baffling error".
 */
export async function applyProjectConfig<T extends { configPath?: string; launch?: LaunchSpec; projectName?: string }>(
  config: T,
  startDir: string = process.cwd()
): Promise<T> {
  const project = await findProjectConfig(startDir, config.configPath);
  if (!project) return config;
  config.projectName = project.name;
  config.launch = project.launch;
  if (project.launch?.cwd) {
    const base = project.file ? dirname(project.file) : startDir;
    config.launch = {
      ...project.launch,
      cwd: isAbsolute(project.launch.cwd) ? project.launch.cwd : resolve(base, project.launch.cwd),
    };
  }
  return config;
}

export interface LaunchedInstance {
  port: number;
  pid?: number;
  name?: string;
  version?: string;
  cwd: string;
  command: string;
  logFile: string;
  startedAt: number;
  child: ChildProcess;
  exited?: { code: number | null; signal: NodeJS.Signals | null };
  /** Last lines of the child's output, kept in memory so a boot failure can be explained. */
  tail: string[];
}

export interface LauncherOptions {
  /** Probe used to decide a port is free and later that the game is up. */
  health?: (port: number, timeoutMs: number) => Promise<boolean>;
  spawnImpl?: typeof spawn;
  sleep?: (ms: number) => Promise<void>;
  logDir?: string;
  now?: () => number;
}

const TAIL_LINES = 200;

/** Is anything at all listening on this port? Cheap and does not touch the game. */
export function portIsFree(port: number): Promise<boolean> {
  return new Promise((resolvePromise) => {
    const probe = createNetServer();
    probe.once("error", () => resolvePromise(false));
    probe.once("listening", () => probe.close(() => resolvePromise(true)));
    probe.listen(port, "127.0.0.1");
  });
}

/** Ask the OS for a free port by binding to 0 and immediately releasing it. */
export function ephemeralPort(): Promise<number> {
  return new Promise((resolvePromise, reject) => {
    const probe = createNetServer();
    probe.once("error", reject);
    probe.listen(0, "127.0.0.1", () => {
      const address = probe.address();
      const port = typeof address === "object" && address ? address.port : 0;
      probe.close(() => (port ? resolvePromise(port) : reject(new Error("could not obtain a port"))));
    });
  });
}

function expandRange(spec: string): number[] {
  const ports: number[] = [];
  for (const part of spec.split(",")) {
    const range = /^(\d+)\s*-\s*(\d+)$/.exec(part.trim());
    if (range) {
      for (let p = Number(range[1]); p <= Number(range[2]); p++) ports.push(p);
    } else if (part.trim()) {
      ports.push(Number(part.trim()));
    }
  }
  return ports;
}

export class Launcher {
  private readonly options: LauncherOptions;
  private readonly instances = new Map<number, LaunchedInstance>();
  private readonly streams = new Map<number, WriteStream>();

  constructor(options: LauncherOptions = {}) {
    this.options = options;
  }

  get logDir(): string {
    return this.options.logDir ?? join(tmpdir(), "game-bridge-logs");
  }

  launched(port: number): LaunchedInstance | undefined {
    return this.instances.get(port);
  }

  list(): LaunchedInstance[] {
    return [...this.instances.values()];
  }

  /**
   * Choose a port nobody is using.
   *
   * The declared range is tried in order and each candidate is verified twice -
   * nothing is bound to it, and nothing answers a health check on it - because
   * a game that is mid-startup has claimed the port in the way that matters
   * while still failing a bind test milliseconds earlier.
   */
  async pickPort(spec: string = DEFAULT_LAUNCH_RANGE, exclude: number[] = []): Promise<number> {
    const excluded = new Set(exclude);
    for (const port of expandRange(spec)) {
      if (excluded.has(port) || this.instances.has(port)) continue;
      if (!(await portIsFree(port))) continue;
      if (this.options.health && (await this.options.health(port, 300))) continue;
      return port;
    }
    // Falling back to an OS-assigned port keeps a launch working when the
    // declared range is full, which on a machine running several agents happens.
    return ephemeralPort();
  }

  /**
   * Start a game and wait until it answers on its debug port.
   *
   * Returns only once the game is genuinely reachable, so a caller never has to
   * write a retry loop of its own; if the child dies first, the failure carries
   * the tail of its output, because when a game fails to boot the stack trace
   * is the entire answer and it is otherwise visible only to whoever happens to
   * be tailing a log.
   */
  async launch(
    spec: LaunchSpec,
    opts: { port?: number; exclude?: number[]; name?: string } = {}
  ): Promise<LaunchedInstance> {
    if (!spec.command && !spec.argv?.length) {
      throw new BridgeUsageError(
        "No launch command configured. Add a gamebridge.json with { \"launch\": { \"command\": \"...{port}...\" } } " +
          "or pass --config <file>."
      );
    }

    const port = opts.port ?? (await this.pickPort(spec.portRange, opts.exclude));
    const cwd = spec.cwd ? resolve(spec.cwd) : process.cwd();
    const sub = (s: string) => s.replaceAll("{port}", String(port));

    const extra = (spec.extraArgs ?? []).map(sub);
    const useShell = !spec.argv?.length;
    const commandLine = useShell
      ? [sub(spec.command as string), ...extra].join(" ")
      : [...spec.argv!.map(sub), ...extra].join(" ");

    await mkdir(this.logDir, { recursive: true });
    const logFile = join(this.logDir, `instance-${port}-${Date.now()}.log`);
    const log = createWriteStream(logFile, { flags: "a" });

    const spawnImpl = this.options.spawnImpl ?? spawn;
    const child = useShell
      ? spawnImpl(commandLine, {
          cwd,
          shell: true,
          env: { ...process.env, ...spec.env },
          stdio: ["ignore", "pipe", "pipe"],
        })
      : spawnImpl(sub(spec.argv![0]), [...spec.argv!.slice(1).map(sub), ...extra], {
          cwd,
          env: { ...process.env, ...spec.env },
          stdio: ["ignore", "pipe", "pipe"],
        });

    const instance: LaunchedInstance = {
      port,
      pid: child.pid,
      name: opts.name,
      cwd,
      command: commandLine,
      logFile,
      startedAt: (this.options.now ?? Date.now)(),
      child,
      tail: [],
    };
    this.instances.set(port, instance);
    this.streams.set(port, log);

    const absorb = (chunk: Buffer | string) => {
      const text = String(chunk);
      log.write(text);
      for (const line of text.split(/\r?\n/)) {
        if (!line.trim()) continue;
        instance.tail.push(line);
        if (instance.tail.length > TAIL_LINES) instance.tail.shift();
      }
    };
    child.stdout?.on("data", absorb);
    child.stderr?.on("data", absorb);
    child.once("exit", (code, signal) => {
      instance.exited = { code, signal };
      log.end();
    });
    // A spawn that fails outright (no such command) surfaces here, not as a
    // throw, and would otherwise be an unhandled error event that takes the
    // whole MCP server down.
    child.once("error", (e) => absorb(`[game-bridge] spawn failed: ${e.message}\n`));

    const ready = await this.waitForReady(instance, spec.readyTimeoutMs ?? 180000);
    if (!ready) {
      const reason = instance.exited
        ? `the process exited with code ${instance.exited.code}`
        : `it did not answer GET /health within ${spec.readyTimeoutMs ?? 180000}ms`;
      await this.stop(port, { grace: 2000 });
      throw new BridgeUsageError(
        `Launch failed on port ${port}: ${reason}.\n` +
          `Command: ${commandLine}\nWorking directory: ${cwd}\nFull log: ${logFile}\n\n` +
          `Last output:\n${instance.tail.slice(-25).join("\n")}`
      );
    }
    return instance;
  }

  private async waitForReady(instance: LaunchedInstance, timeoutMs: number): Promise<boolean> {
    const health = this.options.health;
    const sleep = this.options.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (instance.exited) return false;
      if (health && (await health(instance.port, 500))) return true;
      if (!health) return true;
      await sleep(500);
    }
    return false;
  }

  /**
   * Stop an instance this launcher started: ask nicely, then insist.
   *
   * `close` runs the game's own teardown, which is what saves settings and
   * releases the window, so it is always tried first. Escalation is only
   * reached when the game is already unresponsive - and only ever on a pid this
   * process spawned, which is what makes it legitimate at all.
   */
  async stop(
    port: number,
    opts: { grace?: number; closeCommand?: () => Promise<void> } = {}
  ): Promise<{ port: number; stopped: boolean; how: string }> {
    const instance = this.instances.get(port);
    if (!instance) {
      throw new BridgeUsageError(
        `This bridge did not launch anything on port ${port}, so it will not stop it. ` +
          `Use call_tool { port: ${port}, name: "close" } to ask that game to shut itself down.`
      );
    }
    const grace = opts.grace ?? 8000;
    const sleep = this.options.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));

    if (!instance.exited && opts.closeCommand) {
      try {
        await opts.closeCommand();
      } catch {
        /* the game may already be gone, which is the outcome we wanted */
      }
    }

    const deadline = Date.now() + grace;
    while (!instance.exited && Date.now() < deadline) await sleep(100);
    if (instance.exited) {
      this.forget(port);
      return { port, stopped: true, how: "closed cleanly" };
    }

    this.terminate(instance);
    const hardDeadline = Date.now() + 5000;
    while (!instance.exited && Date.now() < hardDeadline) await sleep(100);
    const stopped = Boolean(instance.exited);
    this.forget(port);
    return { port, stopped, how: stopped ? "terminated after the clean close timed out" : "would not die" };
  }

  private terminate(instance: LaunchedInstance): void {
    if (!instance.pid) return;
    try {
      if (process.platform === "win32") {
        // A shell-spawned Gradle run is a process tree - the shell, the Gradle
        // launcher, the JVM holding the window - and killing only the parent
        // leaves the game on screen, which is the exact failure this whole
        // launcher exists to prevent. /T takes the tree we own.
        spawn("taskkill", ["/pid", String(instance.pid), "/T", "/F"], { stdio: "ignore" });
      } else {
        instance.child.kill("SIGTERM");
        setTimeout(() => instance.child.kill("SIGKILL"), 2000).unref();
      }
    } catch {
      /* nothing else to try; the caller is told it would not die */
    }
  }

  private forget(port: number): void {
    this.streams.get(port)?.end();
    this.streams.delete(port);
    this.instances.delete(port);
  }

  /**
   * Reap every child on the way out.
   *
   * The failure this prevents: an agent session ends, or crashes, and the game
   * windows it started stay on the user's desktop with no obvious owner. The
   * bridge started them, so the bridge closes them.
   */
  async shutdownAll(closeCommandFor?: (port: number) => Promise<void>): Promise<void> {
    const ports = [...this.instances.keys()];
    await Promise.all(
      ports.map((port) =>
        this.stop(port, {
          grace: 4000,
          closeCommand: closeCommandFor ? () => closeCommandFor(port) : undefined,
        }).catch(() => undefined)
      )
    );
  }
}
