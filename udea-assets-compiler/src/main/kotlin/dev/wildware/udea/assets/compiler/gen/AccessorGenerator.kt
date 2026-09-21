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
import com.squareup.kotlinpoet.joinToCode
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
 * rename invalidate that classpath and recompile every script in the tree - **every** script,
 * whatever the corpus happens to hold, against a 3s asset-edit budget. (This sentence used to
 * cost that out as "nineteen script compilations". It was true when it was written, `moba/game/assets`
 * has grown since, and the cost was never the number: it is that the count is the size of the
 * tree rather than the size of the edit.)
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
    public fun generate(declarations: List<Declaration>): List<GeneratedFile> = generate(declarations, emptyMap())

    /**
     * [generate], plus one object per model holding its typed clips (issue #241) and its named
     * nodes (issue #260): `Fox.Clips.Run`, `Chassis.Nodes.socket_roof`. [clips] and [nodes] map
     * a model's asset id to what [ModelFileSource] read from its file; a model whose file holds
     * neither gets no object, so a game with no animated model and no socket compiles nothing
     * that names `udea-core`.
     *
     * Still a pure function of its arguments: the file was read before this is called, so two
     * runs over the same scan and the same files emit the same bytes.
     */
    internal fun generate(
        declarations: List<Declaration>,
        clips: Map<String, List<GltfClip>>,
        nodes: Map<String, List<GltfNode>> = emptyMap(),
    ): List<GeneratedFile> {
        val typed = declarations
            .filter { DslKinds[it.kind] != null }
            .distinctBy { it.id }
            .sortedBy { it.id }

        val groups = typed.groupBy { groupOf(it.id) }.toSortedMap()
        val files = groups.map { (group, members) -> groupFile(group, members) }
        val taken = groups.keys.mapTo(mutableSetOf(ROOT), ::objectNameOf)
        val models = typed.mapNotNull { model ->
            val modelClips = clips[model.id].orEmpty()
            val modelNodes = nodes[model.id].orEmpty()
            if (modelClips.isEmpty() && modelNodes.isEmpty()) null else modelFile(model, modelClips, modelNodes, taken)
        }
        return files + rootFile(groups.keys.toList()) + models
    }

    /**
     * `object Fox { object Clips { ... } object Nodes { ... } }` for one model.
     *
     * The object is named for the id's last segment (`models/fox` is `Fox`), and made unique
     * against every group object, [ROOT] and the other models in [taken], which it adds itself
     * to. A file with no animations gets no `Clips`, and one with no named node no `Nodes`.
     */
    private fun modelFile(
        model: Declaration,
        clips: List<GltfClip>,
        nodes: List<GltfNode>,
        taken: MutableSet<String>,
    ): GeneratedFile {
        val objectName = unique(pascalCase(model.id.substringAfterLast('/')).ifEmpty { "Model" }, taken)
        val type = TypeSpec.objectBuilder(objectName)
            .addKdoc(
                "The model `%L`, declared by `model(...)`: its animation clips and the nodes a " +
                    "part can be mounted on, typed.\n\nThe model itself is `%L.%L.%L`.\n",
                model.id,
                ROOT,
                memberName(groupOf(model.id)),
                memberName(model.id.substringAfterLast('/')),
            )
        if (clips.isNotEmpty()) type.addType(clipsObject(model, clips))
        if (nodes.isNotEmpty()) type.addType(nodesObject(model, nodes))
        return fileOf(objectName, type.build())
    }

    /** `object Clips { val Run: AnimationClip = ... }`: one property per animation in the file. */
    private fun clipsObject(model: Declaration, clips: List<GltfClip>): TypeSpec {
        val clipsType = TypeSpec.objectBuilder(CLIPS_OBJECT)
            .addKdoc(
                "The animations in `%L`, in the file's order, each with its length in ticks at " +
                    "60Hz.\n\nGenerated from the file at build time; a name that is not here is " +
                    "not in the file.\n",
                model.fileArgument.orEmpty(),
            )
        val used = mutableSetOf<String>()
        val members = ArrayList<String>(clips.size)
        for (clip in clips) {
            val member = clipMemberName(clip, used)
            members += member
            clipsType.addProperty(
                PropertySpec.builder(member, ANIMATION_CLIP)
                    .addKdoc("Animation %L of the file, %L ticks long.\n", clip.index, clip.ticks)
                    .initializer(
                        "%T(index = %L, name = %S, length = %T(%LL))",
                        ANIMATION_CLIP,
                        clip.index,
                        clip.name ?: member,
                        TICKS,
                        clip.ticks,
                    )
                    .build(),
            )
        }
        // `all` is lower case and every clip's member starts with a capital, so the two never meet.
        clipsType.addProperty(
            PropertySpec.builder(ALL_MEMBERS, LIST.parameterizedBy(ANIMATION_CLIP))
                .addKdoc("Every clip above, in the file's order: what an editor lists for this model.\n")
                .initializer("%M(%L)", LIST_OF, members.map { CodeBlock.of("%N", it) }.joinToCode())
                .build(),
        )
        return clipsType.build()
    }

    /**
     * `object Nodes { val socket_roof: ModelNode = ... }`: one property per named node in the
     * file, carrying where that node sits at rest (issue #260).
     *
     * Every named node, not only the ones called `socket_*`: a bone is a mounting point too - a
     * pack on a walker's spine, a muzzle flash on a gun's barrel - and a file's naming
     * convention is the game's business rather than the build's.
     *
     * A node the artist attached values to carries them as `extras = ModelExtras(...)` (issue
     * #271), so the accessor equals the node a packed `Model.nodes` holds; one with none is
     * written exactly as it was before extras existed.
     */
    private fun nodesObject(model: Declaration, nodes: List<GltfNode>): TypeSpec {
        val nodesType = TypeSpec.objectBuilder(NODES_OBJECT)
            .addKdoc(
                "The named nodes of `%L`, in the file's order, each at its place at rest in the " +
                    "model's own frame - Z up, as the world is.\n\nGenerated from the file at " +
                    "build time; a name that is not here is not in the file.\n",
                model.fileArgument.orEmpty(),
            )
        // `all` is taken before any node can claim it, so a node named `all` is numbered instead.
        val used = mutableSetOf(ALL_MEMBERS)
        val members = ArrayList<String>(nodes.size)
        for (node in nodes) {
            val member = unique(identifier(node.name), used)
            members += member
            nodesType.addProperty(
                PropertySpec.builder(member, MODEL_NODE)
                    .addKdoc("Node %L of the file, `%L`.\n", node.index, node.name)
                    .initializer(
                        "%T(index = %L, name = %S, x = %L, y = %L, z = %L, qx = %L, qy = %L, " +
                            "qz = %L, qw = %L, scaleX = %L, scaleY = %L, scaleZ = %L%L)",
                        MODEL_NODE,
                        node.index,
                        node.name,
                        float(node.x), float(node.y), float(node.z),
                        float(node.qx), float(node.qy), float(node.qz), float(node.qw),
                        float(node.scaleX), float(node.scaleY), float(node.scaleZ),
                        extrasArgument(node.extras),
                    )
                    .build(),
            )
        }
        nodesType.addProperty(
            PropertySpec.builder(ALL_MEMBERS, LIST.parameterizedBy(MODEL_NODE))
                .addKdoc("Every node above, in the file's order: what an editor lists for this model.\n")
                .initializer("%M(%L)", LIST_OF, members.map { CodeBlock.of("%N", it) }.joinToCode())
                .build(),
        )
        return nodesType.build()
    }

    /**
     * `, extras = ModelExtras(mapOf(...))` for a node with [extras], and nothing for one without.
     *
     * Each value is written as the `AssetValue` case the packed bundle decodes it to, key by key
     * in sorted order, so the accessor and the bundle's node are equal and a rebuild emits the
     * same characters.
     */
    private fun extrasArgument(extras: Map<String, Any>): CodeBlock {
        if (extras.isEmpty()) return CodeBlock.of("")
        val entries = extras.toSortedMap().map { (key, value) -> CodeBlock.of("%S to %L", key, extraValue(value)) }
        return CodeBlock.of(", extras = %T(%M(%L))", MODEL_EXTRAS, MAP_OF, entries.joinToCode())
    }

    /** One plain extras value, as the `AssetValue` constructor call that makes it. */
    private fun extraValue(value: Any): CodeBlock = when (value) {
        is Boolean -> CodeBlock.of("%T(%L)", BOOL_VALUE, value)
        is Int -> CodeBlock.of("%T(%L)", INT_VALUE, value)
        is Float -> CodeBlock.of("%T(%L)", FLOAT_VALUE, float(value))
        is String -> CodeBlock.of("%T(%S)", TEXT_VALUE, value)
        is List<*> -> CodeBlock.of(
            "%T(%M(%L))",
            LIST_VALUE,
            LIST_OF,
            value.map { CodeBlock.of("%T(%L)", FLOAT_VALUE, float(it as Float)) }.joinToCode(),
        )
        else -> error("`GltfExtras` produced ${value::class.simpleName}, which is not a plain extras value")
    }

    /**
     * [value] as Kotlin source for a `Float` literal.
     *
     * `Float.toString` is the shortest decimal that reads back as the same float on every
     * platform Kotlin targets, so the generated source holds exactly the number the build read -
     * and a rebuild of an unchanged file emits the same characters, which is what lets the
     * golden test compare bytes. Infinity and NaN cannot be written as literals; a model file
     * holding one is refused before this, by `GltfNodes`.
     */
    private fun float(value: Float): String = "${value}f"

    /**
     * [name] as a Kotlin identifier: the file's own name where it can be one, so
     * `Chassis.Nodes.socket_roof` reads the way the model was authored.
     *
     * A character that cannot be in an identifier becomes `_`, and a name that cannot start one -
     * `2_wheel`, or an empty name - is prefixed with `_`. Backticks are deliberately not used:
     * a name a person has to quote to write is a name worth renaming in the model.
     */
    private fun identifier(name: String): String {
        val safe = name.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
        return if (safe.isEmpty() || !(safe.first().isLetter() || safe.first() == '_')) "_$safe" else safe
    }

    /**
     * A clip's property name: the file's name for it in `PascalCase` - `open-slowly` and
     * `Open Slowly` are both `OpenSlowly` - or `Clip<index>` for an unnamed clip. A name that
     * cannot start an identifier is prefixed `Clip`, and a clash is numbered from 2, in file
     * order, so two builds of one file always agree on which clip got which name.
     */
    private fun clipMemberName(clip: GltfClip, used: MutableSet<String>): String {
        val words = pascalCase(clip.name.orEmpty())
        val base = when {
            words.isEmpty() -> "Clip${clip.index}"
            !words.first().isLetter() -> "Clip$words"
            else -> words
        }
        return unique(base, used)
    }

    /** [base], or [base] numbered from 2 until it is not in [used]; the result is added to it. */
    private fun unique(base: String, used: MutableSet<String>): String {
        if (used.add(base)) return base
        var at = 2
        while (!used.add("$base$at")) at++
        return "$base$at"
    }

    /** `orc_elite`, `open-slowly` and `Open Slowly` as `OrcElite`, `OpenSlowly`, `OpenSlowly`. */
    private fun pascalCase(name: String): String =
        name.split(NON_IDENTIFIER).filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar(Char::uppercase) }

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

    private fun objectNameOf(group: String): String = pascalCase(group) + "Assets"

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

    /**
     * `udea-core`'s clip handle and duration, by name: this build-time module does not depend on
     * the kernel, and the game module compiling the generated source does.
     */
    private val ANIMATION_CLIP = ClassName("dev.wildware.udea.core.spatial", "AnimationClip")

    /**
     * The node handle: where a part is mounted (issue #260). `udea-assets`' since issue #271, so
     * a packed `Model` can list its nodes; this module depends on `udea-assets`, but the class is
     * named rather than imported, like every other type the generated source names.
     */
    private val MODEL_NODE = ClassName("dev.wildware.udea.assets", "ModelNode")

    /** What an artist attached to a node, and the value cases it holds (issue #271). */
    private val MODEL_EXTRAS = ClassName("dev.wildware.udea.assets", "ModelExtras")
    private val ASSET_VALUE = ClassName("dev.wildware.udea.assets", "AssetValue")
    private val BOOL_VALUE = ASSET_VALUE.nestedClass("BoolValue")
    private val INT_VALUE = ASSET_VALUE.nestedClass("IntValue")
    private val FLOAT_VALUE = ASSET_VALUE.nestedClass("FloatValue")
    private val TEXT_VALUE = ASSET_VALUE.nestedClass("TextValue")
    private val LIST_VALUE = ASSET_VALUE.nestedClass("ListValue")
    private val MAP_OF = MemberName("kotlin.collections", "mapOf")
    private val TICKS = ClassName("dev.wildware.udea.core", "Ticks")
    private val LIST = ClassName("kotlin.collections", "List")
    private val LIST_OF = MemberName("kotlin.collections", "listOf")

    /** The member of a model's `Clips` and `Nodes` objects that lists every one (issue #243). */
    private const val ALL_MEMBERS = "all"

    /** The object inside a model's object that holds its clips: `Fox.Clips`. */
    private const val CLIPS_OBJECT = "Clips"

    /** The object inside a model's object that holds its nodes: `Chassis.Nodes`. */
    private const val NODES_OBJECT = "Nodes"

    private val NON_IDENTIFIER = Regex("[^A-Za-z0-9]+")
}
