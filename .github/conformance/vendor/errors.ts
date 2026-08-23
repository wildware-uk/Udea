/**
 * The bridge's failure modes, as distinct types.
 *
 * These exist because "it didn't work" is a useless thing to tell an agent that
 * is driving five game instances at once. Nothing on port 7803 and something on
 * port 7803 that isn't one of our games are completely different problems with
 * completely different fixes, and the caller can only pick the right one if the
 * bridge says which happened.
 */

/** Nothing is listening, or it accepted the connection and then never answered. */
export class GameOffline extends Error {
  readonly port: number;

  constructor(port: number, launchHint: string, cause?: unknown) {
    const detail = cause instanceof Error ? ` (${cause.message})` : "";
    super(
      `No game is answering on http://127.0.0.1:${port}${detail}.\n` +
        `Start one with:\n  ${launchHint}`
    );
    this.name = "GameOffline";
    this.port = port;
  }
}

/**
 * Something is listening and talking HTTP, but it is not a game implementing
 * the debug contract.
 *
 * Worth its own type: the ports a developer scans are ordinary high ports, and
 * a dev server, a language server or a previous project's API landing on 7802
 * is a routine accident. Reporting that as "game offline" sends people looking
 * for a game that is in fact running somewhere else.
 */
export class NotAGameSurface extends Error {
  readonly port: number;

  constructor(port: number, detail: string) {
    super(
      `Something is listening on http://127.0.0.1:${port}, but it is not a debuggable game: ${detail}.\n` +
        `A drivable game must answer GET /health with {"ok":true,"frame":N}. ` +
        `Check whether another process has taken this port.`
    );
    this.name = "NotAGameSurface";
    this.port = port;
  }
}

/** The game answered, but with an HTTP status we cannot use. */
export class HttpStatusError extends Error {
  readonly status: number;
  readonly path: string;

  constructor(port: number, path: string, status: number, body?: string) {
    const snippet = body ? `: ${body.slice(0, 200)}` : "";
    super(`GET ${path} on port ${port} -> HTTP ${status}${snippet}`);
    this.name = "HttpStatusError";
    this.status = status;
    this.path = path;
  }
}

/** A command was queued but the game never reported it as applied. */
export class CommandTimeout extends Error {
  constructor(command: string, port: number, ms: number) {
    super(
      `Command '${command}' was queued on port ${port} but was not applied within ${ms}ms. ` +
        `The game accepted it, so it is probably blocked, frozen, or on a screen that ignores this command.`
    );
    this.name = "CommandTimeout";
  }
}

/** Bad input from the caller: an unknown toolset, an out-of-range port. */
export class BridgeUsageError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "BridgeUsageError";
  }
}
