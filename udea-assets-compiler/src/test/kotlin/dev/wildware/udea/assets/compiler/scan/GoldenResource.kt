package dev.wildware.udea.assets.compiler.scan

/**
 * A committed golden, read as the bytes it was committed as.
 *
 * `golden/example-declarations.json` is compared byte for byte against what
 * [DeclarationsJson.write] emits, and `two checkouts produce byte-identical json` is a
 * determinism assertion whose entire subject is those bytes. So the golden is one of the files
 * in this repository that a checkout must not translate, and `udea-assets-compiler/.gitattributes`
 * marks it `-text` to say so.
 *
 * This is the other half of that, and issue #176 is why it is not redundant. An attribute
 * governs a checkout; it does nothing about a copy that arrived some other way - restored from
 * an archive, unzipped on Windows, written by an editor set to CRLF, or checked out before the
 * attribute existed. Without a fence such a copy fails on the byte comparison alone, and the
 * failure is worse than useless: `assertEquals` renders `\r` as nothing, so both halves of the
 * diff print identically and the reader is left staring at two blocks of text that look the
 * same. That is exactly how #176 was missed until somebody counted the bytes in the CI
 * artefact.
 *
 * The shape is the one issue #171 shipped for the vendored bridge sources: the attribute stops
 * the translation, and the code that reads the file refuses a translated copy by name.
 */
internal object GoldenResource {

    /** The pass-1 golden, as a classpath resource path. */
    const val EXAMPLE_DECLARATIONS: String = "/golden/example-declarations.json"

    /** [resource]'s bytes, exactly as they sit on the test runtime classpath. */
    fun bytes(resource: String): ByteArray =
        checkNotNull(GoldenResource::class.java.getResourceAsStream(resource)) {
            "$resource is missing from test resources"
        }.use { it.readBytes() }

    /** [resource] as UTF-8 text, refusing a copy a checkout or an editor has translated. */
    fun read(resource: String): String = untranslated(resource, bytes(resource))

    /**
     * [bytes] as UTF-8 text, or a failure naming the translation and how to undo it.
     *
     * @throws IllegalStateException if a carriage return is present. It never is in a golden
     *   this repository emits: [DeclarationsJson] writes `\n` and nothing else, so a `\r` here
     *   did not come from the producer and cannot be reconciled by comparing anyway.
     */
    fun untranslated(resource: String, bytes: ByteArray): String {
        val text = bytes.toString(Charsets.UTF_8)
        val carriageReturns = text.count { it == '\r' }
        check(carriageReturns == 0) {
            "$resource reached the test with $carriageReturns carriage return(s) in it. It is " +
                "committed with LF, and nothing in this repository writes CRLF into it, so the " +
                "copy on the classpath was translated on the way here - a checkout with " +
                "core.autocrlf=true (Git for Windows' default) is the usual cause, and " +
                "udea-assets-compiler/.gitattributes marks the goldens `-text` to prevent it. " +
                "Refresh the working tree (git rm --cached -r . && git reset --hard) or fix the " +
                "editor that saved it. Reported here rather than by the byte comparison because " +
                "a carriage return renders as nothing: the diff would have shown two blocks of " +
                "text that look identical (issue #176)."
        }
        return text
    }
}
