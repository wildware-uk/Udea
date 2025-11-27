import com.badlogic.gdx.physics.box2d.BodyDef.BodyType.KinematicBody
import dev.wildware.udea.ecs.component.physics.body
import dev.wildware.udea.ecs.component.physics.box
import dev.wildware.udea.ecs.component.render.loadSprite
import dev.wildware.udea.ecs.component.render.spriteRenderer
import dev.wildware.udea.example.ability.Damage
import dev.wildware.udea.example.ability.Data
import dev.wildware.udea.example.ability.onHitEffect
import dev.wildware.udea.example.component.projectile
import dev.wildware.udea.example.component.team

blueprint(
    components = lazy {
        body(
            type = KinematicBody
        )

        box(
            width = 0.2F,
            height = 0.1F,
            isSensor = true
        )

        // TODO you have broke ListBuilder<AssetReference>
        projectile(
            onHitEffects = {
                onHitEffect(
                    reference("ability/damage"),
                    setByCallerMagnitudes = mapOf(
                        Data.Damage to -10F
                    ),
                    tags = {
                        add(Damage.Physical)
                    }
                )
                onHitEffect(
                    reference("ability/knockback"),
                    setByCallerMagnitudes = mapOf(
                        Data.Knockback to .2F
                    )
                )

                onHitEffect(
                    reference("ability/knockback"),
                    setByCallerMagnitudes = mapOf(
                        Data.StunDuration to .2F
                    )
                )
            }
        )

        team()

        spriteRenderer(
            texture = loadSprite("/sprites/arrow/arrow.png", .1F)
        )
    }
)
