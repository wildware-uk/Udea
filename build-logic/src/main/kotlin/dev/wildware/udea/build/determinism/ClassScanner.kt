package dev.wildware.udea.build.determinism

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File

/**
 * One reference a compiled class makes to a member or a type of another class.
 *
 * Everything the determinism rules need to fire and to be *reported* well: the class and
 * method that made the reference, the line it was made on, the source file the class was
 * compiled from, and what it referenced. A rule that could not say the line would be a rule
 * whose failure a reader has to go hunting for, which is the failure mode section 8 of the
 * engineering standards rejects.
 *
 * [line] is `0` when the class was compiled without debug info. It never invents a number:
 * a fabricated line is worse than an absent one.
 */
public data class MemberRef(
    /** Dotted FQN of the class that *makes* the reference. */
    public val className: String,
    /** `SourceFile` attribute of the referencing class, e.g. `SoundSystem.kt`, or null. */
    public val sourceFile: String?,
    /** Name of the enclosing method, or `<clinit>`/`<init>`. */
    public val method: String,
    /** Line the reference was compiled from, or `0` when unknown. */
    public val line: Int,
    /** Dotted FQN of the owner of the referenced member, or of the referenced type. */
    public val owner: String,
    /** Member name; `<type>` for a bare type reference (`NEW`, `CHECKCAST`, a class literal). */
    public val member: String,
    /** JVM descriptor of the referenced member, or the empty string for a type reference. */
    public val descriptor: String,
    /** How the reference was made. */
    public val kind: RefKind,
) {
    /** `owner.member` for a member reference, `owner` for a bare type reference. */
    public val target: String get() = if (kind == RefKind.TYPE) owner else "$owner.$member"
}

/**
 * A class's name and its direct supertypes, in internal form (`dev/wildware/udea/editor/Foo`).
 *
 * @property superName `null` only for `java/lang/Object` and `module-info`.
 */
public data class ClassHeader(
    public val name: String,
    public val superName: String?,
    public val interfaces: List<String>,
)

/** How a [MemberRef] was made — a rule may care (a `NEW HashMap` is not a `HashMap.entrySet`). */
public enum class RefKind { METHOD, FIELD, TYPE }

/**
 * The **one** ASM walk in this repository's build logic.
 *
 * Issue #150 asks for this to be shared rather than duplicated, and the reason is concrete:
 * `udea-render`'s `udeaVerifyHeadless` grew its own `ClassRefScanner` in test sources, and a
 * second visitor is a second set of blind spots that nobody diffs against the first. New
 * bytecode gates consume this; they do not write a third.
 *
 * It is a **reference** scanner, not a dataflow analysis. It sees "this method named
 * `System.nanoTime`". It cannot see "this method called a helper that called `System.nanoTime`",
 * and it cannot see the runtime type behind an interface call. `determinism-audit.md` states
 * that limit in full; do not read a green scan as a determinism proof.
 */
public object ClassScanner {

    private const val API = Opcodes.ASM9

    /** Every reference made by the class in [classFile]. */
    public fun scan(classFile: File): List<MemberRef> =
        scan(classFile.readBytes())

    /** Every reference made by the class in [bytes]. */
    public fun scan(bytes: ByteArray): List<MemberRef> {
        val refs = ArrayList<MemberRef>()
        ClassReader(bytes).accept(CollectingClassVisitor(refs), ClassReader.SKIP_FRAMES)
        return refs
    }

    /**
     * What [bytes]' class is and what it directly extends and implements, read from the class
     * header alone - no method is visited.
     *
     * For a gate about *what a class is* rather than what it calls: `UDEA-MG-012` (issue #233) asks
     * whether a class is a `Gizmo`, which is its supertypes, and a string constant that merely spells
     * a gizmo's name is not an answer to that.
     */
    public fun header(bytes: ByteArray): ClassHeader {
        val reader = ClassReader(withReadableVersion(bytes))
        return ClassHeader(reader.className, reader.superName, reader.interfaces.toList())
    }

