package dev.wildware.spellcastgame

import box2dLight.PointLight
import com.badlogic.gdx.Input
import com.badlogic.gdx.Input.Keys
import com.badlogic.gdx.assets.AssetManager
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Sprite
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.physics.box2d.BodyDef
import com.badlogic.gdx.physics.box2d.CircleShape
import com.badlogic.gdx.physics.box2d.PolygonShape
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.utils.viewport.ExtendViewport
import com.github.quillraven.fleks.Entity
import dev.wildware.*
import dev.wildware.Binding.BindingInput.Key
import dev.wildware.Binding.BindingInput.Mouse
import dev.wildware.ability.*
import dev.wildware.ability.ModifierType.Additive
import dev.wildware.ecs.Blueprint
import dev.wildware.ecs.blueprint
import dev.wildware.ecs.component.*
import dev.wildware.ecs.newInstance
import dev.wildware.ecs.system.BackgroundDrawSystem
import dev.wildware.ecs.system.Box2DSystem
import dev.wildware.screen.UIScreen
import dev.wildware.spellcastgame.ability.DivineHealCue
import dev.wildware.spellcastgame.screen.MainMenu
import dev.wildware.spellcastgame.screen.PlayScreen
import dev.wildware.spellcastgame.screen.Runepouch
import dev.wildware.spellcastgame.screen.widget.SpellHotbar
import dev.wildware.spellcastgame.spell.*
import dev.wildware.spellcastgame.spell.Spells.castSpell
import dev.wildware.spellcastgame.spell.modifiers.*
import dev.wildware.udea.assets.Assets
import ktx.app.KtxGame
import ktx.app.KtxScreen
import ktx.assets.getAsset
import ktx.assets.load
import ktx.assets.toInternalFile
import ktx.async.KtxAsync
import ktx.box2d.BodyDefinition
import ktx.box2d.FixtureDefinition
import ktx.box2d.body
import ktx.box2d.box
import ktx.scene2d.Scene2DSkin
import ktx.scene2d.actors
import ktx.scene2d.table
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

lateinit var gameScreen: MainGame

class SpellCastGame : KtxGame<KtxScreen>() {
    override fun create() {
        Scene2DSkin.defaultSkin = Skin("skin/glassyui/glassy-ui.json".toInternalFile())
        KtxAsync.initiate()

        addScreen(PlayScreen(this))
        addScreen(Runepouch(this))

        addScreen(MainMenu(this))
        setScreen<MainMenu>()
    }
}


class MainGame(
    worldSource: WorldSource
) : UIScreen() {

    var localPlayer: Entity? = null
    val spellHotbar = SpellHotbar()

    val game = game(worldSource) {
        assets {
            load<Texture>("wizard.png")
            load<Texture>("fireball.png")
            load<Texture>("target_dummy.png")
            load<Texture>("background.png")
            load<Texture>("foreground.png")
            load<Texture>("earth.png")
        }

        world {
            preSystems {
                add(BackgroundDrawSystem())
            }

            postSystems {
                add(SpellSystem())
                add(HealthbarRenderSystem())
                add(UISystem())
            }
        }

        init {
            if (isServer) {
                localPlayer = Assets[Blueprint]["player"].newInstance(world).apply {
                    with(world) {
                        this@apply[AbilitiesComponent].apply {
                            world.applyGameplayEffect(
                                Entity(0, 0u),
                                Entity(0, 0u),
                                GameplayEffectSpec(Assets[GameplayEffect]["healthRegen"])
                            )
                        }
                    }
                }
                Assets[Blueprint]["target_dummy"].newInstance(world)
            }

            Assets[Blueprint]["platform"].newInstance(world)

            world.system<Box2DSystem>().box2DWorld.body {
                box(20F, 5F, position = Vector2(0.0F, -5.0F)) {
                    density = 10.0F
                }
            }
        }

        stage.actors {
            table {
                setFillParent(true)
                debug()
                add(spellHotbar).expand().top().left()
            }
        }
    }

    init {
        with(game.assetManager) {
            registerBlueprints()
            registerControls()
            registerGameplayEffects()
            registerAbilities()
            registerRunes()
            registerSpellElements()
        }
    }

    override fun show() {
        super.show()
        game.init()
    }

    override fun render(delta: Float) {
        game.update(delta)
        super.render(delta)
    }

    override fun dispose() {
        game.dispose()
    }

    override fun resize(width: Int, height: Int) {
        viewPort.update(width, height, true)
    }

    companion object {
        val viewPort = ExtendViewport(19.20F, 10.80F)
        val camera = viewPort.camera
    }
}

