package dev.wildware.udea.codegen.rpc

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import dev.wildware.udea.codegen.AnnotationNames
import dev.wildware.udea.codegen.CoreNames

/**
 * Turns an `@Rpc` function declaration into an [RpcFunction], or reports why it cannot.
 *
 * Every refusal below exists because the corresponding mistake, left to run, produces a
 * *silently* unguarded call - the old engine's exact failure. `PacketUtil.kt:148` had one
 * comment where a check should have been, and the build was green.
 *
 * The failure policy is `ComponentModelBuilder`'s: no `catch`, every diagnostic at the
 * offending symbol, and a function with any error emits **no file at all** rather than an
 * unguarded one.
 */
internal class RpcModelBuilder(private val logger: KSPLogger) {

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    fun build(declaration: KSFunctionDeclaration): RpcFunction? {
        var failed = false
        val qualifiedName = declaration.qualifiedName?.asString()
        if (qualifiedName == null) {
            logger.error("@Rpc is only supported on a named function", declaration)
            return null
        }
        val functionName = declaration.simpleName.asString()

        // A top-level function and nothing else. A member function has a receiver the server
        // would have to conjure out of a datagram, and an extension has two; both turn "who may
        // call this" into "on what", which the authority vocabulary has no word for.
        if (declaration.parentDeclaration != null || declaration.extensionReceiver != null) {
            logger.error(
                "@Rpc $qualifiedName is not a top-level function. An RPC is invoked from a " +
                    "datagram, which carries arguments and no receiver, so the server would have " +
                    "to invent the instance to call it on. Make it top-level and pass what it " +
                    "needs as arguments.",
                declaration,
            )
            failed = true
        }
        val returns = declaration.returnType?.resolve()
        if (returns != null && returns.declaration.qualifiedName?.asString() != UNIT) {
            logger.error(
                "@Rpc $qualifiedName returns ${returns.declaration.simpleName.asString()}. An RPC " +
                    "is one-way: there is no reply frame and no correlation id, so a return " +
                    "value would be computed on one machine and dropped. Return Unit and send " +
                    "the answer as a separate server-to-client @Rpc.",
                declaration,
            )
            failed = true
        }

        val annotation = declaration.annotations.first {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == AnnotationNames.RPC
        }
        val direction = annotation.enumArgument(DIRECTION) ?: DEFAULT_DIRECTION
        val authority = annotation.enumArgument(AUTHORITY) ?: DEFAULT_AUTHORITY
        val reliability = annotation.enumArgument(RELIABILITY) ?: DEFAULT_RELIABILITY
        val relevancy = annotation.enumArgument(RELEVANCY) ?: DEFAULT_RELEVANCY
        val ratePerSecond = annotation.argument<Int>(RATE) ?: 0
        val burst = annotation.argument<Int>(BURST) ?: 0

        if (ratePerSecond < 0 || burst < 0) {
            logger.error(
                "@Rpc(ratePerSecond = $ratePerSecond, burst = $burst) on $qualifiedName is not a " +
                    "rate: both are counts and neither may be negative. Use 0 for no limit.",
                declaration,
            )
            failed = true
        }

        val args = ArrayList<RpcArg>(declaration.parameters.size)
        for (parameter in declaration.parameters) {
            val parameterName = parameter.name?.asString() ?: UNNAMED
            val type = parameter.type.resolve()
            val kind = kindOf(type)
            if (kind == null) {
                logger.error(
                    "@Rpc $qualifiedName takes $parameterName: ${type.describe()}, which has no " +
                        "wire encoding. An RPC argument must be Boolean, Int, Long, Float, an " +
                        "enum, NetId or Tick. A composite argument is deliberately not lowered " +
                        "the way a @Net field is: a field is diffed against a baseline the " +
                        "server itself wrote, an argument is a value a hostile peer chose, and " +
                        "every extra shape is one more decode to get right under that.",
                    parameter,
                )
                failed = true
                continue
            }
            args += RpcArg(
                name = parameterName,
                kind = kind,
                type = type.toRpcClassName(),
                enumEntries = if (kind == RpcArgKind.ENUM) type.toRpcClassName() else null,
            )
        }

        val ownershipGated = authority == OWNER_PREDICTED || authority == OWNER_WRITABLE
        var targetArg = -1
        if (!failed && ownershipGated) {
            // Only an ownership authority has a target. A `Server` RPC taking a NetId - a
            // multicast kill announcement, say - takes it as data, and treating it as a guard
            // subject would emit an ownership check on a call the server itself originates:
            // a check that always fails, on a body that would then never run.
            targetArg = args.indexOfFirst { it.kind == RpcArgKind.NET_ID }
            if (targetArg < 0) {
                logger.error(
                    "@Rpc(authority = $authority) on $qualifiedName declares an ownership rule " +
                        "and takes no NetId, so there is nothing for the generated guard to " +
                        "check ownership of. That is the exact shape of the defect the guard " +
                        "exists for: PacketUtil.kt:148 read an entity id out of a packet and " +
                        "never asked whether the sender owned it. Add the NetId the call acts " +
                        "on, or declare authority = Server.",
                    declaration,
                )
                failed = true
            }
            if (args.count { it.kind == RpcArgKind.NET_ID } > 1) {
                logger.error(
                    "@Rpc(authority = $authority) on $qualifiedName takes more than one NetId, " +
                        "so which one the guard is about is a guess. Ownership is checked " +
                        "against exactly one entity; pass the others some other way, or split " +
                        "the call.",
                    declaration,
                )
                failed = true
            }
        }

        if (direction == CLIENT_TO_SERVER && authority == SERVER) {
            logger.error(
                "@Rpc(direction = ClientToServer, authority = Server) on $qualifiedName can " +
                    "never be invoked: the direction says a client sends it and the authority " +
                    "says no client may. Declare authority = OwnerPredicted for a call the " +
                    "owning client makes on its own entity, or change the direction.",
                declaration,
            )
            failed = true
        }
        if (direction != CLIENT_TO_SERVER && authority != SERVER) {
            logger.error(
                "@Rpc(direction = $direction, authority = $authority) on $qualifiedName is a " +
                    "server-originated call carrying a client authority rule. Only the server " +
                    "sends $direction, so the ownership check would never run and the " +
                    "declaration would read as a protection that is not there. Declare " +
                    "authority = Server.",
                declaration,
            )
            failed = true
        }

        if (failed) return null
        return RpcFunction(
            packageName = declaration.packageName.asString(),
            functionName = functionName,
            qualifiedName = qualifiedName,
            direction = direction,
            authority = authority,
            reliability = reliability,
            relevancy = relevancy,
            ratePerSecond = ratePerSecond,
            burst = burst,
            args = args,
            targetArg = targetArg,
        )
    }

