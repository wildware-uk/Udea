package dev.wildware.udea.codegen.replicator

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import dev.wildware.udea.codegen.AnnotationNames
import dev.wildware.udea.diagnostics.DidYouMean
import dev.wildware.udea.diagnostics.UdeaRules

/**
 * Turns a `@Replicated` class declaration into a [ReplicatedComponent], or reports why it
 * cannot and returns `null`.
 *
 * **The failure policy is the reason this class exists.** The generator this replaces wrapped
 * each symbol in `catch (e: Exception)` and turned a component it could not handle into a log
 * line plus a silently missing serializer — a component that then failed to replicate at
 * runtime with no build-time trace. Here:
 *
 * - there is no `catch`;
 * - every diagnostic is [KSPLogger.error] **at the offending symbol**, which fails the build;
 * - a component with any error emits **no file at all**, rather than a partial one.
 *
 * Errors that a K2 FIR checker will also raise (spec 3.2) report the same stable rule id from
 * `udea-diagnostics`, so the two producers cannot drift apart (spec 5, "Diagnostics").
 */
internal class ComponentModelBuilder(private val logger: KSPLogger) {

    /**
     * One field before it has an index or a constant name.
     *
     * Indices are handed out by [FieldOrder] over the *whole* lowered set, so they cannot be
     * assigned while walking properties: a composite property contributes several names that
     * interleave with other properties' names in the sort.
     */
    private data class Candidate(
        val path: List<String>,
        val net: Boolean,
        val storage: FieldStorage,
        val declaredType: ClassName,
        val enumEntries: ClassName?,
        val enumConstants: List<String>?,
        val quantisation: Quantisation?,
        val createOnly: Boolean,
    ) {
        val name: String = path.joinToString(".")
    }

    fun build(declaration: KSClassDeclaration): ReplicatedComponent? {
        var failed = false

        val qualifiedName = declaration.qualifiedName?.asString()
        if (qualifiedName == null) {
            logger.error("@Replicated is only supported on a named, top-level or nested class", declaration)
            return null
        }
        if (declaration.classKind != ClassKind.CLASS) {
            logger.error(
                "@Replicated is only supported on a class, but ${declaration.simpleName.asString()} " +
                    "is ${declaration.classKind.name.lowercase().replace('_', ' ')}",
                declaration,
            )
            failed = true
        }

        val annotated = declaration.getDeclaredProperties()
            .filter { it.hasAnnotation(AnnotationNames.NET) || it.hasAnnotation(AnnotationNames.SIM) }
            .toList()

        val candidates = ArrayList<Candidate>(annotated.size)
        for (property in annotated) {
            if (!describe(declaration, property, candidates)) failed = true
        }

        // Counted **after** lowering, because that is what the mask actually addresses: a
        // component with 33 Vector2 properties declares 33 things and needs 66 bits.
        if (candidates.size > UdeaRules.MAX_COMPONENT_FIELDS) {
            // Deliberately not a truncation and not a widening: one FieldMask addresses 64
            // fields, and the fix a developer can actually take is to split the component.
            logger.error(
                "${UdeaRules.COMPONENT_FIELD_LIMIT.id}: $qualifiedName declares ${candidates.size} " +
                    "@Net/@Sim fields, but a field mask addresses at most " +
                    "${UdeaRules.MAX_COMPONENT_FIELDS}. SPLIT the component into two or more " +
                    "components of at most ${UdeaRules.MAX_COMPONENT_FIELDS} fields each; " +
                    "there is no way to widen the mask for one component.",
                declaration,
            )
            failed = true
        }

        if (failed) return null

        val ordered = FieldOrder.assign(candidates, Candidate::name)
        val constants = FieldOrder.constantNames(ordered.map(Candidate::name))
        val fields = ordered.mapIndexed { index, candidate ->
            ReplicatedField(
                path = candidate.path,
                constant = constants[index],
                index = index,
                net = candidate.net,
                storage = candidate.storage,
                declaredType = candidate.declaredType,
                enumEntries = candidate.enumEntries,
                enumConstants = candidate.enumConstants,
                quantisation = candidate.quantisation,
                createOnly = candidate.createOnly,
            )
        }
        return ReplicatedComponent(
            className = declaration.toClassName(),
            qualifiedName = qualifiedName,
            fields = fields,
        )
    }

