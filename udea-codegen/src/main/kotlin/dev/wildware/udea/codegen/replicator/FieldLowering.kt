package dev.wildware.udea.codegen.replicator

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import dev.wildware.udea.codegen.CoreNames

/**
 * Decides how a `@Net`/`@Sim` property's type reaches a `FieldStore`.
 *
 * There are exactly two answers and one refusal, and the refusal is the point. The generator
 * this replaces had a third answer — `data.putSerializable(property)` for anything it did not
 * recognise — which turned an unsupported field into a per-tick allocation plus a full CBOR
 * encode, and turned a *wrong* field into a runtime failure on a machine that was not the
 * developer's. Here an unresolved type is a build failure with a reason.
 *
 * ## Direct
 *
 * `Boolean`, `Int`, `Long`, `Float`, an enum (as its ordinal), `NetId` and `Tick`. The last
 * two are spec 5's "treat `NetId` as a primitive field type": they have their own
 * `FieldStore` accessors rather than being smuggled through `setInt`/`setLong`, so an
 * entity-referencing field or a tick stamp round-trips with no special case in the store, in
 * `desync_report` or in the agent's field access.
 *
 * ## Lowered
 *
 * A composite **value** type is lowered to one field per component, with `fieldNames`
 * carrying the dotted path — `position.x`, `position.y`. That is the frozen contract
 * (`docs/contracts/replicator.md`, "Composite values are lowered"), and it is what keeps one
 * mask bit meaning one comparable value: a `Vector2` written as a single opaque field would
 * make a change in `x` and a change in `y` indistinguishable to `diff`, would break the
 * `fieldNames[i] == FieldMask bit i == FieldStore field i` alignment that `desync_report`
 * indexes with, and would put a boxed object in a columnar store.
 *
 * Lowering is deliberately **one level deep** and **structural**: a type qualifies when every
 * one of its accessible, non-static, declared properties is a `var` of a directly storable
 * type. That covers LibGDX's `Vector2`/`Vector3` and any hand-rolled equivalent without
 * `udea-codegen` — a build-time-only module — taking a dependency on the graphics library to
 * name them, and without a registry that a game's own vector type could not be added to.
 * A type whose components are themselves composite is refused rather than lowered to
 * `a.b.c`, because a name with two dots is a design that wants a flatter component.
 */
internal object FieldLowering {

    /** The outcome of asking how a type is stored. */
    sealed interface Result {

        /** The type occupies one field, held by [storage]. */
        data class Direct(
            val storage: FieldStorage,
            val type: ClassName,
            /** Non-null when [storage] is [FieldStorage.ENUM]; the class the ordinal decodes through. */
            val enumEntries: ClassName?,
            /** Non-null when [storage] is [FieldStorage.ENUM]; the constants in ordinal order. */
            val enumConstants: List<String>?,
        ) : Result

        /** The type lowers to one field per element of [components], in name order. */
        data class Composite(val components: List<Component>) : Result

        /**
         * The type cannot be replicated. [reason] completes the sentence
         * "…, which udea-codegen cannot replicate: <reason>", so it states the defect and
         * not the remedy — the remedy is the same for every reason and the caller appends it.
         */
        data class Unsupported(val reason: String) : Result
    }

    /** One lowered component of a composite value type. */
    data class Component(
        val name: String,
        val storage: FieldStorage,
        val type: ClassName,
        val enumEntries: ClassName?,
        val enumConstants: List<String>?,
    )

    /** How the directly storable types are spelled in a diagnostic. */
    val DIRECT_TYPE_NAMES: List<String> = listOf("Boolean", "Int", "Long", "Float", "NetId", "Tick")

    fun lower(type: KSType): Result {
        if (type.isMarkedNullable) {
            // A FieldStore column is a primitive array. There is no null in it, and inventing
            // a sentinel would make one legal value of the field mean "absent" on the wire.
            return Result.Unsupported("a nullable type has no representation in a columnar FieldStore")
        }
        directStorage(type)?.let { storage ->
            return Result.Direct(
                storage = storage,
                type = type.toClassName(),
                enumEntries = if (storage == FieldStorage.ENUM) type.toClassName() else null,
                enumConstants = if (storage == FieldStorage.ENUM) enumConstants(type) else null,
            )
        }
        return composite(type)
    }

