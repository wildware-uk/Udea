package dev.wildware.udea.core.replication

import dev.wildware.udea.core.KotlinSource
import dev.wildware.udea.core.ModuleFiles
import java.io.File
import kotlin.reflect.KType
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shape of the frozen interface, checked rather than trusted.
 *
 * Spec 7 names `Replicator<T>` the highest-coupling interface in the project: four modules
 * break together if it moves. The extension everybody can already see coming is a component
 * with more than 64 replicated fields, forcing `Long` to `LongArray`. That is a non-breaking
 * change **only** while no signature says `Long` where it means "a set of fields", so that
 * is what this test enforces.
 */
class ReplicatorApiShapeTest {

    @Test
    fun `Replicator declares exactly the frozen members`() {
        val members = (
            Replicator::class.declaredMemberProperties.map { it.name } +
                Replicator::class.declaredMemberFunctions.map { it.name }
            ).sorted()

        assertEquals(
            listOf(
                "allMask",
                "apply",
                "capture",
                "diff",
                "fieldNames",
                "getField",
                "netMask",
                "read",
                "setField",
                "typeId",
                "write",
            ),
            members,
            "the Replicator surface is frozen: adding or removing a member is a four-module change",
        )
    }

    @Test
    fun `every field set in the Replicator surface is a FieldMask`() {
        val maskType = FieldMask::class.qualifiedName

        assertEquals(maskType, typeName(Replicator::class.declaredMemberProperties.single { it.name == "netMask" }.returnType))
        assertEquals(maskType, typeName(Replicator::class.declaredMemberProperties.single { it.name == "allMask" }.returnType))
        assertEquals(maskType, typeName(function("diff").returnType))
        assertEquals(maskType, typeName(function("read").returnType))
        assertEquals(maskType, typeName(parameterType("write", "mask")))
        assertEquals(maskType, typeName(parameterType("apply", "mask")))
    }

    @Test
    fun `no member of Replicator exposes a raw Long`() {
        // A value class erases to its underlying type on the JVM, so this has to run on
        // Kotlin's reflection. If it ever passes trivially, the reflection broke.
        val offenders = Replicator::class.declaredMemberFunctions.flatMap { function ->
            val types = function.parameters.map { it.name to it.type } + ("return" to function.returnType)
            types.filter { typeName(it.second) == "kotlin.Long" }
                .map { "${function.name}(${it.first})" }
        } + Replicator::class.declaredMemberProperties
            .filter { typeName(it.returnType) == "kotlin.Long" }
            .map { it.name }

        assertEquals(
            emptyList(),
            offenders.sorted(),
            "a Long here would be a mask in disguise, and widening the mask would break every caller",
        )
    }

    @Test
    fun `FieldMask keeps its storage internal`() {
        val bits = FieldMask::class.declaredMemberProperties.single { it.name == "bits" }

        assertEquals(kotlin.reflect.KVisibility.INTERNAL, bits.visibility)
        assertEquals(
            listOf("bits"),
            FieldMask::class.declaredMemberProperties.map { it.name },
            "FieldMask must carry exactly one storage property, and it must not be public",
        )
    }

    @Test
    fun `no bitwise operator is reachable on a FieldMask`() {
        val operators = setOf("and", "or", "xor", "inv", "shl", "shr", "ushr", "plus", "minus")
        val exposed = FieldMask::class.declaredMemberFunctions
            .map { it.name }
            .filter { it in operators }

        assertEquals(
            emptyList(),
            exposed,
            "every bit operation goes through MaskOps, or callers start depending on the storage",
        )
    }

    @Test
    fun `no declared property outside udea-core and udea-net names FieldMask in its type`() {
        // The mask may be passed through the Replicator API, never stored in game code. A
        // component or system holding one is how a Long-to-LongArray widening turns into a
        // breaking change.
        //
        // What this enforces is exactly its name: a `val`/`var` whose *declared* type
        // mentions FieldMask anywhere - `FieldMask`, `FieldMask?`, `List<FieldMask>`,
        // `Map<NetId, FieldMask>`, a constructor property. It cannot see storage behind an
        // inferred type (`private val cached = replicator.netMask`); no source scan can, and
        // the compiled-class variant is no better, because FieldMask is a value class over
        // Long and its field descriptor is `J` - that check would flag every Long field in
        // every module. The four reflection tests above carry the load-bearing half of the
        // spec-7 mitigation; this is a belt over those braces.
        //
        // Scope: shipped source only — `src/main` and `src/testFixtures`, the code a
        // widening would have to keep compiling without a source change. Test sources are
        // deliberately excluded: a test names a `FieldMask` local to make an assertion
        // readable, which is passing the mask through the API, not storing it, and it is
        // recompiled in the same commit as any widening anyway. Generated `Replicator`s
        // live under `build/` and are regenerated, so they are out of scope by construction.
        val allowedModules = setOf("udea-core", "udea-net")
        val moduleRoots = ModuleFiles.repoRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory && (it.name.startsWith("udea-") || it.name == "moba") }
            .filterNot { it.name in allowedModules }
            .sortedBy { it.name }

