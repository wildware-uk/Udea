package dev.wildware.udea.assets.pack

import dev.wildware.udea.assets.Ability
import dev.wildware.udea.assets.AbilityDisplay
import dev.wildware.udea.assets.AbilitySpec
import dev.wildware.udea.assets.AnimNotify
import dev.wildware.udea.assets.AssetData
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.AssetValue
import dev.wildware.udea.assets.Axis2D
import dev.wildware.udea.assets.Axis2DBinding
import dev.wildware.udea.assets.Binding
import dev.wildware.udea.assets.BindingInput
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.assets.Character
import dev.wildware.udea.assets.ComponentSpec
import dev.wildware.udea.assets.Control
import dev.wildware.udea.assets.Effect
import dev.wildware.udea.assets.EffectDuration
import dev.wildware.udea.assets.EffectMagnitude
import dev.wildware.udea.assets.EntityDefinition
import dev.wildware.udea.assets.EntityTagName
import dev.wildware.udea.assets.GameConfig
import dev.wildware.udea.assets.GameplayEffect
import dev.wildware.udea.assets.GameplayTagName
import dev.wildware.udea.assets.Level
import dev.wildware.udea.assets.LightingConfig
import dev.wildware.udea.assets.ModifierKind
import dev.wildware.udea.assets.MovementType
import dev.wildware.udea.assets.NetworkConfig
import dev.wildware.udea.assets.PhysicsConfig
import dev.wildware.udea.assets.Ref
import dev.wildware.udea.assets.SoundCue
import dev.wildware.udea.assets.SpawnRecipe
import dev.wildware.udea.assets.SpriteAnimation
import dev.wildware.udea.assets.SpriteAnimationSet
import dev.wildware.udea.assets.SpriteSheet
import dev.wildware.udea.assets.TypeName
import dev.wildware.udea.assets.UiConfig
import dev.wildware.udea.assets.Vec2
import dev.wildware.udea.assets.uClass

/** Turns one packed record into the model type its kind names. */
public fun interface AssetCodec {

    public fun decode(fields: AssetFields): AssetData
}

/**
 * An asset whose kind this build has no type for.
 *
 * Not a failure mode - the *normal* outcome for a kind the game declares. `AssetData` is not
 * sealed precisely so a game can add kinds without editing this module, and a reader that threw
 * on the first unrecognised `kind` would make that impossible. The fields survive in full, refs
 * included and index-bound, so a game system can read its own asset without the engine knowing
 * anything about it.
 */
public data class OpaqueAsset(
    override val id: AssetId,
    /** The fully qualified name the bundle recorded, or a bare DSL word for an unpublishable kind. */
    public val kind: String,
    public val fields: Map<String, AssetValue>,
) : AssetData

/**
 * Kind name to codec.
 *
 * The engine's own kinds are in [Builtin]. A game adds its own with [plus], which is checked
 * rather than silently last-wins: two codecs for one kind is a mistake nobody would find at
 * runtime, because whichever lost would simply never be called.
 */