    /**
     * The storage kind for a type held in one field, or `null` if it needs lowering.
     *
     * `NetId` and `Tick` are matched by fully-qualified name rather than by structure: they
     * are `udea-core` value classes with their own accessors, and a game type that happened
     * to wrap a single `Int` must not silently acquire `NetId`'s wire format.
     */
    private fun directStorage(type: KSType): FieldStorage? {
        val declaration = type.declaration
        return when (declaration.qualifiedName?.asString()) {
            "kotlin.Boolean" -> FieldStorage.BOOLEAN
            "kotlin.Int" -> FieldStorage.INT
            "kotlin.Long" -> FieldStorage.LONG
            "kotlin.Float" -> FieldStorage.FLOAT
            CoreNames.NET_ID_FQN -> FieldStorage.NET_ID
            CoreNames.TICK_FQN -> FieldStorage.TICK
            else -> if ((declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS) {
                FieldStorage.ENUM
            } else {
                null
            }
        }
    }

    /**
     * An enum's constants **in declaration order**, which is ordinal order and therefore wire
     * order: capture writes `.ordinal` and apply reads `entries[ordinal]`.
     *
     * They are recorded in `net-protocol.lock` for the same reason a field's bit width is.
     * Reordering two constants changes what every ordinal on the wire means while leaving the
     * bit layout untouched, so a lock that recorded only `enum:32` would hash identically
     * before and after — and the connect-time `protoHash` check would report agreement while
     * a server saying "crouching" made a client render "standing".
     */
    private fun enumConstants(type: KSType): List<String> =
        (type.declaration as? KSClassDeclaration)
            ?.declarations
            ?.filterIsInstance<KSClassDeclaration>()
            ?.filter { it.classKind == ClassKind.ENUM_ENTRY }
            ?.map { it.simpleName.asString() }
            ?.toList()
            .orEmpty()

    private fun composite(type: KSType): Result {
        val declaration = type.declaration as? KSClassDeclaration
            ?: return Result.Unsupported("only a class type can be lowered")
        if (declaration.classKind != ClassKind.CLASS) {
            val kind = declaration.classKind.name.lowercase().replace('_', ' ')
            return Result.Unsupported("only a class type can be lowered, and this is $kind")
        }
        if (declaration.typeParameters.isNotEmpty()) {
            return Result.Unsupported("a generic type cannot be lowered; its components' types are not known here")
        }
        if (Modifier.ABSTRACT in declaration.modifiers) {
            return Result.Unsupported("an abstract class cannot be lowered; its runtime components are not known here")
        }

        val properties = declaration.getDeclaredProperties()
            .filter { Modifier.JAVA_STATIC !in it.modifiers }
            .filter { it.isPublic() }
            .toList()
        if (properties.isEmpty()) {
            return Result.Unsupported("it declares no public property to lower to")
        }

        val components = ArrayList<Component>(properties.size)
        for (property in properties) {
            val name = property.simpleName.asString()
            if (!property.isMutable) {
                return Result.Unsupported(
                    "its property `$name` is a val, so Replicator.apply could not restore it",
                )
            }
            val componentType = property.type.resolve()
            if (componentType.isMarkedNullable) {
                return Result.Unsupported("its property `$name` is nullable")
            }
            val storage = directStorage(componentType)
                ?: return Result.Unsupported(
                    "its property `$name` is ${componentType.describe()}, which is not itself a " +
                        "storable field type; lowering is one level deep",
                )
            components += Component(
                name = name,
                storage = storage,
                type = componentType.toClassName(),
                enumEntries = if (storage == FieldStorage.ENUM) componentType.toClassName() else null,
                enumConstants = if (storage == FieldStorage.ENUM) enumConstants(componentType) else null,
            )
        }
        // Returned in declaration order and NOT sorted here: FieldOrder assigns every index in
        // the component from the full lowered name, so sorting again at this level would be a
        // second ordering rule that happens to agree with the first — and the day it stopped
        // agreeing, nothing would say which one the wire format followed. FieldOrder is the
        // single source of bit indices, and it is the only sort.
        return Result.Composite(components)
    }
}
