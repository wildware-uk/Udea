package dev.wildware.udea.compiler.testing

/**
 * One Kotlin source file handed to the compile-testing harness.
 *
 * @param name the file name, which is also the tail of the repo-relative [dev.wildware.udea.diagnostics.SourceSpan]
 *   a diagnostic on it will carry.
 * @param text the whole file, including any `// expect:` markers (see [InlineExpectations]).
 */
data class TestSource(val name: String, val text: String)

/** Builds a [TestSource] with [name], trimming the leading indentation of a raw string. */
fun source(name: String, text: String): TestSource = TestSource(name, text.trimIndent())
