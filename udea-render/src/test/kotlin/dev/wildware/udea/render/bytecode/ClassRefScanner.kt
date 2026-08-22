package dev.wildware.udea.render.bytecode

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File

/**
 * One reference from a compiled class to another type.
 *
 * `owner` is the *referenced* type in internal form (`com/badlogic/gdx/graphics/Texture`);
 * [className] and [member] say where the reference was found. Both halves matter: a rule
 * that only reported "udea-core mentions Texture" would leave a human grepping.
 */
internal data class TypeUse(
    /** Binary name of the class holding the reference, dotted. */
    val className: String,
    /** Simple source file name from the class file's debug info, e.g. `Transform.kt`. */
    val sourceFile: String?,
    /** The member the reference sits in: `render(F)V`, `field batch`, or `class declaration`. */
    val member: String,
    /** Internal name of the referenced type. */
    val owner: String,
    /** The referenced member, when the reference was a field or method access. */
    val ownerMember: String?,
    /** Source line from the class file's line-number table; `0` when unknown. */
    val line: Int,
)

/**
 * The one ASM visitor this module's bytecode rules share.
 *
 * Two rules ride on it -- "no headless module names a GL type" (issue #117) and "no
 * `RenderSystem` resolves a `Family` inside `render`" (issue #116) -- and neither one owns
 * a visitor of its own. That is the point: a rule is then a *data table* of banned owners
 * plus a filter over [TypeUse], and adding one is a table entry rather than a second
 * traversal that gradually stops agreeing with the first about what "references" means.
 * `udeaVerifyDeterminism` (Phase 7) is the third rule that will use it.
 *
 * ## What counts as a reference
 *
 * Everything the JVM verifier would have to resolve: the superclass and interfaces, field
 * and method descriptors, every `new`/`checkcast`/`instanceof`, every field and method
 * access, `ldc` of a class literal, `invokedynamic` bootstrap handles (which is where
 * Kotlin's lambdas end up), and `catch` types. Generic *signatures* are deliberately not
 * read: they are erased at runtime, and a type that appears only there cannot be called.
 */
internal object ClassRefScanner {

    /** Every type reference in [classFile]. */
    fun scan(classFile: File): List<TypeUse> = scan(classFile.readBytes())

    /** Every type reference in a class file's [bytes]. */
    fun scan(bytes: ByteArray): List<TypeUse> {
        val collector = Collector()
        ClassReader(bytes).accept(collector, ClassReader.SKIP_FRAMES)
        return collector.finish()
    }

    private class RawUse(val member: String, val owner: String, val ownerMember: String?, val line: Int)

    private class Collector : ClassVisitor(Opcodes.ASM9) {

        private val uses = ArrayList<RawUse>()
        private var className: String = "<unknown>"
        private var sourceFile: String? = null

        fun finish(): List<TypeUse> =
            uses.map { TypeUse(className, sourceFile, it.member, it.owner, it.ownerMember, it.line) }

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
        ) {
            className = name.replace('/', '.')
            val declaration = "class declaration"
            if (superName != null) uses += RawUse(declaration, superName, null, 0)
            interfaces?.forEach { uses += RawUse(declaration, it, null, 0) }
        }

        override fun visitSource(source: String?, debug: String?) {
            sourceFile = source
        }

        override fun visitField(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            value: Any?,
        ): FieldVisitor? {
            record("field $name", descriptor, 0)
            return null
        }

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor {
            val member = "$name$descriptor"
            record(member, descriptor, 0)
            exceptions?.forEach { uses += RawUse(member, it, null, 0) }
            return Body(member)
        }

        private fun record(member: String, descriptor: String, line: Int, ownerMember: String? = null) {
            for (type in objectTypesIn(descriptor)) {
                uses += RawUse(member, type, ownerMember, line)
            }
        }

        private inner class Body(private val member: String) : MethodVisitor(Opcodes.ASM9) {

            private var line = 0

            override fun visitLineNumber(line: Int, start: Label?) {
                this.line = line
            }

            override fun visitTypeInsn(opcode: Int, type: String) {
                uses += RawUse(member, arrayElement(type), null, line)
            }

            override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                uses += RawUse(member, arrayElement(owner), name, line)
                record(member, descriptor, line, name)
            }

            override fun visitMethodInsn(
                opcode: Int,
                owner: String,
                name: String,
                descriptor: String,
                isInterface: Boolean,
            ) {
                uses += RawUse(member, arrayElement(owner), name, line)
                record(member, descriptor, line, name)
            }

            override fun visitInvokeDynamicInsn(
                name: String,
                descriptor: String,
                bootstrapMethodHandle: Handle,
                vararg bootstrapMethodArguments: Any?,
            ) {
                record(member, descriptor, line)
                handle(bootstrapMethodHandle)
                for (argument in bootstrapMethodArguments) {
                    when (argument) {
                        is Handle -> handle(argument)
                        is Type -> objectName(argument)?.let { uses += RawUse(member, it, null, line) }
                    }
                }
            }

            override fun visitLdcInsn(value: Any?) {
                if (value is Type) objectName(value)?.let { uses += RawUse(member, it, null, line) }
            }

            override fun visitTryCatchBlock(start: Label?, end: Label?, handler: Label?, type: String?) {
                if (type != null) uses += RawUse(member, type, null, line)
            }

            override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
                record(member, descriptor, line)
            }

            private fun handle(handle: Handle) {
                uses += RawUse(member, arrayElement(handle.owner), handle.name, line)
                record(member, handle.desc, line, handle.name)
            }
        }
    }

    /**
     * An owner can be an array descriptor (`[Lcom/badlogic/gdx/graphics/Texture;`) rather
     * than a plain internal name -- that is how `invokevirtual` on an array type is encoded.
     * Reduce it to the element type so the banned-owner table stays a list of type names.
     */
    private fun arrayElement(owner: String): String =
        if (owner.startsWith("[")) objectName(Type.getType(owner)) ?: owner else owner

    private fun objectTypesIn(descriptor: String): List<String> =
        if (descriptor.startsWith("(")) {
            val type = Type.getMethodType(descriptor)
            (type.argumentTypes.toList() + type.returnType).mapNotNull(::objectName)
        } else {
            listOfNotNull(objectName(Type.getType(descriptor)))
        }

    private fun objectName(type: Type): String? = when (type.sort) {
        Type.OBJECT -> type.internalName
        Type.ARRAY -> objectName(type.elementType)
        else -> null
    }
}
