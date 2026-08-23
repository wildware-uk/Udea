import { BridgeUsageError } from "./errors.js";
import { registryDir as defaultRegistryDir } from "./registry.js";
import type { LaunchSpec } from "./launcher.js";

export const DEFAULT_PORT = 7777;

/**
 * The ports scanned by `list_instances` when the caller does not name a range.
 *
 * 7777 is the conventional single-instance port; 7800-7810 is the block a team
 * of agents can hand out one port at a time without collisions. Both are only
 * defaults - a project that uses different ports passes --scan-range.
 */
export const DEFAULT_SCAN_RANGE = "7777,7800-7810";

/**
 * What a dead port tells the caller to run. The bridge is game-agnostic, so it
 * cannot know the real command; a project sets its own with --launch-hint and
 * then the error message is genuinely actionable rather than a shrug.
 * `{port}` is substituted at the point of use.
 */
export const DEFAULT_LAUNCH_HINT =
  "<your game launch command> --debug-port={port}   (set a real one with --launch-hint)";

export interface BridgeConfig {
  /** Port used when a tool call does not name one. */
  defaultPort: number;
  /** Ports `list_instances` sweeps by default. */
  scanPorts: number[];
  /** Shown when a port is dead; `{port}` is substituted. */
  launchHint: string;
  /** Per-request HTTP timeout. */
  requestTimeoutMs: number;
  /** How long `call_tool` waits for a queued command to be applied. */
  commandTimeoutMs: number;
  /** Optional manifest file used when a game does not serve GET /tools. */
  manifestPath?: string;
  /** Directory holding self-registered instance entries. */
  registryDir: string;
  /** Read the instance registry during discovery. */
  useRegistry: boolean;
  /** Sweep the port range during discovery. Kept on by default: games predating the registry exist. */
  useScan: boolean;
  /** Advertise every tool flatly instead of the four discovery tools. */
  eager: boolean;
  /** Explicit path to a project launch config, instead of discovering one. */
  configPath?: string;
  /** Filled in from the project config file, if one was found. */
  launch?: LaunchSpec;
  /** Project name, for messages. */
  projectName?: string;
}

export interface ParsedCli {
  action: "run" | "help" | "version";
  config: BridgeConfig;
}

/**
 * Expand "7777,7800-7810" into explicit ports.
 *
 * Ranges are inclusive because that is how a person reads "7800-7810", and the
 * off-by-one in the other direction silently loses the last agent's instance.
 */
export function parsePortRange(spec: string): number[] {
  const ports: number[] = [];
  for (const chunk of spec.split(",")) {
    const part = chunk.trim();
    if (!part) continue;
    const range = /^(\d+)\s*-\s*(\d+)$/.exec(part);
    if (range) {
      const lo = validPort(range[1]);
      const hi = validPort(range[2]);
      if (hi < lo) throw new BridgeUsageError(`Bad port range '${part}': ${hi} is below ${lo}.`);
      if (hi - lo > 512) {
        throw new BridgeUsageError(
          `Port range '${part}' covers ${hi - lo + 1} ports. Scanning that many is slow enough to look hung; keep it under 512.`
        );
      }
      for (let p = lo; p <= hi; p++) ports.push(p);
    } else {
      ports.push(validPort(part));
    }
  }
  if (ports.length === 0) throw new BridgeUsageError(`No ports in range spec '${spec}'.`);
  return [...new Set(ports)];
}

export function validPort(value: unknown): number {
  const n = typeof value === "number" ? value : Number(String(value).trim());
  if (!Number.isInteger(n) || n < 1 || n > 65535) {
    throw new BridgeUsageError(`'${String(value)}' is not a valid TCP port (1-65535).`);
  }
  return n;
}

/**
 * Resolve which instance a call is aimed at.
 *
 * The order is deliberate and is the whole reason this project exists: an
 * explicit `port` argument always wins, so one MCP server can drive every
 * running instance in the same session. The CLI flag and env var only supply
 * the default for calls that stay silent about the port, and 7777 is the
 * last resort so a single-instance setup needs no configuration at all.
 */
export function resolvePort(explicit: unknown, config: BridgeConfig): number {
  if (explicit === undefined || explicit === null || explicit === "") return config.defaultPort;
  return validPort(explicit);
}

