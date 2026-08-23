package dev.wildware.udea.assets

/**
 * The fully qualified name of a class the game declares: a component type, an ability
 * implementation, a system.
 *
 * Authored data names code, and this is the only way it is allowed to. The old tree resolved such
 * names with `Class.forName(className).kotlin` (`common/.../classes.kt`), which is reflection on a
 * load path and a `ClassNotFoundException` for a renamed class. Here the name is data, and turning
 * it into something callable is the loader's job, through generated registries and `ServiceLoader`
 * (standards section 1: discovery happens at build time).
 */
@JvmInline
public value class TypeName(public val value: String) {

    init {
        require(value.isNotBlank()) { "a TypeName must name a class; it was blank" }
        require(value.none { it.isWhitespace() }) { "a TypeName must not contain whitespace: '$value'" }
    }

    /** The `Health` of `dev.wildware.moba.Health`. */
    public val simpleName: String get() = value.substringAfterLast('.')

    override fun toString(): String = value
}

/**
 * A Fleks entity tag - the marker with no data that `Blueprint.tags` and `EntityDefinition.tags`
 * carry.
 *
 * A distinct type from [GameplayTagName] because they are distinct concepts that were both "a tag"
 * in the old tree: an entity tag is a component-shaped marker Fleks indexes families by, and a
 * gameplay tag is a GAS concept an ability blocks on. Passing one where the other is meant is a
 * defect the compiler can catch, so it does.
 */
@JvmInline
public value class EntityTagName(public val value: String) {

    init {
        require(value.isNotBlank()) { "an EntityTagName must name something; it was blank" }
    }

    override fun toString(): String = value
}

/**
 * A gameplay tag, in its authored form: `state.stunned`, `ability.fireball`.
 *
 * A *name*, not the interned integer GAS compares at runtime. That split is the layering: authored
 * data carries names, `udea-gas` interns them into ids once at load, and nothing on a per-tick path
 * ever compares strings.
 */
@JvmInline
public value class GameplayTagName(public val value: String) {

    init {
        require(value.isNotBlank()) { "a GameplayTagName must name something; it was blank" }
        require(value.none { it.isWhitespace() }) {
            "a GameplayTagName must not contain whitespace: '$value'"
        }
    }

    override fun toString(): String = value
}

/**
 * A phantom-typed class name: `UClass<AbilityExec>` is a [TypeName] the validator has checked names
 * a subtype of `AbilityExec`.
 *
 * The type argument buys nothing at runtime and everything at the authoring site, which is the same
 * trade [Ref] makes. The old `UClass.toKClass()` is deliberately absent: it was `Class.forName` on a
 * load path, and this module resolves nothing.
 */
@JvmInline
public value class UClass<out T : Any>(public val type: TypeName) {

    override fun toString(): String = type.value
}

/** `UClass<T>` from a fully qualified name. */
public fun <T : Any> uClass(className: String): UClass<T> = UClass(TypeName(className))