        assertTrue(moduleRoots.isNotEmpty(), "expected sibling udea-* modules beside udea-core")

        val offenders = moduleRoots.flatMap { module ->
            listOf("src/main", "src/testFixtures").flatMap { sourceSet ->
                ModuleFiles.kotlinFilesIn(module.resolve(sourceSet))
                    .flatMap { file -> fieldMaskProperties(file) }
            }
        }

        assertEquals(
            emptyList(),
            offenders,
            "FieldMask is passed through the Replicator API and never stored outside udea-core/udea-net",
        )
    }

    /**
     * Every `val`/`var` in [file] whose declared type mentions `FieldMask`.
     *
     * Same shape as `FleksEntityBoundaryRule`, the sibling source rule in this module: find
     * the declaration, extract its type, then look for the name anywhere inside it. Matching
     * `: FieldMask` directly - as this used to - misses every generic and nullable form,
     * which is most of the ways a mask would actually get stored.
     */
    @Test
    fun `the FieldMask storage rule fires on the forms idiomatic Kotlin actually uses`() {
        // A source rule that only matches `: FieldMask` certifies far less than its name
        // claims, and a guard nobody has watched fail is a guard nobody should trust. Every
        // line here is a way a mask would really get stored.
        val caught = """
            class Offenders(private val ctor: FieldMask) {
                val plain: FieldMask = MaskOps.EMPTY
                var nullable: FieldMask? = null
                private val many: List<FieldMask> = emptyList()
                val byId: Map<NetId, FieldMask> = emptyMap()
                val wrapped: Map<NetId, List<FieldMask>> = emptyMap()
                val multiLine: Map<
                    NetId,
                    FieldMask,
                > = emptyMap()
            }
        """.trimIndent()
        assertEquals(7, fieldMaskProperties("Offenders.kt", caught).size, caught)

        // And it does not fire on prose, on a name that merely starts the same way, or on
        // passing a mask through the API — which is the sanctioned use.
        val allowed = """
            /** A FieldMask is passed through, never stored: val stored: FieldMask. */
            class Fine(val label: String) {
                val note: String = "val cached: FieldMask"
                val other: FieldMaskCache = FieldMaskCache()
                fun write(mask: FieldMask, out: BitWriter) {
                    val local = MaskOps.and(mask, mask)
                }
            }
        """.trimIndent()
        assertEquals(emptyList(), fieldMaskProperties("Fine.kt", allowed), allowed)
    }

    @Test
    fun `implementing the frozen Replicator is not storing a mask`() {
        // The exact shape udea-gas's hand-written AttributesReplicator has. Flagging it means
        // the rule forbids implementing the interface it exists to protect, which is what it
        // did before `overrides` was added.
        val implementation = """
            class AttributesReplicator : Replicator<Attributes> {
                override val netMask: FieldMask = MaskOps.single(BASE)
                public override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)
            }
        """.trimIndent()
        assertEquals(emptyList(), fieldMaskProperties("AttributesReplicator.kt", implementation), implementation)

        // And the exemption is the modifier, not the name: drop `override` and the same two
        // lines are storage again.
        val stored = implementation.replace("override ", "")
        assertEquals(2, fieldMaskProperties("AttributesReplicator.kt", stored).size, stored)
    }

    @Test
    fun `the only overridable FieldMask properties are the frozen Replicator's`() {
        // `overrides` exempts every `override val ...: FieldMask` in every module. That is only
        // sound while `Replicator` is the sole thing in udea-core that declares such a property
        // to override. This enumerates udea-core's own declarations so a new one - a second
        // interface, an open class - fails here and forces the exemption to be re-argued rather
        // than quietly covering it too.
        val declarations = listOf("src/main")
            .flatMap { ModuleFiles.kotlinFilesIn(ModuleFiles.repoRoot.resolve("udea-core").resolve(it)) }
            .flatMap { file -> maskPropertyNames(ModuleFiles.relativePath(file), file.readText()) }
            .sorted()

        assertEquals(
            listOf(
                "udea-core/src/main/kotlin/dev/wildware/udea/core/replication/FieldMask.kt:ALL",
                "udea-core/src/main/kotlin/dev/wildware/udea/core/replication/FieldMask.kt:EMPTY",
                "udea-core/src/main/kotlin/dev/wildware/udea/core/replication/Replicator.kt:allMask",
                "udea-core/src/main/kotlin/dev/wildware/udea/core/replication/Replicator.kt:netMask",
            ),
            declarations,
            "a new FieldMask property in udea-core may be overridable, which would widen the " +
                "override exemption in `overrides` past the frozen Replicator",
        )
    }

    /** `path:propertyName` for every FieldMask-typed property declared in [source]. */
    private fun maskPropertyNames(path: String, source: String): List<String> {
        val code = KotlinSource.stripCommentsAndStrings(source)
        return PROPERTY_DECLARATION.findAll(code)
            .filter { FIELD_MASK.containsMatchIn(declaredTypeAt(code, it.range.last + 1)) }
            .map { "$path:" + PROPERTY_NAME.find(it.value)!!.groupValues[1] }
            .toList()
    }

    private fun fieldMaskProperties(file: File): List<String> =
        fieldMaskProperties(ModuleFiles.relativePath(file), file.readText())

    private fun fieldMaskProperties(path: String, source: String): List<String> {
        val code = KotlinSource.stripCommentsAndStrings(source)
        return PROPERTY_DECLARATION.findAll(code)
            .filterNot { overrides(code, it.range.first) }
            .filter { FIELD_MASK.containsMatchIn(declaredTypeAt(code, it.range.last + 1)) }
            .map { "$path:${KotlinSource.lineOf(code, it.range.first)}" }
            .toList()
    }

    /**
     * The declared type beginning at [from], which is one character past a property's `:`.
     *
     * Runs to the initialiser, the accessor or the end of the declaration, carrying across a
     * newline only while a bracket is open so a multi-line generic argument list is read
     * whole. A `,` or `)` at depth zero ends it, which is what stops a constructor property's
     * type from swallowing the parameters declared after it.
     */
    private fun declaredTypeAt(code: String, from: Int): String {
        var cursor = from
        var depth = 0
        while (cursor < code.length) {
            when (val char = code[cursor]) {
                '<', '(', '[' -> depth++
                '>', ')', ']' -> when {
                    depth > 0 -> depth--
                    char != '>' -> return code.substring(from, cursor)
                }
                ',', '=', '{', '\n' -> if (depth == 0) return code.substring(from, cursor)
            }
            cursor++
        }
        return code.substring(from)
    }

    /**
     * Whether the declaration beginning at [start] carries the `override` modifier.
     *
     * An `override val netMask: FieldMask` is not storage; it *is* the frozen API, and a
     * `Long`-to-`LongArray` widening leaves that line untouched because the type name does not
     * change. Before this exemption the rule flagged `udea-gas`'s hand-written
     * `AttributesReplicator` - i.e. it forbade implementing the interface it exists to protect.
     *
     * The exemption is sound only because the mask has exactly one overridable declaration site
     * in the project. `the only overridable FieldMask properties are the frozen Replicator's`
     * asserts that by reflection instead of assuming it: the day a second interface declares a
     * `FieldMask` property, that test goes red and this exemption is reconsidered rather than
     * silently widened.
     *
     * Modifiers only, scanned backwards: `override` and `public override` match; `private val`
     * and a preceding line ending in an identifier do not.
     */
    private fun overrides(code: String, start: Int): Boolean =
        OVERRIDE_MODIFIER.containsMatchIn(code.substring(maxOf(0, start - MODIFIER_LOOKBEHIND), start))

    private fun function(name: String) =
        Replicator::class.declaredMemberFunctions.single { it.name == name }

    private fun parameterType(functionName: String, parameterName: String): KType =
        function(functionName).parameters.single { it.name == parameterName }.type

    private fun typeName(type: KType): String? = (type.classifier as? kotlin.reflect.KClass<*>)?.qualifiedName

    private companion object {
        /** A `val`/`var` declaration with an explicit type, matched up to and including its `:`. */
        val PROPERTY_DECLARATION = Regex("""\b(?:val|var)\s+\w+\s*:""")

        /** The mask's name, looked for anywhere inside a declared type. */
        val FIELD_MASK = Regex("""\bFieldMask\b""")

        /**
         * `override`, followed only by further modifier words, immediately before the `val`.
         *
         * Anchored at the end so it fires on a modifier chain and not on a previous line that
         * merely contains the word.
         */
        val OVERRIDE_MODIFIER = Regex("""\boverride(\s+\w+)*\s+$""")

        /** How far back a modifier chain can plausibly reach. */
        const val MODIFIER_LOOKBEHIND = 200

        /** The declared name inside a `PROPERTY_DECLARATION` match. */
        val PROPERTY_NAME = Regex("""(?:val|var)\s+(\w+)""")
    }
}
