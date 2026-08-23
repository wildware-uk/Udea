package dev.wildware.udea.gradle

/**
 * How to invoke this repository's Gradle wrapper from a shell, on the operating system the
 * declaration is being written on.
 *
 * ## Why this is not the constant `./gradlew`
 *
 * `game-bridge-mcp` executes `launch.command` **through a shell**, and on Windows that shell is
 * `cmd.exe`. Three spellings, three outcomes, all three measured against this repository's own
 * wrapper rather than assumed:
 *
 * | Spelling | `cmd.exe` | POSIX shell |
 * |---|---|---|
 * | `./gradlew` | `'.' is not recognized as an internal or external command` | works |
 * | `gradlew.bat` | works *unless* `NoDefaultCurrentDirectoryInExePath` is set, and then it is "not recognized" | n/a |
 * | `.\gradlew.bat` | works | n/a |
 *
 * The middle row is the trap and it is not hypothetical: the environment this was first launched
 * in sets `NoDefaultCurrentDirectoryInExePath=1`, so `cmd` refuses to look in the working
 * directory, and the bridge reported a boot failure whose "Last output" was
 * `'gradlew.bat' is not recognized`. That is faithful reporting of a fault in the declaration,
 * and it sends the reader looking for a broken game. An explicit relative path resolves against
 * the working directory whatever the search rules are, so [WINDOWS] carries one.
 *
 * ## The consequence for the generated file
 *
 * `gamebridge.json` is therefore **machine-specific**, and one written on the other operating
 * system hands a launcher a command it cannot run. The value is an `@Input` of
 * `udeaGenerateLaunchDeclaration`, so crossing operating systems makes that task out of date and
 * it rewrites; the file is regenerated on `assemble` and on `run`, so the correct spelling comes
 * back with the first build. It is still worth keeping out of version control.
 */
public object GradleWrapperCommand {

    /** The POSIX spelling: relative, because `gradlew` is not on anybody's `PATH`. */
    public const val POSIX: String = "./gradlew"

    /**
     * The `cmd.exe` spelling.
     *
     * A backslash, and explicitly relative. `./gradlew.bat` is *not* a substitute - `cmd` rejects
     * the forward slash - and a bare `gradlew.bat` is not one either, because
     * `NoDefaultCurrentDirectoryInExePath` removes the working directory from the search path.
     */
    public const val WINDOWS: String = ".\\gradlew.bat"

    /**
     * [WINDOWS] when [osName] names a Windows, [POSIX] otherwise.
     *
     * @param osName the `os.name` system property. A parameter so the decision is testable on
     *   either platform rather than only on the one CI happens to run.
     */
    public fun forOs(osName: String): String =
        if (osName.lowercase().startsWith("windows")) WINDOWS else POSIX

    /** [forOs] for the JVM running the build. */
    public fun current(): String = forOs(System.getProperty("os.name") ?: "")
}
