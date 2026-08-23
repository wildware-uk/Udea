package dev.wildware.udea.gas

import dev.wildware.udea.core.Tick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Ids come from sorted names, so two builds agree whatever order their modules were discovered in.
 *
 * This is the runtime half of issue #96. The KSP processor that emits the table and the
 * `attribute-ids.lock` CI diff live in `udea-codegen`, which is not this module's to write; what is
 * this module's is the id *assignment rule* and the lock file's text, both of which are exercised
 * here.
 */
class AttributeTableTest {

    private fun engineModule() = object : AttributeModule {
        override val moduleName: String get() = "engine"
        override fun attributes(): List<AttributeDecl> = listOf(
            AttributeDecl("engine.Unit.health", defaultBase = 100f),
            AttributeDecl("engine.Unit.mana", defaultBase = 50f),
        )
    }

    private fun gameModule() = object : AttributeModule {
        override val moduleName: String get() = "game"
        override fun attributes(): List<AttributeDecl> = listOf(
            AttributeDecl("game.Champion.armour", defaultBase = 12f),
            AttributeDecl("game.Champion.critChance", defaultBase = 0.05f),
            AttributeDecl("game.Champion.lifesteal"),
        )
    }

    @Test
    fun `merging two modules in either order produces identical ids`() {
        val forwards = AttributeTable.of(listOf(engineModule(), gameModule()))
        val backwards = AttributeTable.of(listOf(gameModule(), engineModule()))

        assertEquals(forwards.count, backwards.count)
        for (index in 0 until forwards.count) {
            val id = AttributeId(index)
            assertEquals(forwards.nameOf(id), backwards.nameOf(id), "id $index disagrees")
        }
        assertEquals(forwards.render(), backwards.render(), "the lock file must be order-independent")
    }

    @Test
    fun `a game module gets ids without any engine-side edit`() {
        val engineOnly = AttributeTable.of(listOf(engineModule()))
        assertEquals(2, engineOnly.count)

        val withGame = AttributeTable.of(listOf(engineModule(), gameModule()))
        assertEquals(5, withGame.count)
        assertTrue(withGame.idOf("game.Champion.armour").index >= 0)
        assertTrue(withGame.idOf("game.Champion.lifesteal").index >= 0)
        assertEquals("game.Champion.critChance", withGame.nameOf(withGame.idOf("game.Champion.critChance")))
    }

    @Test
    fun `two attributes with one name is a failure that names both owners`() {
        val clashing = object : AttributeModule {
            override val moduleName: String get() = "clash"
            override fun attributes(): List<AttributeDecl> = listOf(
                AttributeDecl("engine.Unit.health", defaultBase = 1f),
            )
        }
        val failure = assertFailsWith<DuplicateAttributeException> {
            AttributeTable.of(listOf(engineModule(), clashing))
        }
        assertEquals("engine.Unit.health", failure.name)
        assertEquals("engine", failure.firstOwner)
        assertEquals("clash", failure.secondOwner)
    }

    @Test
    fun `the lock file is stable diffable text`() {
        val rendered = AttributeTable.of(listOf(engineModule())).render()
        assertEquals(
            "0 engine.Unit.health owner=engine default=${100f.toRawBits()} replicated=true\n" +
                "1 engine.Unit.mana owner=engine default=${50f.toRawBits()} replicated=true\n",
            rendered,
        )
    }

    @Test
    fun `an unknown attribute name fails loudly and says what it knows`() {
        val table = AttributeTable.of(listOf(engineModule()))
        val failure = assertFailsWith<NoSuchAttributeException> { table.idOf("engine.Unit.helth") }
        assertTrue(failure.message!!.contains("engine.Unit.health"), failure.message!!)
        assertEquals(AttributeId.NONE, table.idOrNone("engine.Unit.helth"))
    }

    @Test
    fun `a fresh base array holds every declared default`() {
        val table = AttributeTable.of(listOf(engineModule(), gameModule()))
        val base = table.newBaseArray()
        assertEquals(100f, base[table.idOf("engine.Unit.health").index])
        assertEquals(12f, base[table.idOf("game.Champion.armour").index])
        assertEquals(0f, base[table.idOf("game.Champion.lifesteal").index])
    }
}