fun AssetManager.registerBlueprints() {
    blueprint("platform") {
        it += RenderSprite(
            Sprite(getAsset<Texture>("foreground.png")).apply { resizeScale(0.2F) },
            order = -100
        )

        it[Transform].run {
            scale.set(0.1F, 0.1F)
            position.set(0.0F, .6F)
        }
    }

    blueprint("player") {
        it += RenderSprite(Sprite(getAsset<Texture>("wizard.png")).apply { resizeScale(0.0025F) })
        it += PlayerController()
        it += Controller()
        it += Networkable()
        it += Particles()
        it += CameraTrack(Vector2(0.0F, 4.0F))
        it += Box2DLight(PointLight(game.rayHandler, 128, Color(0.1F, 0.1F, 0.1F, 1.0F), 8F, 0F, 0F))
        it += SpellHolderComponent(SpellLoader.loadAllSpells())

        it += RigidBodyComponent(BodyDefinition().apply {
            type = BodyDef.BodyType.DynamicBody
            linearDamping = 1.0F

        }, FixtureDefinition().apply {
            shape = PolygonShape().apply { setAsBox(0.1F, 0.2F) }
            friction = 0.0F
        })

        it += AbilitiesComponent(CharacterAttributeSet())

        it[AbilitiesComponent].run {
            giveAbility(Assets[Ability]["spell"])
        }
    }

    blueprint("spell") {
        it += Networkable(0)
        it += RigidBodyComponent(BodyDefinition().apply {
            type = BodyDef.BodyType.DynamicBody
            linearDamping = 0.0F

        }, FixtureDefinition().apply {
            shape = CircleShape().apply { radius = .1F }
            friction = 0.0F
            isSensor = true
        })
        it += Box2DLight(PointLight(game.rayHandler, 64, Color.GRAY, 5F, 0F, 0F))

        it += Particles()
    }

    blueprint("earth_spell", extends = "spell") {
        it += RenderSprite(Sprite(getAsset<Texture>("earth.png")).apply { resizeScale(0.01F) })
    }

    blueprint("target_dummy") {
        it += Networkable(0)
        it += Particles()
        it += AbilitiesComponent(CharacterAttributeSet())
        it += RenderSprite(Sprite(getAsset<Texture>("target_dummy.png")).apply { resizeScale(0.01F) })
        it += RigidBodyComponent(BodyDefinition().apply {
            type = BodyDef.BodyType.DynamicBody
            linearDamping = 1.0F

        }, FixtureDefinition().apply {
            shape = PolygonShape().apply { setAsBox(0.1F, 0.2F) }
            friction = 0.0F
        })
    }
}

fun registerGameplayEffects() {
    gameplayEffect(
        name = "healthRegen",
        target = CharacterAttributeSet::health,
        modifierType = Additive,
        source = ValueResolver.ConstantValue(1.0F),
        effectDuration = GameplayEffectDuration.Infinite,
        period = 1.seconds
    )

    gameplayEffect(
        name = "damage",
        target = CharacterAttributeSet::health,
        modifierType = Additive,
        source = ValueResolver.ConstantValue(-1.0F),
        effectDuration = GameplayEffectDuration.Instant,
    )

    gameplayEffect(
        name = "heal",
        target = CharacterAttributeSet::health,
        modifierType = Additive,
        source = ValueResolver.ConstantValue(1.0F),
        effectDuration = GameplayEffectDuration.Instant,
        cues = listOf(DivineHealCue)
    )
}

fun registerAbilities() {
    ability("spell") {
        if (game.isServer) {
            val heading = (it.targetPos.cpy().sub(it.source[Transform].position)).nor()

            val spell = it.source[SpellHolderComponent].selectedSpell ?: return@ability

            castSpell(
                Assets[SpellElement]["fire"],
                spell.runes.copyRunes(),
                it.source,
                it.source[Transform].position.cpy()
                    .add(heading.cpy().rotateDeg(Random.nextInt(-5, 5).toFloat()).scl(.05F)),
                heading,
                it.target?.getOrNull(Transform)?.position
            )
        }
    }
}