export interface CliEnv {
  GAME_BRIDGE_PORT?: string;
  GAME_BRIDGE_SCAN_RANGE?: string;
  GAME_BRIDGE_LAUNCH_HINT?: string;
  GAME_BRIDGE_MANIFEST?: string;
  GAME_BRIDGE_SCAN?: string;
  GAME_BRIDGE_LAUNCH?: string;
  GAME_BRIDGE_CONFIG?: string;
  GAME_BRIDGE_INSTANCES?: string;
  GAME_BRIDGE_HOME?: string;
  GAME_BRIDGE_EAGER?: string;
  [key: string]: string | undefined;
}

/**
 * Parse argv (without node/script) plus the environment into a config.
 *
 * Hand-rolled rather than pulled from a dependency: this is four flags, and an
 * MCP server that runs via `npx` pays for every transitive package in startup
 * latency on every single launch.
 */
export function parseCli(argv: string[], env: CliEnv = {}): ParsedCli {
  let port: string | undefined = env.GAME_BRIDGE_PORT;
  // GAME_BRIDGE_SCAN and GAME_BRIDGE_LAUNCH are accepted alongside the longer
  // names because an earlier in-tree implementation of this bridge used them,
  // and existing MCP client entries should keep working after switching to
  // this package.
  let scanRange: string = env.GAME_BRIDGE_SCAN_RANGE ?? env.GAME_BRIDGE_SCAN ?? DEFAULT_SCAN_RANGE;
  let launchHint: string = env.GAME_BRIDGE_LAUNCH_HINT ?? env.GAME_BRIDGE_LAUNCH ?? DEFAULT_LAUNCH_HINT;
  let manifestPath: string | undefined = env.GAME_BRIDGE_MANIFEST;
  let configPath: string | undefined = env.GAME_BRIDGE_CONFIG;
  let registry = defaultRegistryDir(env);
  let useRegistry = true;
  let useScan = true;
  let eager = env.GAME_BRIDGE_EAGER === "1";
  let requestTimeoutMs = 5000;
  let commandTimeoutMs = 5000;

  const need = (flag: string, value: string | undefined): string => {
    if (value === undefined) throw new BridgeUsageError(`${flag} needs a value.`);
    return value;
  };

  for (let i = 0; i < argv.length; i++) {
    const raw = argv[i];
    // Accept both `--port 7802` and `--port=7802`; agents and shells write both.
    const eq = raw.indexOf("=");
    const flag = eq > 1 ? raw.slice(0, eq) : raw;
    const inline = eq > 1 ? raw.slice(eq + 1) : undefined;
    const next = () => (inline !== undefined ? inline : argv[++i]);

    switch (flag) {
      case "-h":
      case "--help":
        return { action: "help", config: buildConfig() };
      case "-v":
      case "--version":
        return { action: "version", config: buildConfig() };
      case "-p":
      case "--port":
        port = need(flag, next());
        break;
      case "--scan":
      case "--scan-range":
        scanRange = need(flag, next());
        break;
      case "--no-scan":
        useScan = false;
        break;
      case "--no-registry":
        useRegistry = false;
        break;
      case "--registry-dir":
        registry = need(flag, next());
        break;
      case "--config":
        configPath = need(flag, next());
        break;
      case "--eager":
        eager = true;
        break;
      case "--launch":
      case "--launch-hint":
        launchHint = need(flag, next());
        break;
      case "--manifest":
        manifestPath = need(flag, next());
        break;
      case "--timeout":
        requestTimeoutMs = Number(need(flag, next()));
        commandTimeoutMs = requestTimeoutMs;
        break;
      default:
        throw new BridgeUsageError(`Unknown option '${raw}'. Run with --help for the option list.`);
    }
  }

  function buildConfig(): BridgeConfig {
    return {
      defaultPort: port === undefined ? DEFAULT_PORT : validPort(port),
      scanPorts: parsePortRange(scanRange),
      launchHint,
      requestTimeoutMs: Number.isFinite(requestTimeoutMs) && requestTimeoutMs > 0 ? requestTimeoutMs : 5000,
      commandTimeoutMs: Number.isFinite(commandTimeoutMs) && commandTimeoutMs > 0 ? commandTimeoutMs : 5000,
      manifestPath,
      registryDir: registry,
      useRegistry,
      useScan,
      eager,
      configPath,
    };
  }

  return { action: "run", config: buildConfig() };
}

export function formatLaunchHint(hint: string, port: number): string {
  return hint.replaceAll("{port}", String(port));
}