    /** The wire kind for [type], or `null` when there is none. */
    private fun kindOf(type: KSType): RpcArgKind? {
        // A nullable argument would need a presence bit and a null branch in the guard, which
        // for a NetId means a guard that has to decide what owning nothing means. Refused.
        if (type.isMarkedNullable) return null
        return when (type.declaration.qualifiedName?.asString()) {
            "kotlin.Boolean" -> RpcArgKind.BOOLEAN
            "kotlin.Int" -> RpcArgKind.INT
            "kotlin.Long" -> RpcArgKind.LONG
            "kotlin.Float" -> RpcArgKind.FLOAT
            CoreNames.NET_ID_FQN -> RpcArgKind.NET_ID
            CoreNames.TICK_FQN -> RpcArgKind.TICK
            else -> {
                val declaration = type.declaration as? KSClassDeclaration ?: return null
                if (declaration.classKind == ClassKind.ENUM_CLASS) RpcArgKind.ENUM else null
            }
        }
    }

    private companion object {
        const val UNIT = "kotlin.Unit"
        const val UNNAMED = "<unnamed>"

        const val DIRECTION = "direction"
        const val AUTHORITY = "authority"
        const val RELIABILITY = "reliability"
        const val RELEVANCY = "relevancy"
        const val RATE = "ratePerSecond"
        const val BURST = "burst"

        const val CLIENT_TO_SERVER = "ClientToServer"
        const val SERVER = "Server"
        const val OWNER_PREDICTED = "OwnerPredicted"
        const val OWNER_WRITABLE = "OwnerWritable"

        // The annotation's own defaults, restated because KSP does not reliably surface an
        // argument the call site omitted. Restating is only safe while they stay identical to
        // the declaration in udea-net; `RpcDeclarationTest` is what pins that.
        const val DEFAULT_DIRECTION = CLIENT_TO_SERVER
        const val DEFAULT_AUTHORITY = SERVER
        const val DEFAULT_RELIABILITY = "Reliable"
        const val DEFAULT_RELEVANCY = "Owner"
    }
}

private inline fun <reified T> KSAnnotation.argument(name: String): T? =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? T

/**
 * An enum-valued argument, by the constant's simple name.
 *
 * Same technique as `ComponentModelBuilder.lifetimeIsOnCreate`, and for the same reason: the
 * processor runs inside the compiler and addresses annotations by name rather than loading
 * their classes.
 */
private fun KSAnnotation.enumArgument(name: String): String? =
    arguments.firstOrNull { it.name?.asString() == name }?.value?.toString()?.substringAfterLast('.')

private fun KSType.toRpcClassName(): ClassName {
    val qualified = declaration.qualifiedName ?: return ClassName("", declaration.simpleName.asString())
    val packageName = declaration.packageName.asString()
    val simpleNames = qualified.asString().removePrefix("$packageName.").split('.')
    return ClassName(packageName, simpleNames)
}

private fun KSType.describe(): String = declaration.qualifiedName?.asString() ?: toString()