fun registerControls() {
//    control("jump")
//    control("left")
//    control("right")
//    control("cast_spell")
//
//    control("select_spell_1")
//    control("select_spell_2")
//    control("select_spell_3")
//    control("select_spell_4")
//    control("select_spell_5")
//    control("select_spell_6")
//    control("select_spell_7")
//    control("select_spell_8")
//    control("select_spell_9")
//    control("select_spell_10")
//
//    binding(Assets[Control]["jump"], Key(Keys.SPACE))
//    binding(Assets[Control]["left"], Key(Keys.A))
//    binding(Assets[Control]["right"], Key(Keys.D))
//    binding(Assets[Control]["cast_spell"], Mouse(Input.Buttons.LEFT))
//
//    binding(Assets[Control]["select_spell_1"], Key(Keys.NUM_1))
//    binding(Assets[Control]["select_spell_2"], Key(Keys.NUM_2))
//    binding(Assets[Control]["select_spell_3"], Key(Keys.NUM_3))
//    binding(Assets[Control]["select_spell_4"], Key(Keys.NUM_4))
//    binding(Assets[Control]["select_spell_5"], Key(Keys.NUM_5))
//    binding(Assets[Control]["select_spell_6"], Key(Keys.NUM_6))
//    binding(Assets[Control]["select_spell_7"], Key(Keys.NUM_7))
//    binding(Assets[Control]["select_spell_8"], Key(Keys.NUM_8))
//    binding(Assets[Control]["select_spell_9"], Key(Keys.NUM_9))
//    binding(Assets[Control]["select_spell_10"], Key(Keys.NUM_0))
}

fun registerRunes() {
    rune("cluster", "Cluster", "Splits into 3 projectiles.", ClusterModifier)
    rune("alternate", "Alternate", "Alternates between two runes.", AlternateModifier)
    rune("curl", "Curl", "Curls round in a tight circle.", CurlModifier)
    rune("curve", "Curve", "Curves round slightly.", CurveModifier)
    rune("delay", "Delay", "Activates child modifiers after a delay.", DelayModifier)
    rune("orbit", "Orbit", "Spell orbits the target.", OrbitModifier)
    rune("ramping", "Ramping", "Spell grows larger", RampingSizeModifier)
    rune("shotgun", "Shotgun", "Splits spell into 10 short range, high damage projectiles.", ShotgunModifier)
    rune("homing", "Homing", "Spell homing to target.", TargetHomingModifier)
    rune("explode", "Explode", "Spell explodes into many smaller spells.", ExplodeModifier)
    rune("transmute", "Transmute", "Transmutes this spell into another element", TransmuteModifier)
    rune("random", "Random", "Randomly applies one child rune.", RandomModifier)
    rune("size", "Size", "Changes the size of the spell.", SizeModifier)
    rune("temporary", "Temporary", "Applies rune to spell until delay.", TempModifier)
    rune("wave", "Wave", "Spell moves in a wave shape.", WaveModifier)
    rune("chaos", "Chaos", "Spell moves chaotically", ChaosModifier)
    rune("trail", "Trail", "Creates a trail of smaller spells", TrailModifier)
}

fun registerSpellElements() {
    spellElement("fire", false, 7.0F, 1.2F, FIRE_PARTICLE, Assets[GameplayEffect]["damage"])
    spellElement("earth", true, 5.0F, 2.0F, FIRE_PARTICLE, Assets[GameplayEffect]["damage"])
    spellElement("water", true, 7.0F, 2.0F, FIRE_PARTICLE, Assets[GameplayEffect]["damage"])
    spellElement("air", false, 12.0F, 1.0F, FIRE_PARTICLE, Assets[GameplayEffect]["damage"])
    spellElement(
        "arcane",
        false,
        10.0F,
        1.0F,
        ARCANE_PARTICLE,
        Assets[GameplayEffect]["damage"],
        Color(0.1F, 0.0F, 0.2F, 1.0F)
    )
    spellElement(
        "divine",
        false,
        5.0F,
        1.0F,
        DIVINE_PARTICLE,
        Assets[GameplayEffect]["heal"],
        Color(0.5F, 0.5F, 0.1F, 0.1F)
    )
    spellElement("blood", true, 7.0F, 1.0F, BLOOD_PARTICLE, Assets[GameplayEffect]["damage"])
    spellElement("void", false, 5.0F, 0.1F, FIRE_PARTICLE, Assets[GameplayEffect]["damage"])
}

fun Sprite.resizeScale(scale: Float) {
    this.setSize(this.width * scale, this.height * scale)
}