public class AssetCodecs private constructor(
    private val byKind: Map<String, AssetCodec>,
) {
    /** Kinds this registry decodes, sorted. Everything else becomes an [OpaqueAsset]. */
    public val kinds: List<String> = byKind.keys.sorted()

    public operator fun get(kind: String): AssetCodec? = byKind[kind]

    /** This registry plus [codecs]. Fails on a kind both sides claim. */
    public operator fun plus(codecs: Map<String, AssetCodec>): AssetCodecs {
        val clashes = codecs.keys.filter { it in byKind }.sorted()
        require(clashes.isEmpty()) {
            "these kinds already have a codec and would be silently overridden: $clashes"
        }
        return AssetCodecs(byKind + codecs)
    }

    override fun toString(): String = "AssetCodecs(${byKind.size} kinds)"

    public companion object {

        /** Every [AssetData] type declared in this module. */
        public val Builtin: AssetCodecs = AssetCodecs(
            buildMap<String, AssetCodec> {
                put(SpriteSheet::class) { fields ->
                    SpriteSheet(
                        id = fields.id,
                        texture = fields.path("texture"),
                        columns = fields.int("columns"),
                        rows = fields.int("rows"),
                        scale = fields.float("scale", 1.0F),
                    )
                }
                put(SpriteAnimation::class) { fields ->
                    SpriteAnimation(
                        id = fields.id,
                        sheet = fields.ref("sheet", SpriteSheet::class),
                        frameTime = fields.float("frameTime", DEFAULT_FRAME_TIME),
                        loop = fields.bool("loop", true),
                        interruptible = fields.bool("interruptible", true),
                        notifies = fields.list("notifies").map {
                            AnimNotify(frame = it.int("frame"), name = it.text("name"))
                        },
                    )
                }
                put(SpriteAnimationSet::class) { fields ->
                    SpriteAnimationSet(
                        id = fields.id,
                        animations = fields.refList("animations", SpriteAnimation::class),
                    )
                }
                put(SoundCue::class) { fields ->
                    SoundCue(
                        id = fields.id,
                        sounds = fields.pathList("sounds"),
                        pitchVariance = fields.float("pitchVariance", 0.0F),
                        volume = fields.float("volume", 1.0F),
                    )
                }
                put(Blueprint::class) { fields ->
                    Blueprint(
                        id = fields.id,
                        components = fields.list("components").map { it.componentSpec() },
                        tags = fields.textList("tags").map(::EntityTagName),
                        inheritedFrom = fields.textList("inheritedFrom").map(::AssetId),
                    )
                }
                put(Level::class) { fields ->
                    Level(
                        id = fields.id,
                        systems = fields.textList("systems").map { uClass<Any>(it) },
                        entities = fields.list("entities").map { entity ->
                            EntityDefinition(
                                name = entity.text("name", "Entity"),
                                blueprint = entity.refOrNull("blueprint", SpawnRecipe::class),
                                components = entity.list("components").map { it.componentSpec() },
                                tags = entity.textList("tags").map(::EntityTagName),
                                position = if ("position" in entity) {
                                    entity.vec("position", Vec2(0F, 0F))
                                } else {
                                    null
                                },
                            )
                        },
                    )
                }
                put(GameConfig::class) { fields ->
                    GameConfig(
                        id = fields.id,
                        defaultLevel = fields.refOrNull("defaultLevel", Level::class),
                        defaultCharacter = fields.refOrNull("defaultCharacter", SpawnRecipe::class),
                        backgroundTexture = fields.pathOrNull("backgroundTexture"),
                        network = NetworkConfig(
                            tcpPort = fields.int("tcpPort", NetworkConfig.DEFAULT_TCP_PORT),
                            udpPort = fields.int("udpPort", NetworkConfig.DEFAULT_UDP_PORT),
                        ),
                        physics = PhysicsConfig(gravity = fields.vec("gravity", DEFAULT_GRAVITY)),
                        lighting = if ("fboWidth" in fields) {
                            LightingConfig(
                                shadows = fields.bool("shadows", true),
                                ambientLight = fields.float("ambientLight", DEFAULT_AMBIENT),
                                fboWidth = fields.int("fboWidth"),
                                fboHeight = fields.int("fboHeight"),
                                blur = fields.bool("blur", true),
                                blurPasses = fields.int("blurPasses", DEFAULT_BLUR_PASSES),
                            )
                        } else {
                            null
                        },
                        ui = if ("defaultSkin" in fields) {
                            UiConfig(defaultSkin = fields.pathOrNull("defaultSkin"))
                        } else {
                            null
                        },
                        movementType = fields.movementType(),
                    )
                }
                put(Control::class) { fields -> Control(fields.id) }
                put(Axis2D::class) { fields -> Axis2D(fields.id) }
                put(Binding::class) { fields ->
                    Binding(
                        id = fields.id,
                        control = fields.ref("control", Control::class),
                        input = fields.bindingInput(),
                    )
                }
                put(Axis2DBinding::class) { fields ->
                    Axis2DBinding(
                        id = fields.id,
                        axis = fields.ref("axis", Axis2D::class),
                        input = fields.bindingInput(),
                        direction = fields.vec("direction", Vec2(0F, 1F)),
                    )
                }
                put(Ability::class) { fields ->
                    Ability(
                        id = fields.id,
                        exec = uClass(fields.text("exec")),
                        display = if ("displayName" in fields) {
                            AbilityDisplay(
                                name = fields.text("displayName"),
                                description = fields.text("description", ""),
                            )
                        } else {
                            null
                        },
                        blockedBy = fields.textList("blockedBy").map(::GameplayTagName),
                        tags = fields.textList("tags").map(::GameplayTagName),
                        range = if ("range" in fields) fields.float("range") else null,
                        blockAnimations = fields.bool("blockAnimations", false),
                        // The four the writer has always written and this codec used to drop on
                        // the floor. `GraphPacker.ability` emits `params`, `cooldown`, `costs` and
                        // `setByCaller`; nothing read them back, so an ability's whole tuning
                        // survived the round trip as bytes nobody could reach. The two reference
                        // fields are typed `GameplayEffect` now that the kind exists - `Ability`
                        // still declares them `Ref<*>`, so this is where the type token is chosen.
                        params = fields.record("params").toAssetValues(),
                        cooldown = fields.refOrNull("cooldown", GameplayEffect::class),
                        costs = fields.refList("costs", GameplayEffect::class),
                        setByCaller = fields.record("setByCaller").let { record ->
                            record.names.associate { GameplayTagName(it) to record.float(it) }
                        },
                    )
                }
                put(Character::class) { fields ->
                    Character(
                        id = fields.id,
                        size = fields.float("size", 1F),
                        health = fields.float("health", 100F),
                        animationSet = fields.refOrNull("animationSet", SpriteAnimationSet::class),
                        animations = fields.refMap("animations", SpriteAnimation::class),
                        sounds = fields.refMap("sounds", SoundCue::class),
                        attributes = fields.record("attributes").let { record ->
                            record.names.associateWith { record.float(it) }
                        },
                        components = fields.list("components").map { it.componentSpec() },
                        tags = fields.textList("tags").map(::EntityTagName),
                        abilities = fields.list("abilities").map { spec ->
                            AbilitySpec(
                                ability = spec.ref("ability", Ability::class),
                                tags = spec.textList("tags").map(::GameplayTagName),
                                level = spec.int("level", 1),
                            )
                        },
                    )
                }
                put(GameplayEffect::class) { fields ->
                    GameplayEffect(
                        id = fields.id,
                        duration = fields.effectDuration(),
                        target = fields.textOrNull("target"),
                        modifierType = fields.modifierKind(),
                        magnitude = fields.effectMagnitude(),
                        period = fields.float("period", 0F),
                        cues = fields.textList("cues"),
                        tags = fields.textList("tags").map(::GameplayTagName),
                    )
                }
                put(Effect::class) { fields ->
                    Effect(
                        id = fields.id,
                        animationSet = fields.ref("animationSet", SpriteAnimationSet::class),
                        animation = fields.text("animation"),
                        duration = fields.float("duration"),
                    )
                }
            },
        )

        // The model's own defaults, repeated because a bundle may omit a field the writer
        // found equal to its default. `DefaultFidelityTest` compares each of these against
        // the data class it mirrors, so a change to one side fails rather than drifts.
        private const val DEFAULT_FRAME_TIME = 0.1F
        private const val DEFAULT_AMBIENT = 0.5F
        private const val DEFAULT_BLUR_PASSES = 3
        private val DEFAULT_GRAVITY = Vec2(0F, -9.81F)

        /**
         * The movement type, by name, refusing an unknown one.
         *
         * `MovementType.valueOf` would throw `IllegalArgumentException` naming the enum but not
         * the asset, and this is exactly the field a bundle from a newer compiler grows a case
         * in - so it is worth the four lines to say which `gameConfig` could not be read.
         */
        /**
         * A `name -> Ref<T>` field, as [Character.animations] and [Character.sounds] hold it.
         *
         * A record and not a list of `{key, value}` pairs, because a role may only appear once and
         * a record is the encoding that says so - the same argument `GraphPacker.spriteAnimation`
         * makes for animation notifies going the other way.
         */
        private fun <T : AssetData> AssetFields.refMap(
            name: String,
            expected: kotlin.reflect.KClass<T>,
        ): Map<String, Ref<T>> {
            val record = record(name)
            return record.names.associateWith { record.ref(it, expected) }
        }

        /**
         * `instant()` / `infinite()` / `duration(tag)` as a flat discriminator pair.
         *
         * Flat for [bindingInput]'s reason, and refusing an unknown word for [movementType]'s: an
         * effect whose duration this build cannot read must not quietly become instant, because an
         * instant effect writes `base` and a duration effect does not - so the difference is a
         * permanently wrong stat rather than a wrong number for a while.
         */
        private fun AssetFields.effectDuration(): EffectDuration =
            when (val kind = text("durationKind", INSTANT)) {
                INSTANT -> EffectDuration.Instant
                INFINITE -> EffectDuration.Infinite
                SET_BY_CALLER -> EffectDuration.SetByCaller(GameplayTagName(text("durationTag")))
                else -> throw AssetDecodeException(
                    id.value,
                    "unknown effect duration kind '$kind'; this build knows " +
                        "'$INSTANT', '$INFINITE' and '$SET_BY_CALLER'",
                )
            }

        /** `setByCaller(tag)` / `attribute(name)`, or absent. */
        private fun AssetFields.effectMagnitude(): EffectMagnitude? =
            when (val kind = textOrNull("magnitudeKind")) {
                null -> null
                SET_BY_CALLER -> EffectMagnitude.SetByCaller(GameplayTagName(text("magnitudeTag")))
                ATTRIBUTE -> EffectMagnitude.Attribute(text("magnitudeAttribute"))
                else -> throw AssetDecodeException(
                    id.value,
                    "unknown magnitude kind '$kind'; this build knows '$SET_BY_CALLER' and " +
                        "'$ATTRIBUTE'",
                )
            }

        /** The modifier, by name, refusing an unknown one exactly as [movementType] does. */
        private fun AssetFields.modifierKind(): ModifierKind {
            val name = text("modifierType", ModifierKind.Additive.name)
            return ModifierKind.entries.firstOrNull { it.name == name }
                ?: throw AssetDecodeException(
                    id.value,
                    "unknown modifierType '$name'; this build knows " +
                        ModifierKind.entries.joinToString { it.name },
                )
        }

        private fun AssetFields.movementType(): MovementType {
            val name = text("movementType", MovementType.TopDown.name)
            return MovementType.entries.firstOrNull { it.name == name }
                ?: throw AssetDecodeException(
                    id.value,
                    "unknown movementType '$name'; this build knows " +
                        MovementType.entries.joinToString { it.name },
                )
        }

        private fun MutableMap<String, AssetCodec>.put(
            type: kotlin.reflect.KClass<out AssetData>,
            codec: AssetCodec,
        ) {
            val name = requireNotNull(type.qualifiedName) { "$type has no qualified name" }
            check(put(name, codec) == null) { "two codecs registered for '$name'" }
        }

        private fun AssetFields.componentSpec(): ComponentSpec = ComponentSpec(
            type = TypeName(text("type")),
            fields = AssetFields(id, (raw["fields"] as? RawValue.Fields)?.values.orEmpty(), ids, binder)
                .toAssetValues(),
        )

        /**
         * `key(42)` / `mouse(0)` as one struct-free pair of fields.
         *
         * A nested struct would have been the tidier encoding and is deliberately not used:
         * `BindingInput` is a two-case sealed interface, and a `kind` discriminator next to a
         * `code` costs one string in the table and makes a malformed binding say
         * "unknown binding input 'joystick'" rather than "field 'input' is a Text, not a
         * nested record".
         */
        private fun AssetFields.bindingInput(): BindingInput {
            val kind = text("inputKind")
            val code = int("inputCode")
            return when (kind) {
                KEY -> BindingInput.Key(code)
                MOUSE -> BindingInput.MouseButton(code)
                else -> throw AssetDecodeException(
                    id.value,
                    "unknown binding input kind '$kind'; this build knows '$KEY' and '$MOUSE'",
                )
            }
        }

        /** The `inputKind` discriminator for [BindingInput.Key]. Shared with the writer. */
        public const val KEY: String = "key"

        /** The `inputKind` discriminator for [BindingInput.MouseButton]. Shared with the writer. */
        public const val MOUSE: String = "mouse"

        /** `durationKind` for [EffectDuration.Instant]. Shared with the writer. */
        public const val INSTANT: String = "instant"

        /** `durationKind` for [EffectDuration.Infinite]. */
        public const val INFINITE: String = "infinite"

        /** `durationKind` and `magnitudeKind` for the caller-supplied case. */
        public const val SET_BY_CALLER: String = "setByCaller"

        /** `magnitudeKind` for [EffectMagnitude.Attribute]. */
        public const val ATTRIBUTE: String = "attribute"
    }
}