    /**
     * [bytes], or a copy stamped with a class-file version this ASM reads, when the class is newer.
     *
     * A release classpath carries classes newer than any ASM this build has: LWJGL 3.4.3 ships
     * `META-INF/versions/27/` classes for Java 27's FFM API. ASM refuses a newer major version because
     * it cannot promise to read the *methods* - new attributes, new instructions - and [header] reads
     * no method: only the constant pool, `this_class`, `super_class` and `interfaces`. A constant-pool
     * entry this ASM does not know still throws, so a header it cannot read fails the caller rather
     * than being guessed at. Skipping those classes instead would leave a hole a gizmo could sit in.
     */
    private fun withReadableVersion(bytes: ByteArray): ByteArray {
        if (bytes.size < HEADER_END) return bytes
        val major = ((bytes[MAJOR_OFFSET].toInt() and BYTE) shl Byte.SIZE_BITS) or (bytes[MAJOR_OFFSET + 1].toInt() and BYTE)
        if (major <= NEWEST_READABLE_MAJOR) return bytes
        return bytes.copyOf().also {
            it[MAJOR_OFFSET] = (NEWEST_READABLE_MAJOR shr Byte.SIZE_BITS).toByte()
            it[MAJOR_OFFSET + 1] = NEWEST_READABLE_MAJOR.toByte()
        }
    }

    /** Where a class file's major version sits: after the 4-byte magic and the 2-byte minor. */
    private const val MAJOR_OFFSET = 6
    private const val HEADER_END = MAJOR_OFFSET + 2
    private const val BYTE = 0xFF

    /** Java 21's class-file major version, which the catalog's ASM reads. */
    private const val NEWEST_READABLE_MAJOR = Opcodes.V21

    /** Every reference made by every `.class` file under [roots]. */
    public fun scanAll(roots: Iterable<File>): List<MemberRef> =
        classFilesUnder(roots).flatMap { scan(it) }

    /** Every `.class` file under [roots], sorted so a report is stable across machines. */
    public fun classFilesUnder(roots: Iterable<File>): List<File> = roots
        .filter { it.exists() }
        .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "class" } }
        .sortedBy { it.invariantSeparatorsPath }

    private class CollectingClassVisitor(private val sink: MutableList<MemberRef>) :
        ClassVisitor(API) {

        private var className: String = ""
        private var sourceFile: String? = null

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
        ) {
            className = name.replace('/', '.')
        }

        override fun visitSource(source: String?, debug: String?) {
            sourceFile = source
        }

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor = CollectingMethodVisitor(sink, className, sourceFile, name)
    }

    private class CollectingMethodVisitor(
        private val sink: MutableList<MemberRef>,
        private val className: String,
        private val sourceFile: String?,
        private val method: String,
    ) : MethodVisitor(API) {

        private var line = 0

        override fun visitLineNumber(line: Int, start: Label?) {
            this.line = line
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            add(owner, name, descriptor, RefKind.METHOD)
        }

        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
            add(owner, name, descriptor, RefKind.FIELD)
        }

        override fun visitTypeInsn(opcode: Int, type: String) {
            // NEW / ANEWARRAY / CHECKCAST / INSTANCEOF. This is what makes DET004 able to fire
            // at all: `for (x in someHashMap)` compiles to an interface call whose runtime type
            // is invisible here, but the `NEW java/util/HashMap` that produced it is not.
            if (!type.startsWith("[")) add(type, TYPE_MEMBER, "", RefKind.TYPE)
        }

        override fun visitLdcInsn(value: Any?) {
            if (value is Type && value.sort == Type.OBJECT) {
                add(value.internalName, TYPE_MEMBER, "", RefKind.TYPE)
            }
        }

        private fun add(owner: String, name: String, descriptor: String, kind: RefKind) {
            sink += MemberRef(
                className = className,
                sourceFile = sourceFile,
                method = method,
                line = line,
                owner = owner.replace('/', '.'),
                member = name,
                descriptor = descriptor,
                kind = kind,
            )
        }
    }

    /** [MemberRef.member] used for a reference to a type rather than to one of its members. */
    public const val TYPE_MEMBER: String = "<type>"
}
