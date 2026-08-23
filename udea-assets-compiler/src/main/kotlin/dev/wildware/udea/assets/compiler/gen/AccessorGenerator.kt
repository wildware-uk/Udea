package dev.wildware.udea.assets.compiler.gen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import dev.wildware.udea.assets.Ref
import dev.wildware.udea.assets.compiler.scan.Declaration

/** One generated file: where it goes, and what is in it. */
public data class GeneratedFile(
    /** Path relative to the generated source root, e.g. `dev/wildware/udea/generated/CharacterAssets.kt`. */
    public val path: String,
    public val text: String,
)

/**
 * Emits `dev.wildware.udea.generated.GameAssets` from the pass-1 declaration scan.
 *
 * ## Why scripts do not get these, and why that is the whole design
 *
 * Spec 3.6: `.udea.kts` uses validated `reference("id")` strings; only `.kt` uses
 * `GameAssets.character.orcElite`. Without that split the build has a cycle - the accessors are
 * generated from the scripts, so putting them on the script compile classpath makes every asset
 * rename invalidate that classpath and recompile every script in the tree. On this corpus that
 * is nineteen script compilations for a rename, against a 3s asset-edit budget.
 *
 * Nothing in *this file* enforces the split: it emits source, and what compiles against it is
 * decided by whoever registers the source directory. `AccessorsNotOnScriptClasspathTest` is
 * where the enforcement is checked, by compiling a `.udea.kts` that mentions `GameAssets` and
 * asserting it fails.
 *
 * ## One file per group, and every member a plain `val`
 *
 * Kotlin's ABI snapshotting compares the *shape* of a class. Adding a `val` of an existing type
 * to `CharacterAssets` does not change anything about `LevelAssets`, so a downstream file that
 * only touched `GameAssets.level` is not recompiled. That is the difference issue #90 calls out
 * between a 3s edit loop and a whole-module rebuild - and it is why nothing here is emitted as
 * a function, an inline accessor, or a `const`: each of those puts something in the ABI that
 * moves when the asset set moves.
 */
public object AccessorGenerator {

    /** The package every generated accessor lands in. */
    public const val PACKAGE: String = "dev.wildware.udea.generated"

    /** The aggregate object, whose members are the per-group objects. */
    public const val ROOT: String = "GameAssets"

    private val REF = Ref::class.asClassName()

    /**
     * Generates one file per top-level group plus the [ROOT] aggregate.
     *
     * Output is a pure function of the ids and kinds in [declarations]: the spans are ignored,
     * so moving a declaration within its file regenerates byte-identical source and nothing
     * downstream recompiles.
     */
    public fun generate(declarations: List<Declaration>): List<GeneratedFile> {
        val typed = declarations
            .filter { DslKinds[it.kind] != null }
            .distinctBy { it.id }
            .sortedBy { it.id }

        val groups = typed.groupBy { groupOf(it.id) }.toSortedMap()
        val files = groups.map { (group, members) -> groupFile(group, members) }
        return files + rootFile(groups.keys.toList())
    }

    /**
     * The top-level folder of an id, or [ROOT_GROUP] for an id at the asset root.
     *
     * `character/orc_elite` groups as `character`. A deeper id, `ui/hud/health_bar`, groups by
     * its *first* segment only: nesting the objects to match the folders would mean a rename of
     * an intermediate folder rewrites every generated file under it, which is exactly the ABI
     * churn the one-file-per-group rule exists to avoid.
     */
    public fun groupOf(id: String): String =
        id.substringBefore('/', missingDelimiterValue = "").ifEmpty { ROOT_GROUP }

    /** The group an id at the asset root belongs to. `config.udea.kts` declares one. */
    public const val ROOT_GROUP: String = "root"

    private fun groupFile(group: String, members: List<Declaration>): GeneratedFile {
        val objectName = objectNameOf(group)
        val type = TypeSpec.objectBuilder(objectName)
            .addKdoc(
                "Assets under `%L/`.\n\nGenerated from the `.udea.kts` declaration scan; edits are lost.\n",
                group,
            )
        val used = mutableSetOf<String>()
        for (member in members) {
            val kind = DslKinds[member.kind] ?: continue
            val name = uniqueMemberName(member.id.substringAfterLast('/'), used)
            type.addProperty(
                PropertySpec.builder(name, REF.parameterizedBy(kind.asClassName()))
                    .addKdoc("`%L`, declared by `%L(...)`.\n", member.id, member.kind)
                    .initializer(
                        // reference(id) rather than reference<T>(id): the property's declared
                        // type supplies T, and spelling it twice would be a second place a
                        // kind rename has to reach.
                        CodeBlock.of("%M(%S)", REFERENCE, member.id),
                    )
                    .build(),
            )
        }
        return fileOf(objectName, type.build())
    }

    private fun rootFile(groups: List<String>): GeneratedFile {
        val type = TypeSpec.objectBuilder(ROOT)
            .addKdoc(
                "Every asset this module declares, by group.\n\n" +
                    "Only `.kt` uses this. A `.udea.kts` names assets with `reference(\"id\")` " +
                    "strings, which the K2 checker validates - see spec 3.6.\n",
            )
        for (group in groups) {
            val objectName = objectNameOf(group)
            type.addProperty(
                PropertySpec.builder(group.let(::memberName), ClassName(PACKAGE, objectName))
                    .initializer("%T", ClassName(PACKAGE, objectName))
                    .build(),
            )
        }
        return fileOf(ROOT, type.build())
    }

    private fun fileOf(name: String, type: TypeSpec): GeneratedFile {
        val spec = FileSpec.builder(PACKAGE, name)
            .addFileComment("Generated by udea-assets-compiler. Do not edit.")
            .addAnnotation(
                // The generated names come from asset ids, which are snake_case by convention;
                // `orc_elite` becomes `orcElite`, but `hp` stays `hp` and a numeric-leading id
                // becomes `_2h_sword`. Suppressing here rather than mangling harder keeps the
                // generated name recognisably the id it came from.
                AnnotationSpec.builder(Suppress::class)
                    .addMember("%S", "ObjectPropertyName")
                    .addMember("%S", "RedundantVisibilityModifier")
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .build(),
            )
            .addType(type)
            .indent("    ")
            .build()
        return GeneratedFile(
            path = PACKAGE.replace('.', '/') + "/" + name + ".kt",
            text = spec.toString(),
        )
    }

    private fun objectNameOf(group: String): String =
        group.split('_', '-').joinToString("") { it.replaceFirstChar(Char::uppercase) } + "Assets"

    /** `orc_elite` -> `orcElite`. An id that cannot start an identifier gets a leading `_`. */
    public fun memberName(name: String): String {
        val camel = name.split('_', '-', '.').filter { it.isNotEmpty() }
            .mapIndexed { at, part -> if (at == 0) part else part.replaceFirstChar(Char::uppercase) }
            .joinToString("")
        val safe = camel.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        return if (safe.isEmpty() || !safe.first().isLetter()) "_$safe" else safe
    }

    /**
     * A name no other member of this object already has.
     *
     * `orc_elite` and `orcElite` are different ids that camel-case to the same member. Rare, and
     * silently emitting a file that does not compile would be much worse than a suffix.
     */
    private fun uniqueMemberName(name: String, used: MutableSet<String>): String {
        val base = memberName(name)
        if (used.add(base)) return base
        var at = 2
        while (!used.add("$base$at")) at++
        return "$base$at"
    }

    private val REFERENCE = MemberName("dev.wildware.udea.assets", "reference")
}
