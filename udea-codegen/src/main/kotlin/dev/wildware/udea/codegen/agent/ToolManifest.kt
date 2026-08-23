package dev.wildware.udea.codegen.agent

/**
 * The two agent-facing documents, built from one [ToolModel] so they cannot disagree.
 *
 * - [schemaOf] is the tool's JSON Schema, carried verbatim as `tools[].inputSchema`;
 * - [render] is the module's manifest fragment, which the agent host merges with every other
 *   module's into the single `GET /tools` document the bridge fetches.
 *
 * ## The parser this has to satisfy
 *
 * `game-bridge-mcp`'s `normaliseManifest` is deliberately tolerant, and tolerant means
 * **silent**: a tool whose `name` is missing or not a string is *dropped*, not reported, so a
 * malformed entry does not take an instance offline - it just makes a capability invisible
 * with nothing anywhere saying why. Every rule that parser applies is therefore a rule this
 * writer obeys by construction rather than by hope:
 *
 * - `toolsets[].name` is a non-empty string, or the toolset is skipped entirely;
 * - `toolsets[].tools` is an array, or it is read as no tools at all;
 * - `tools[].name` is a non-empty string, or that tool is dropped;
 * - `tools[].inputSchema` is an **object**, not the string the generated Kotlin holds;
 * - `args[]` entries are objects with a string `name`, under the `args` key the parser
 *   accepts alongside `arguments` and `params`.
 *
 * `ToolManifestBridgeParserTest` re-implements those rules over the real generated file, so
 * the claim is checked rather than asserted.
 */
internal object ToolManifest {

    /**
     * The manifest document version, not the command set's.
     *
     * The bridge reads it to tell "I cannot read this" apart from "this game knows different
     * commands than last time". Adding a tool must not move it; restructuring this document
     * must.
     */
    const val PROTOCOL: Int = 1

    /** The JSON Schema dialect the emitted `inputSchema` documents declare. */
    const val SCHEMA_DIALECT: String = "https://json-schema.org/draft/2020-12/schema"

    /**
     * One tool's JSON Schema.
     *
     * `additionalProperties: false` on purpose: an argument the tool does not accept is a
     * misunderstanding worth telling the model about, and the alternative is a call that
     * appears to succeed while silently ignoring half of what was asked for.
     *
     * A default is folded into the property's `description` rather than emitted as `default`,
     * which is what the bridge does when it builds a schema itself - the text is what the
     * model reads anyway, and a `default` on a strictly-typed property is something a strict
     * client is entitled to reject.
     */
    fun schemaOf(tool: ToolModel): JsonText.Obj {
        val properties = tool.args.map { arg -> arg.name to propertyOf(arg) }
        val required = tool.args.filter(ToolArgModel::required).map { JsonText.Text(it.name) }
        val members = mutableListOf<Pair<String, JsonText.Value>>(
            "\$schema" to JsonText.Text(SCHEMA_DIALECT),
            "type" to JsonText.Text("object"),
            "properties" to JsonText.Obj(properties),
        )
        if (required.isNotEmpty()) members += "required" to JsonText.Arr(required)
        members += "additionalProperties" to JsonText.Literal("false")
        return JsonText.Obj(members)
    }

    private fun propertyOf(arg: ToolArgModel): JsonText.Obj {
        val members = mutableListOf<Pair<String, JsonText.Value>>(
            "type" to JsonText.Text(arg.jsonType),
            "description" to JsonText.Text(describedWithDefault(arg)),
        )
        // `enum` constrains the whole value, so it belongs to a scalar argument alone: a list
        // arrives as one comma-separated string, which is never itself one of the constants.
        // A list of enums publishes its constants in the description instead, beside the
        // separator, which is the same place and for the same reason.
        if (!arg.list && arg.enumConstants.isNotEmpty()) {
            members += "enum" to JsonText.Arr(arg.enumConstants.map(JsonText::Text))
        }
        return JsonText.Obj(members)
    }

    /**
     * A list argument travels as one query parameter, so the separator has to be in the text
     * the model reads - there is nowhere else in JSON Schema to say it. That is also why the
     * property is typed `string` and not `array`: see [ToolArgModel.jsonType].
     */
    private fun describedWithDefault(arg: ToolArgModel): String = buildString {
        append(arg.description)
        if (arg.list) {
            append(" Several values, comma separated")
            if (arg.enumConstants.isNotEmpty()) {
                append(", each one of ").append(arg.enumConstants.joinToString(", "))
            }
            append('.')
        }
        when {
            arg.defaultText != null -> append(" (default ").append(arg.defaultText).append(')')
            !arg.required -> append(" (optional; omit for none)")
        }
    }

    /**
     * The module's fragment: its toolsets, in toolset then tool name order.
     *
     * A fragment and not the whole `GET /tools` document, because no single KSP round sees
     * every module - merging them is the agent host's job, and it is the reason each module
     * publishes a `ToolModule` through `ServiceLoader` rather than writing into a shared file.
     */
    fun render(moduleName: String, tools: List<ToolModel>): String {
        val toolsets = tools
            .groupBy(ToolModel::toolset)
            .toSortedMap()
            .map { (toolset, members) ->
                JsonText.Obj(
                    listOf(
                        "name" to JsonText.Text(toolset),
                        "tools" to JsonText.Arr(
                            members.sortedBy(ToolModel::name).map(::toolOf),
                        ),
                    ),
                )
            }
        return JsonText.render(
            JsonText.Obj(
                listOf(
                    "module" to JsonText.Text(moduleName),
                    "protocol" to JsonText.Literal(PROTOCOL.toString()),
                    "toolsets" to JsonText.Arr(toolsets),
                ),
            ),
        )
    }

    private fun toolOf(tool: ToolModel): JsonText.Obj = JsonText.Obj(
        listOf(
            "name" to JsonText.Text(tool.name),
            "description" to JsonText.Text(tool.description),
            "args" to JsonText.Arr(tool.args.map(::argOf)),
            "inputSchema" to schemaOf(tool),
        ),
    )

    /** `{name, type, description, required, default}` - the bridge's `ArgDef`, exactly. */
    private fun argOf(arg: ToolArgModel): JsonText.Obj {
        val members = mutableListOf<Pair<String, JsonText.Value>>(
            "name" to JsonText.Text(arg.name),
            "type" to JsonText.Text(arg.jsonType),
            "description" to JsonText.Text(arg.description),
            "required" to JsonText.Literal(arg.required.toString()),
            // `null` means "no default", which is what the bridge's ArgDef documents. Absent
            // and null are the same to that parser; null is written so the shape of every
            // entry is identical and a diff over the golden reads as one line per field.
            "default" to (arg.defaultText?.let(JsonText::Text) ?: JsonText.Literal("null")),
        )
        // As in the schema: `enum` describes one whole value, and a list is one string holding
        // several. There is no `items`, because there is no `array` — the query string a tool
        // call arrives as has no array to put items in.
        if (!arg.list && arg.enumConstants.isNotEmpty()) {
            members += "enum" to JsonText.Arr(arg.enumConstants.map(JsonText::Text))
        }
        return JsonText.Obj(members)
    }

    /** `udea/Moba-agent-tools.json`, beside the protocol lock and read the same way. */
    fun resourcePath(moduleName: String): String = "udea/$moduleName-agent-tools.json"
}