    /** Appends this property's fields to [out]; returns `false` if it reported an error. */
    private fun describe(
        owner: KSClassDeclaration,
        property: KSPropertyDeclaration,
        out: MutableList<Candidate>,
    ): Boolean {
        val ownerName = owner.qualifiedName?.asString() ?: owner.simpleName.asString()
        val propertyName = property.simpleName.asString()
        val net = property.hasAnnotation(AnnotationNames.NET)
        var failed = false

        if (net && property.hasAnnotation(AnnotationNames.SIM)) {
            // The two masks are exclusive by definition: `@Sim` means "snapshotted but never
            // replicated". Accepting both and letting `@Net` win makes an attempted demotion a
            // silent no-op, and it fails in the leaking direction — the field the developer
            // meant to stop sending keeps reaching clients with a green build. Spec 3.1 puts
            // jungle respawn timers and bot blackboards on the wrong side of that.
            //
            // No rule id: `udea-diagnostics` registers none for this defect, and this module
            // does not fabricate one. Same shape as the `@Sim`-on-a-val message below.
            logger.error(
                "$ownerName.$propertyName carries both @Net and @Sim, which are mutually " +
                    "exclusive: @Net is replicated and snapshotted, @Sim is snapshotted only. " +
                    "Delete @Sim to keep replicating it, or delete @Net to stop it reaching " +
                    "clients.",
                property,
            )
            failed = true
        }

        val type = property.type.resolve()

        // Issue #114. Declared since Phase 0 and read by nothing until now, which made the
        // annotation decorative: a team id set at spawn rode a delta on every tick that
        // capture-and-diff happened to see it move. `udea-net`'s `LifetimePolicy` already
        // refuses to put such a field in an `Update`; what was missing is any generated
        // replicator ever *saying* it has one.
        val createOnly = net && property.lifetimeIsOnCreate()

        val quantisation = if (property.hasAnnotation(AnnotationNames.Q)) {
            if (!type.isFloat()) {
                logger.error(
                    "${UdeaRules.QUANTIZED_NON_FLOAT.id}: @Q annotates $ownerName.$propertyName, " +
                        "which is ${type.describe()}, not Float. Quantization is only defined for " +
                        "floats.",
                    property,
                )
                failed = true
                null
            } else {
                readQuantisation(property, ownerName, propertyName).also { if (it == null) failed = true }
            }
        } else {
            null
        }

        when (val lowering = FieldLowering.lower(type)) {
            is FieldLowering.Result.Direct -> {
                if (!property.isMutable) {
                    reportVal(property, ownerName, propertyName, net)
                    failed = true
                }
                if (!failed) {
                    out += Candidate(
                        path = listOf(propertyName),
                        net = net,
                        storage = lowering.storage,
                        declaredType = lowering.type,
                        enumEntries = lowering.enumEntries,
                        enumConstants = lowering.enumConstants,
                        quantisation = quantisation,
                        createOnly = createOnly,
                    )
                }
            }

            is FieldLowering.Result.Composite -> {
                // The property itself may be a `val`: `apply` restores a composite by writing
                // its components in place, which is what preserves the vector's identity for
                // whatever holds a reference to it (spec 3.1, "apply mutates in place"). Only
                // the *components* have to be assignable, and FieldLowering already required
                // that before calling this a Composite.
                if (!failed) {
                    for (component in lowering.components) {
                        out += Candidate(
                            path = listOf(propertyName, component.name),
                            net = net,
                            storage = component.storage,
                            declaredType = component.type,
                            enumEntries = component.enumEntries,
                            enumConstants = component.enumConstants,
                            quantisation = null,
                            // The lifetime is declared on the property, and a composite is
                            // lowered to one field per component, so every component of a
                            // `lifetime = OnCreate` vector is create-only. Anything else would
                            // let half a spawn position ride deltas.
                            createOnly = createOnly,
                        )
                    }
                }
            }

            is FieldLowering.Result.Unsupported -> {
                reportUnsupported(property, ownerName, propertyName, type, lowering.reason)
                failed = true
            }
        }
        return !failed
    }

    private fun reportVal(
        property: KSPropertyDeclaration,
        ownerName: String,
        propertyName: String,
        net: Boolean,
    ) {
        // @Net on a val is always a mistake, never a no-op: replication is capture-and-diff,
        // and a val cannot change, so the field would occupy a bit that can never be set.
        // @Sim on a val is the same defect on the snapshot side. Both ids come from
        // udea-diagnostics, never from here: an id is permanent public API, so a producer
        // that mints its own is not sharing an id space with the K2 checker at all.
        val rule = if (net) UdeaRules.NET_ON_VAL else UdeaRules.SIM_ON_VAL
        val annotation = if (net) "@Net" else "@Sim"
        val consequence = if (net) "it can never replicate" else "it can never be snapshotted"
        logger.error(
            "${rule.id}: $annotation annotates the val $ownerName.$propertyName. A val can " +
                "never change, so $consequence, and Replicator.apply could not restore it. " +
                "Make it a var or drop the annotation.",
            property,
        )
    }

