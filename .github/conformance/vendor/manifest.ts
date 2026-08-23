/**
 * The tool manifest: what a game says it can be told to do.
 *
 * The point of the manifest is that this bridge contains no knowledge of any
 * particular game. The previous generation of this tool hardcoded one game's
 * command list, which meant every new debug command needed a matching edit here
 * and, in between, the list on the agent's side was quietly wrong. Here the game
 * is the source of truth and the bridge is a pipe.
 */

export type JsonType = "string" | "number" | "integer" | "boolean" | "object" | "array";

export interface ArgDef {
  name: string;
  type?: JsonType;
  description?: string;
  required?: boolean;
  enum?: unknown[];
  default?: unknown;
  /** Element schema for `type: "array"` arguments. */
  items?: Record<string, unknown>;
}

export interface JsonSchema {
  type: "object";
  properties?: Record<string, unknown>;
  required?: string[];
  [k: string]: unknown;
}

export interface ToolDef {
  name: string;
  description?: string;
  /** Debug command to send. Defaults to the tool name, which is the common case. */
  command?: string;
  /** Wait for the game to report the command applied. Default true. */
  sync?: boolean;
  arguments?: ArgDef[];
  /** A game that already has JSON Schema for its commands can send it verbatim instead. */
  inputSchema?: JsonSchema;
  /** Set by the bridge on its own tools so `describe_toolset` can mark them. */
  builtin?: boolean;
}

export interface ToolsetDef {
  name: string;
  description?: string;
  tools: ToolDef[];
}

export interface PassthroughDef {
  description: string;
  examples?: string[];
}

export interface Manifest {
  game: { name: string; version?: string; protocol?: number };
  toolsets: ToolsetDef[];
  passthrough: PassthroughDef;
  /** Where this manifest came from, so a caller can tell a real one from a stand-in. */
  source: "game" | "file" | "fallback";
}

export const DEFAULT_PASSTHROUGH: PassthroughDef = {
  description:
    "Any command the running game's debug bridge accepts, passed straight through as query " +
    "parameters. Use this for commands newer than the game's published manifest - a command " +
    "added to the game today is drivable today, without changing or restarting this bridge.",
};

/**
 * The manifest document shape this bridge understands.
 *
 * It versions the *document*, not the command set: a game adding a command is
 * routine and changes nothing here, whereas a game restructuring the manifest
 * needs to be distinguishable from one this bridge simply cannot read.
 */
export const SUPPORTED_PROTOCOL = 1;

/**
 * The manifest used when a game does not serve GET /tools.
 *
 * It advertises no game-specific commands, because the bridge has no business
 * guessing them - what it offers is the contract-level toolset plus the
 * passthrough, which together are enough to drive any conforming game by hand.
 * A project with an older game that cannot be changed can supply a real
 * manifest from a file with --manifest instead of living on this.
 */
export function fallbackManifest(port: number): Manifest {
  return {
    game: { name: `game on port ${port}`, version: "unknown" },
    toolsets: [],
    passthrough: {
      ...DEFAULT_PASSTHROUGH,
      description:
        DEFAULT_PASSTHROUGH.description +
        "\n\nThis game does not serve GET /tools, so it publishes no command list. Everything " +
        "must go through raw_command; read GET /state to work out what the game is doing.",
    },
    source: "fallback",
  };
}

function asArgDefs(value: unknown): ArgDef[] | undefined {
  if (Array.isArray(value)) {
    return value
      .map((a): ArgDef | null => {
        if (typeof a === "string") return { name: a };
        if (a && typeof a === "object" && typeof (a as ArgDef).name === "string") return a as ArgDef;
        return null;
      })
      .filter((a): a is ArgDef => a !== null);
  }
  // Also accept { x: {type, description}, ... }, which is how a game whose
  // commands are declared as a map will most naturally serialise them.
  if (value && typeof value === "object") {
    return Object.entries(value as Record<string, Partial<ArgDef>>).map(([name, def]) => ({
      name,
      ...(def ?? {}),
    }));
  }
  return undefined;
}

function asToolDef(value: unknown): ToolDef | null {
  if (typeof value === "string") return { name: value };
  if (!value || typeof value !== "object") return null;
  const raw = value as Record<string, unknown>;
  if (typeof raw.name !== "string" || !raw.name) return null;
  const tool: ToolDef = { name: raw.name };
  if (typeof raw.description === "string") tool.description = raw.description;
  if (typeof raw.command === "string") tool.command = raw.command;
  if (typeof raw.sync === "boolean") tool.sync = raw.sync;
  const args = asArgDefs(raw.arguments ?? raw.args ?? raw.params);
  if (args) tool.arguments = args;
  if (raw.inputSchema && typeof raw.inputSchema === "object") tool.inputSchema = raw.inputSchema as JsonSchema;
  return tool;
}

