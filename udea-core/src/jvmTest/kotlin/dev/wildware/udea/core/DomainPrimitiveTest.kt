package dev.wildware.udea.core

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.replication.ComponentTypeId
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Charter §1: a domain concept is a value class, never a bare `Int`, `Long` or `String`.
 *
 * The rule is cheap to state and easy to lose: `Cue` carried `id: Int` beside a `Tick` and a
 * `NetId` in the same constructor, and `SceneManager` took a raw `String` — the literal shape
 * of `Assets["character/orc"]` that §1 names as the smell being killed. These are all public
 * kernel signatures other modules will compile against, so the cost of a regression rises
 * with every module that lands; this fails the moment one is widened back to a primitive.
 */
class DomainPrimitiveTest {

    @Test
    fun `every kernel identity is a value class over its storage`() {
        assertValueClass(Tick::class, Long::class)
        assertValueClass(NetId::class, Int::class)
        assertValueClass(CueId::class, Int::class)
        assertValueClass(SceneId::class, String::class)
        assertValueClass(ComponentTypeId::class, Int::class)
    }

    @Test
    fun `the signatures that carry an identity name the identity, not its storage`() {
        assertEquals(CueId::class, propertyType(Cue::class, "id"))
        assertEquals(Tick::class, propertyType(Cue::class, "tick"))
        assertEquals(NetId::class, propertyType(Cue::class, "source"))
        assertEquals(SceneId::class, propertyType(SceneManager::class, "activeSceneId"))

        val requestScene = SceneManager::class.declaredMemberFunctions.single { it.name == "requestScene" }
        assertEquals(
            listOf(SceneId::class),
            requestScene.parameters.drop(1).map { it.type.classifier },
            "requestScene(String) is `Assets[\"character/orc\"]` with extra steps",
        )
    }

    @Test
    fun `an identity rejects a value that could not be one`() {
        // The wrapper is not typo detection — SceneId("levle_1") still compiles, and the
        // did-you-mean diagnostic comes from build-time asset validation. What it can do is
        // refuse a value no scene or type id could ever have.
        assertFailsWith<IllegalArgumentException> { SceneId("") }
        assertFailsWith<IllegalArgumentException> { ComponentTypeId(-1) }
    }

    private fun assertValueClass(type: KClass<*>, storage: KClass<*>) {
        assertTrue(type.isValue, "${type.simpleName} must be a value class (charter 1)")
        val backing = checkNotNull(type.primaryConstructor).parameters.single()
        assertEquals(
            storage,
            backing.type.classifier,
            "${type.simpleName} must wrap exactly one ${storage.simpleName}",
        )
    }

    private fun propertyType(owner: KClass<*>, name: String): KClass<*>? =
        owner.memberProperties.single { it.name == name }.returnType.classifier as? KClass<*>
}
