import { CommandTimeout, GameOffline, HttpStatusError, NotAGameSurface } from "./errors.js";
import { formatLaunchHint } from "./config.js";

/**
 * The subset of `Response` the bridge actually uses.
 *
 * Narrowed on purpose: the test suite must be able to stand in for a game
 * without a running game and without a fake HTTP server, and constructing real
 * `Response` objects for that is ceremony with no payoff.
 */
export interface ResponseLike {
  ok: boolean;
  status: number;
  text(): Promise<string>;
}

export type FetchLike = (url: string, init?: { signal?: AbortSignal }) => Promise<ResponseLike>;

export interface ClientOptions {
  fetchImpl?: FetchLike;
  timeoutMs?: number;
  launchHint?: string;
  sleep?: (ms: number) => Promise<void>;
}

export interface CommandAck {
  accepted?: boolean;
  commandId?: number;
  frame?: number;
  [key: string]: unknown;
}

export interface GameState {
  frame?: number;
  simFrame?: number;
  completedCommandId?: number;
  paused?: boolean;
  ui?: { screen?: string; elements?: Array<{ label?: string; visible?: boolean }> };
  events?: Array<{ m?: string } | string>;
  [key: string]: unknown;
}

export interface SyncResult {
  state: GameState;
  commandId?: number;
  /** False when the game does not report completedCommandId and we fell back to watching `frame`. */
  confirmed: boolean;
}

const defaultSleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/**
 * One game instance, addressed by port.
 *
 * Deliberately stateless apart from its port and options: the registry keeps one
 * of these per port and they must not accumulate anything that would survive a
 * game restart on the same port.
 */
export class GameClient {
  readonly port: number;
  readonly baseUrl: string;

  private readonly fetchImpl: FetchLike;
  private readonly timeoutMs: number;
  private readonly launchHint: string;
  private readonly sleep: (ms: number) => Promise<void>;

  constructor(port: number, opts: ClientOptions = {}) {
    this.port = port;
    this.baseUrl = `http://127.0.0.1:${port}`;
    this.fetchImpl = opts.fetchImpl ?? ((url, init) => fetch(url, init) as unknown as Promise<ResponseLike>);
    this.timeoutMs = opts.timeoutMs ?? 5000;
    this.launchHint = opts.launchHint ?? "";
    this.sleep = opts.sleep ?? defaultSleep;
  }

  private hint(): string {
    return formatLaunchHint(this.launchHint, this.port);
  }

  /** Raw GET returning the response, with connection failures mapped to GameOffline. */
  async get(path: string, timeoutMs?: number): Promise<ResponseLike> {
    try {
      return await this.fetchImpl(this.baseUrl + path, {
        signal: AbortSignal.timeout(timeoutMs ?? this.timeoutMs),
      });
    } catch (e) {
      throw new GameOffline(this.port, this.hint(), e);
    }
  }

  /**
   * GET a path and parse it as JSON.
   *
   * A body that is not JSON is reported as "not a game", not as a parse error:
   * in practice that means some other HTTP service has taken the port, and the
   * JSON exception on its own would have sent the caller debugging the game.
   */
  async getJson<T = unknown>(path: string, timeoutMs?: number): Promise<T> {
    const res = await this.get(path, timeoutMs);
    const body = await res.text().catch(() => "");
    if (!res.ok) throw new HttpStatusError(this.port, path, res.status, body);
    try {
      return JSON.parse(body) as T;
    } catch {
      throw new NotAGameSurface(
        this.port,
        `GET ${path} returned ${body.length} bytes that are not JSON`
      );
    }
  }

  /**
   * `timeoutMs` overrides the client default, which discovery needs: a scan
   * touches ports that may hold anything, and one unrelated service that
   * accepts a connection and never answers must not stall the whole sweep.
   */
  async health(timeoutMs?: number): Promise<{ ok?: boolean; frame?: number; [k: string]: unknown }> {
    let raw: { ok?: boolean; frame?: number };
    try {
      raw = await this.getJson<{ ok?: boolean; frame?: number }>("/health", timeoutMs);
    } catch (e) {
      // A 404 on /health means an HTTP server that is not ours. Anything else -
      // refused connection, timeout - is a port with no game on it.
      if (e instanceof HttpStatusError) {
        throw new NotAGameSurface(this.port, `GET /health returned HTTP ${e.status}`);
      }
      throw e;
    }
    if (typeof raw !== "object" || raw === null || raw.ok !== true) {
      throw new NotAGameSurface(this.port, `GET /health did not return {"ok":true,...}`);
    }
    return raw;
  }

  state(): Promise<GameState> {
    return this.getJson<GameState>("/state");
  }

  /** Queue a command. Returns as soon as the game has accepted it, not when it has run. */
  async command(name: string, args: Record<string, unknown> = {}): Promise<CommandAck> {
    // Keyed 'cmd' rather than 'name' because several commands take a 'name'
    // argument of their own, and a duplicate query key silently overwrites the
    // command being invoked.
    const qs = new URLSearchParams({ cmd: name });
    for (const [k, v] of Object.entries(args)) {
      if (v === undefined || v === null) continue;
      qs.set(k, typeof v === "object" ? JSON.stringify(v) : String(v));
    }
    return this.getJson<CommandAck>(`/command?${qs.toString()}`);
  }

  /**
   * Queue a command and wait until the game has actually applied it.
   *
   * `/command` is fire-and-forget: it answers from the HTTP thread the moment
   * the command is queued, while the command itself runs later on the render
   * thread. Reading `/state` straight after a command therefore reads the world
   * *before* it happened, which is the single most confusing failure this
   * bridge can produce - the test looks flaky, the game is fine.
   *
   * The reliable signal is `completedCommandId`. Games that do not publish it
   * get the weaker guarantee of "two frames have gone by", which at least means
   * the render thread has drained its queue once.
   */
  async commandAndSync(
    name: string,
    args: Record<string, unknown> = {},
    timeoutMs = 5000
  ): Promise<SyncResult> {
    const before = await this.state().catch(() => ({} as GameState));
    const ack = await this.command(name, args);
    const deadline = Date.now() + timeoutMs;
    const startFrame = typeof before.frame === "number" ? before.frame : -1;

    while (Date.now() < deadline) {
      const s = await this.state();
      if (typeof s.completedCommandId === "number" && typeof ack.commandId === "number") {
        if (s.completedCommandId >= ack.commandId) {
          return { state: s, commandId: ack.commandId, confirmed: true };
        }
      } else if (typeof s.frame === "number" && startFrame >= 0 && s.frame >= startFrame + 2) {
        return { state: s, commandId: ack.commandId, confirmed: false };
      }
      await this.sleep(16);
    }
    throw new CommandTimeout(name, this.port, timeoutMs);
  }

  /**
   * Wait for the port to stop answering.
   *
   * Used by `close`, where the usual confirmation is impossible: the game is on
   * its way out, so nothing will ever report the command as completed. The port
   * going quiet *is* the success signal.
   */
  async waitForSilence(timeoutMs = 10000): Promise<boolean> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      await this.sleep(200);
      try {
        await this.state();
      } catch (e) {
        if (e instanceof GameOffline) return true;
        // A game part-way through shutdown can answer with an error before the
        // socket closes; that is still on its way out, so keep waiting.
      }
    }
    return false;
  }
}
