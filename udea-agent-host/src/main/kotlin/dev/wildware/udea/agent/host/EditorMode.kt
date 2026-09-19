package dev.wildware.udea.agent.host

/**
 * Whether this instance was started as a level editor (issue #193).
 *
 * An editor instance registers the `editor.*` toolset, whose tools write fields
 * `world.set_component_field` refuses; a normal run does not register it. `/health` reports which
 * this is, through [AgentHostConfig.editor], so an agent knows `editor.*` is live before calling
 * one.
 *
 * Until the editor window (#194) exists, the switch is a JVM property, `-Dudea.editor=true`, which
 * `:moba:desktop:run` forwards from `-Peditor=true`.
 */
public object EditorMode {

    /** `-Dudea.editor=true` starts an editor. Absent, or `false`, is a normal run. */
    public const val PROPERTY: String = "udea.editor"

    /**
     * Reads [PROPERTY].
     *
     * @param properties system-property lookup, injected so a test can drive it without mutating
     *   the JVM - the same reason [SessionIdentity.resolve] takes one.
     * @throws IllegalArgumentException for anything but `true`, `false` or absent. Loud, because
     *   `-Dudea.editor=yes` silently starting a normal run would leave an agent calling tools that
     *   are not there, with nothing saying why.
     */
    public fun resolve(properties: (String) -> String? = System::getProperty): Boolean =
        when (val value = properties(PROPERTY)?.trim()) {
            null, "", "false" -> false
            "true" -> true
            else -> throw IllegalArgumentException("-D$PROPERTY='$value' is not true or false")
        }
}