/**
 * An attribute with default bounds can hold a negative value.
 *
 * Reproduces and fixes `ValueResolver.Min = ConstantValue(Float.MIN_VALUE)`
 * (`common/ability/Attributes.kt:59`). `Float.MIN_VALUE` is the smallest *positive* float, so the
 * default lower bound was about `1.4e-45` and every attribute silently clamped just above zero: a
 * damage effect that should have pushed health to -20 left it at ~0, and an armour debuff could
 * not make armour negative at all.
 */
class AttributeClampDefaultTest {

    @Test
    fun `the default lower bound is the most negative float, not the smallest positive one`() {
        assertEquals(-Float.MAX_VALUE, (ValueResolver.MIN as ValueResolver.Constant).value)
        assertTrue(Float.MIN_VALUE > 0f, "the defect exists because Float.MIN_VALUE is positive")
    }

    @Test
    fun `an attribute with default clamps holds a negative value`() {
        val fixture = GasFixture()
        val unit = fixture.unit()
        unit.attributes.setBase(fixture.health, 10f)

        unit.apply(fixture.damageEffect, Tick.ZERO, fixture.damageTag to -30f)
        unit.recompute(Tick.ZERO)

        assertEquals(-20f, unit.attributes.base(fixture.health), "damage past zero must go past zero")
        assertEquals(-20f, unit.attributes.current(fixture.health))
    }

    @Test
    fun `a declared bound is still enforced`() {
        val table = AttributeTableBuilder().apply {
            add(
                AttributeDecl("game.Unit.health", defaultBase = 10f, min = value(0f), max = value(20f)),
                "game",
            )
        }.build()
        val id = table.idOf("game.Unit.health")
        val effects = GameplayEffectTable.of(
            listOf(
                GameplayEffectDef(
                    name = "damage",
                    target = id,
                    magnitude = value(-100f),
                    duration = GameplayEffectDuration.Instant,
                    tags = TagSet(0),
                ),
            ),
        )
        val handles = HandleAllocator()
        val applier = EffectApplier(effects, handles, GasCueQueue())
        val recompute = AttributeRecompute(effects, table, handles)

        val attributes = Attributes(table)
        val applied = GameplayEffects()
        applier.begin(0).applyTo(applied, attributes, Tick.ZERO)
        recompute.recompute(attributes, applied, Tick.ZERO)

        assertEquals(0f, attributes.base(id), "a declared min of zero must still clamp at zero")
    }

    @Test
    fun `a cross-attribute clamp survives as a per-id hook`() {
        var clampCalls = 0
        // Sorted names put `health` at 0 and `maxHealth` at 1. The clamp is what
        // `AttributeSet.preAttributeChanged` was for: health may not exceed max health, which no
        // constant min or max can express because the ceiling is another attribute.
        val table = AttributeTableBuilder().apply {
            add(AttributeDecl("game.Unit.maxHealth", defaultBase = 80f), "game")
            add(
                AttributeDecl(
                    "game.Unit.health",
                    defaultBase = 100f,
                    clamp = AttributeClamp { _, currentValue, all ->
                        clampCalls++
                        minOf(currentValue, all[1])
                    },
                ),
                "game",
            )
        }.build()
        val healthId = table.idOf("game.Unit.health")
        assertEquals(0, healthId.index, "sorted names put health first; the clamp reads index 1 for max")

        val effects = GameplayEffectTable.of(
            listOf(GameplayEffectDef("noop", duration = GameplayEffectDuration.Infinite, tags = TagSet(0))),
        )
        val recompute = AttributeRecompute(effects, table, HandleAllocator())
        val attributes = Attributes(table)
        recompute.recompute(attributes, GameplayEffects(), Tick.ZERO)

        assertEquals(1, clampCalls, "the preAttributeChanged hook must still run, once per tick")
        assertEquals(80f, attributes.current(healthId), "health is clamped to maxHealth, not to its base")
        assertEquals(100f, attributes.base(healthId), "the clamp shapes current, it does not rewrite base")
    }
}