    /**
     * The message that replaces the old generator's silent CBOR fallback.
     *
     * It names the type, the owning class and the property, states *why* the type could not
     * be lowered, and lists what a field may be — and it carries a Levenshtein suggestion
     * when the type's name is one typo away from a storable one, which the diagnostics
     * contract makes mandatory rather than optional for an unresolved name.
     */
    private fun reportUnsupported(
        property: KSPropertyDeclaration,
        ownerName: String,
        propertyName: String,
        type: KSType,
        reason: String,
    ) {
        val suggestion = DidYouMean.suggest(type.simpleName(), FieldLowering.DIRECT_TYPE_NAMES)
        logger.error(
            "${UdeaRules.UNSUPPORTED_FIELD_TYPE.id}: $ownerName.$propertyName is " +
                "${type.describe()}, which udea-codegen cannot replicate: $reason. A @Net/@Sim " +
                "field must be Boolean, Int, Long, Float, an enum, NetId or Tick, or a value " +
                "type whose public properties are all vars of those — such a type is lowered to " +
                "one field per property, as a 2D vector lowers to `$propertyName.x` and " +
                "`$propertyName.y`." +
                if (suggestion == null) "" else " Did you mean $suggestion?",
            property,
        )
    }

    /**
     * The `@Q` arguments, folded to a [Quantisation], or `null` after reporting why not.
     *
     * `bits` and the range are validated here and not only by the K2 checker, because KSP is
     * the producer that would otherwise emit them: `writeFixed` requires `bits in 1..32` and
     * `min < max`, so an unchecked declaration becomes a generated file that compiles and
     * throws from `write` on the first tick that field changes.
     *
     * All three failures report under `UdeaRules.MALFORMED_QUANTIZATION` (`UDEA0007`), which
     * is about the annotation's *arguments*; `UDEA0003` next door is about the annotated
     * property's *type*. Two ids because they have two different fixes.
     */
    private fun readQuantisation(
        property: KSPropertyDeclaration,
        ownerName: String,
        propertyName: String,
    ): Quantisation? {
        val annotation = property.annotations.first {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == AnnotationNames.Q
        }
        val bits = annotation.argument<Int>("bits")
        val min = annotation.argument<Float>("min")
        val max = annotation.argument<Float>("max")
        if (bits == null || min == null || max == null) {
            logger.error(
                "${UdeaRules.MALFORMED_QUANTIZATION.id}: @Q on $ownerName.$propertyName does " +
                    "not supply bits, min and max as constants; " +
                    "all three are folded into the generated codec at build time and must be " +
                    "compile-time literals.",
                property,
            )
            return null
        }
        if (bits !in 1..32) {
            logger.error(
                "${UdeaRules.MALFORMED_QUANTIZATION.id}: @Q(bits = $bits) on " +
                    "$ownerName.$propertyName is out of range: a quantised field " +
                    "occupies 1..32 bits. Use @Q(bits = 32, ...) for the widest fixed-point " +
                    "field, or drop @Q to send the float unquantised.",
                property,
            )
            return null
        }
        if (!min.isFinite() || !max.isFinite() || min >= max) {
            logger.error(
                "${UdeaRules.MALFORMED_QUANTIZATION.id}: @Q(min = $min, max = $max) on " +
                    "$ownerName.$propertyName is not a range: min must " +
                    "be finite, max must be finite, and min must be less than max. The range is " +
                    "the one thing the generator cannot infer, which is why @Q has no defaults.",
                property,
            )
            return null
        }
        return Quantisation(bits = bits, min = min, max = max)
    }
}

private inline fun <reified T> KSAnnotation.argument(name: String): T? =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? T

/**
 * Whether this property's `@Net` declares `lifetime = OnCreate`.
 *
 * The argument is compared **by the enum constant's simple name**, read off whatever KSP hands
 * back for an enum-valued argument — a `KSType`, a `KSClassDeclaration` or, on a Java-view
 * declaration, a plain string. Resolving it to `dev.wildware.udea.annotations.Lifetime` instead
 * would put a hard dependency from the processor onto the annotation module's classes at
 * *processing* time, which `AnnotationNames`' whole design avoids: `udea-codegen` runs inside
 * the compiler and addresses annotations by name.
 *
 * Absent means [dev.wildware.udea.annotations.Lifetime.Always], which is the annotation's own
 * default. Defaulting the *other* way would silently stop replicating a field.
 */
private fun KSPropertyDeclaration.lifetimeIsOnCreate(): Boolean {
    val net = annotations.firstOrNull {
        it.annotationType.resolve().declaration.qualifiedName?.asString() == AnnotationNames.NET
    } ?: return false
    val argument = net.arguments.firstOrNull { it.name?.asString() == LIFETIME_ARGUMENT }?.value
        ?: return false
    return argument.toString().substringAfterLast('.') == ON_CREATE
}

/** The `@Net` argument that carries the lifetime. */
private const val LIFETIME_ARGUMENT = "lifetime"

/** `dev.wildware.udea.annotations.Lifetime.OnCreate`, by simple name. */
private const val ON_CREATE = "OnCreate"

private fun KSType.isFloat(): Boolean = declaration.qualifiedName?.asString() == "kotlin.Float"
