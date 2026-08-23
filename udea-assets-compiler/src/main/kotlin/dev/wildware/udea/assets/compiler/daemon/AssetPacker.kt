package dev.wildware.udea.assets.compiler.daemon

import dev.wildware.udea.assets.AnimNotify
import dev.wildware.udea.assets.AssetData
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.assets.ComponentSpec
import dev.wildware.udea.assets.EntityDefinition
import dev.wildware.udea.assets.Level
import dev.wildware.udea.assets.ResPath
import dev.wildware.udea.assets.SoundCue
import dev.wildware.udea.assets.SpriteAnimation
import dev.wildware.udea.assets.SpriteSheet
import dev.wildware.udea.assets.TypeName
import dev.wildware.udea.assets.assetRef
import dev.wildware.udea.assets.compiler.AssetCompilerRules
import dev.wildware.udea.assets.compiler.DeclaredAsset
import dev.wildware.udea.assets.compiler.Ref
import dev.wildware.udea.assets.compiler.ResFile
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import kotlin.reflect.KClass

/** One asset packed, or the diagnostic saying why it could not be. */
public sealed interface PackOutcome {

    /** The runtime value. */
    public data class Packed(public val data: AssetData) : PackOutcome

    /** No value; [diagnostic] says what stopped it. */
    public data class Unpackable(public val diagnostic: UdeaDiagnostic) : PackOutcome
}

/**
 * A [DeclaredAsset] as the [AssetData] a running graph holds.
 *
 * ## What this is a slice of, and what it is not
 *
 * Pass 4 proper - deterministic slot assignment, the `.udeapak` writer, the atlas index - is
 * issue #90's and lives in `udea-assets/pack`. This is the one thing a *hot reload* needs and
 * that neither of those provides: a new value at an id the running graph already has. It packs
 * one declaration, into memory, with no slots and no bytes.
 *
 * ## Two kinds are reported rather than guessed
 *
 * - `character(...)` is [dev.wildware.udea.assets.compiler.AssetKind.Unpublishable]: there is no
 *   `Character` type in `udea-assets` to construct.
 * - `gameConfig(...)`'s `defaultCharacter` points at a `character`, and
 *   [dev.wildware.udea.assets.GameConfig.defaultCharacter] is a `Ref<Blueprint>`. Packing it
 *   would mint a reference that type-checks against `Blueprint` and resolves - if the character
 *   were packable at all - to something that is not one, which is a `AssetTypeMismatchException`
 *   thrown from inside the game rather than a diagnostic at the edit. The DSL's own KDoc names
 *   this seam; the honest treatment is [AssetCompilerRules.UNPACKABLE_KIND] until #84's generated
 *   DSL closes it.
 *
 * An unpackable asset is not an error in a *validate* - a corpus is not broken because
 * `udea-assets` has no `Character` yet. It is an error at exactly one moment, a reload that
 * changes one, because that is the only moment the missing value would change what the game shows.
 * [AssetDaemon] is where that distinction is made.
 */
public object AssetPacker {

    /** The DSL words this packs. Everything else is [AssetCompilerRules.UNPACKABLE_KIND]. */
    public val PACKABLE_KINDS: Set<String> =
        setOf("spriteSheet", "spriteAnimation", "soundCue", "blueprint", "level")

    /** [asset] as a runtime value, or the diagnostic saying why not. */
    public fun pack(asset: DeclaredAsset): PackOutcome {
        val id = AssetId(asset.id)
        return when (asset.kind) {
            "spriteSheet" -> PackOutcome.Packed(
                SpriteSheet(
                    id = id,
                    texture = asset.resPath("spritePath"),
                    columns = asset.typed("columns"),
                    rows = asset.typed("rows"),
                    scale = asset.typed("scale"),
                ),
            )

            "spriteAnimation" -> PackOutcome.Packed(
                SpriteAnimation(
                    id = id,
                    sheet = asset.ref("sheet", SpriteSheet::class),
                    loop = asset.typed("loop"),
                    interruptible = asset.typed("interruptable"),
                    notifies = asset.typed<Map<*, *>>("notifies").entries
                        .map { AnimNotify(frame = it.value as Int, name = it.key as String) }
                        .sortedBy { it.frame },
                ),
            )

            "soundCue" -> PackOutcome.Packed(
                SoundCue(
                    id = id,
                    sounds = asset.typed<List<*>>("sounds").map { ResPath((it as ResFile).value) },
                    pitchVariance = asset.typed("pitchVariance"),
                    volume = asset.typed("volume"),
                ),
            )

            "blueprint" -> PackOutcome.Packed(
                Blueprint(
                    id = id,
                    components = asset.typed<List<*>>("components").map { ComponentSpec(TypeName(it as String)) },
                    // The DSL's `parent` is an authoring convenience; `Blueprint` is flattened and
                    // keeps the chain as provenance only. Flattening proper - concatenating the
                    // parent's components in - is #90's, and this daemon refuses a reload that
                    // changes a component list anyway, so a half-flattened value can never be
                    // pushed into a running game. See `StructuralChange.BLUEPRINT_COMPONENTS`.
                    inheritedFrom = listOfNotNull(asset.optionalRef("parent")).map { AssetId(it.id) },
                ),
            )

            "level" -> PackOutcome.Packed(
                Level(
                    id = id,
                    entities = asset.typed<List<*>>("entities").map { entity ->
                        val ref = entity as Ref
                        EntityDefinition(
                            name = ref.id.substringAfterLast('/'),
                            blueprint = assetRef(AssetId(ref.id), Blueprint::class),
                        )
                    },
                ),
            )

            else -> PackOutcome.Unpackable(
                AssetCompilerRules.UNPACKABLE_KIND.diagnostic(
                    message = "'${asset.id}' is declared with `${asset.kind}(...)`, which no " +
                        "AssetData type in udea-assets corresponds to field for field, so it " +
                        "cannot be packed into a running graph. Packable kinds today: " +
                        PACKABLE_KINDS.sorted().joinToString(", "),
                    span = asset.origin,
                    assetId = asset.id,
                ),
            )
        }
    }

    // --- field readers -------------------------------------------------------------------------
    //
    // Every one of these fails loudly on a type it did not expect. They read a map produced by
    // this repository's own DSL two files away, so a mismatch is a defect in that DSL rather than
    // user input - and a lenient reader would answer a renamed field with a default, which is a
    // hot reload that silently resets the number the author was tuning.

    private fun DeclaredAsset.field(name: String): Any? {
        require(name in fields) {
            "asset '$id' declared with `$kind(...)` has no field '$name'; it has " +
                fields.keys.sorted().joinToString(", ")
        }
        return fields[name]
    }

    private inline fun <reified T> DeclaredAsset.typed(name: String): T {
        val value = field(name)
        return value as? T ?: error(
            "asset '$id' field '$name' holds ${value?.let { it::class.simpleName } ?: "null"}, " +
                "and ${T::class.simpleName} is required to pack a `$kind`",
        )
    }

    private fun DeclaredAsset.resPath(name: String): ResPath = ResPath(typed<ResFile>(name).value)

    private fun <T : AssetData> DeclaredAsset.ref(name: String, expected: KClass<T>) =
        assetRef(AssetId(typed<Ref>(name).id), expected)

    private fun DeclaredAsset.optionalRef(name: String): Ref? = field(name) as Ref?
}