/**
 * Turn whatever the game sent into the shape the bridge works with.
 *
 * Tolerant by design. A game's manifest is written by a gameplay programmer in
 * whatever serialiser was already to hand, and a bridge that rejects it over
 * an array-versus-map disagreement is a bridge people stop using. Anything
 * unrecognisable is dropped rather than thrown, so one malformed tool cannot
 * take the whole instance offline.
 */
export function normaliseManifest(raw: unknown, port: number, source: Manifest["source"] = "game"): Manifest {
  const doc = (raw && typeof raw === "object" ? raw : {}) as Record<string, unknown>;
  const gameRaw = (doc.game ?? doc.info ?? {}) as Record<string, unknown>;

  const toolsetsRaw = doc.toolsets ?? doc.groups ?? [];
  let entries: Array<[string, unknown]>;
  if (Array.isArray(toolsetsRaw)) {
    entries = toolsetsRaw.map((t) => {
      const name = t && typeof t === "object" ? String((t as Record<string, unknown>).name ?? "") : String(t);
      return [name, t] as [string, unknown];
    });
  } else if (toolsetsRaw && typeof toolsetsRaw === "object") {
    entries = Object.entries(toolsetsRaw as Record<string, unknown>);
  } else {
    entries = [];
  }

  const toolsets: ToolsetDef[] = [];
  for (const [name, value] of entries) {
    if (!name) continue;
    const set = (value && typeof value === "object" ? value : {}) as Record<string, unknown>;
    const toolsRaw = Array.isArray(set.tools) ? set.tools : [];
    const tools = toolsRaw.map(asToolDef).filter((t): t is ToolDef => t !== null);
    toolsets.push({
      name,
      description: typeof set.description === "string" ? set.description : undefined,
      tools,
    });
  }

  const passRaw = (doc.passthrough ?? {}) as Record<string, unknown>;
  const passthrough: PassthroughDef = {
    description:
      typeof passRaw.description === "string" ? passRaw.description : DEFAULT_PASSTHROUGH.description,
    examples: Array.isArray(passRaw.examples) ? passRaw.examples.map(String) : undefined,
  };

  return {
    game: {
      name: typeof gameRaw.name === "string" && gameRaw.name ? gameRaw.name : `game on port ${port}`,
      version: typeof gameRaw.version === "string" ? gameRaw.version : undefined,
      protocol: typeof gameRaw.protocol === "number" ? gameRaw.protocol : undefined,
    },
    toolsets,
    passthrough,
    source,
  };
}

/** Everything callable on this instance, tool name -> definition. */
export function toolIndex(manifest: Manifest): Map<string, ToolDef> {
  const index = new Map<string, ToolDef>();
  for (const set of manifest.toolsets) {
    for (const tool of set.tools) if (!index.has(tool.name)) index.set(tool.name, tool);
  }
  return index;
}

/**
 * Expand a tool definition into the JSON Schema an MCP client expects.
 *
 * Defaults are folded into the description rather than emitted as `default`.
 * A manifest serialised from a typed language tends to carry every default as
 * a string - `"false"`, `"0.05"` - and a `default: "false"` on a property typed
 * `boolean` is a schema a strict client is entitled to reject. The text is what
 * the model reads anyway.
 */
export function toInputSchema(tool: ToolDef): JsonSchema {
  if (tool.inputSchema) return tool.inputSchema;
  const properties: Record<string, unknown> = {};
  const required: string[] = [];
  for (const arg of tool.arguments ?? []) {
    const prop: Record<string, unknown> = {};
    if (arg.type) prop.type = arg.type;
    const hasDefault = arg.default !== undefined && arg.default !== null;
    const suffix = hasDefault ? ` (default ${String(arg.default)})` : "";
    if (arg.description || suffix) prop.description = `${arg.description ?? ""}${suffix}`.trim();
    if (arg.enum) prop.enum = arg.enum;
    if (arg.type === "array") prop.items = arg.items ?? {};
    properties[arg.name] = prop;
    if (arg.required) required.push(arg.name);
  }
  const schema: JsonSchema = { type: "object", properties };
  if (required.length) schema.required = required;
  return schema;
}

/**
 * Build the synthetic `bridge` toolset for one instance.
 *
 * Filtered by game name, because a composite that chains game-specific commands
 * is nonsense on a game that does not have them - offering `stack_crates` to a
 * flight simulator is exactly the kind of drift that makes an agent distrust
 * the whole tool list.
 */
export function bridgeToolset(tools: ToolDef[]): ToolsetDef {
  return {
    name: "bridge",
    description:
      "Added by the bridge, not the game: tools that read the contract endpoints directly, " +
      "chain several commands, or wait for something GET /command cannot report.",
    tools,
  };
}
