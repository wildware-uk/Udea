package dev.wildware.udea.codegen.agent

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import dev.wildware.udea.codegen.AnnotationNames
import dev.wildware.udea.codegen.replicator.describe
import dev.wildware.udea.codegen.replicator.toClassName
import dev.wildware.udea.diagnostics.UdeaRules

/**
 * Groups this module's `@AgentState` properties by their declaring class into
 * [AgentStateModel]s, reporting every property it cannot publish.
 *
 * ## This never touches the replication field space
 *
 * Nothing here reads, writes or consults `ComponentModelBuilder`, `FieldOrder`, `FieldMask`
 * or `FieldStore`, and no `@AgentState` property is ever offered to them. The frozen
 * `Replicator` contract makes `fieldNames[i]`, mask bit `i` and store index `i` the same
 * index, so a property with a name in that space but no bit and no slot cannot exist there.
 * `@AgentState` is a second, one-way channel: read once per digest, straight into the digest
 * buffer, never captured, never diffed, never sent and never restored by a rewind. A property
 * carrying both `@Net` and `@AgentState` therefore appears once in each, independently, and
 * `AgentStateIsolationTest` is what holds that apart.
 */
internal class AgentStateBuilder(private val logger: KSPLogger) {

    /**
     * @param properties every `@AgentState` property in the module, in a deterministic order.
     * @return one model per declaring class, sorted by the class's qualified name. A class
     *   with any failing property contributes nothing, so a half-published digest block is
     *   never emitted.
     */
    fun build(properties: List<KSPropertyDeclaration>): List<OwnedState> {
        val byOwner = LinkedHashMap<KSClassDeclaration, MutableList<AgentStateEntry>>()
        val failedOwners = HashSet<KSClassDeclaration>()

        for (property in properties) {
            val owner = property.parentDeclaration as? KSClassDeclaration
            if (owner == null || owner.classKind !in SOURCE_KINDS) {
                logger.error(
                    "@AgentState must be declared inside a class or object. The declaring type " +
                        "is the source the generated digest writer reads the value from; a " +
                        "top-level property has no source to read.",
                    property,
                )
                continue
            }
            byOwner.getOrPut(owner) { mutableListOf() }
            val entry = describe(owner, property)
            if (entry == null) failedOwners += owner else byOwner.getValue(owner) += entry
        }

        val models = ArrayList<OwnedState>(byOwner.size)
        for ((owner, entries) in byOwner) {
            if (owner in failedOwners) continue
            if (!checkUniqueNames(owner, entries)) continue
            val ownerName = owner.toClassName()
            models += OwnedState(
                owner = owner,
                model = AgentStateModel(
                    owner = ownerName,
                    objectName = AgentNaming.stateObjectName(ownerName.simpleName),
                    // Sorted by digest key so the `game` block's key order is a function of the
                    // sources alone, never of the order KSP handed the properties over.
                    entries = entries.sortedBy(AgentStateEntry::name),
                ),
            )
        }
        return models.sortedBy { it.model.owner.canonicalName }
    }

    /** A model plus the declaration it came from, which is what carries its source file. */
    data class OwnedState(val owner: KSClassDeclaration, val model: AgentStateModel)

    private fun describe(owner: KSClassDeclaration, property: KSPropertyDeclaration): AgentStateEntry? {
        val annotation = property.annotations.single {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == AnnotationNames.AGENT_STATE
        }
        val propertyName = property.simpleName.asString()
        val name = annotation.stringArgument("name").orEmpty().ifEmpty { propertyName }

        val type = property.type.resolve()
        if (type.isMarkedNullable) {
            logger.error(
                "${UdeaRules.AGENT_STATE_NON_SCALAR.id}: @AgentState ${owner.simpleName.asString()}." +
                    "$propertyName is ${type.describe()}. A null has no scalar rendering the " +
                    "bridge's digest agrees on, so publish a sentinel the game defines instead " +
                    "of a nullable field.",
                property,
            )
            return null
        }

        val declaration = type.declaration
        val qualifiedName = declaration.qualifiedName?.asString()
        val kind = qualifiedName?.let(AgentStateKind::of)
            ?: enumKind(declaration)
            ?: run {
                logger.error(
                    "${UdeaRules.AGENT_STATE_NON_SCALAR.id}: @AgentState ${owner.simpleName.asString()}." +
                        "$propertyName is ${type.describe()}, which is not a scalar. The bridge " +
                        "contract for GET /state says of the game block: \"scalar fields are " +
                        "included in the digest. Nested objects and arrays are not\" - so this " +
                        "value would not render oddly, it would vanish from every digest an " +
                        "agent ever reads. Use ${AgentStateKind.supported}, or expose entity " +
                        "data through describe_entity, which is what the restriction keeps out " +
                        "of the digest.",
                    property,
                )
                return null
            }

        return AgentStateEntry(
            name = name,
            propertyName = propertyName,
            kind = kind,
            enumType = if (kind == AgentStateKind.ENUM) (declaration as KSClassDeclaration).toClassName() else null,
        )
    }

    private fun enumKind(declaration: com.google.devtools.ksp.symbol.KSDeclaration): AgentStateKind? {
        val enum = declaration as? KSClassDeclaration ?: return null
        return if (enum.classKind == ClassKind.ENUM_CLASS) AgentStateKind.ENUM else null
    }

    /**
     * Two properties resolving to one digest key is not a merge: one of the two becomes
     * unreachable, and which one depends on iteration order.
     */
    private fun checkUniqueNames(owner: KSClassDeclaration, entries: List<AgentStateEntry>): Boolean {
        val duplicates = entries.groupBy(AgentStateEntry::name).filterValues { it.size > 1 }
        if (duplicates.isEmpty()) return true
        for ((name, colliding) in duplicates) {
            logger.error(
                "${UdeaRules.AGENT_NAME_COLLISION.id}: ${owner.qualifiedName?.asString()} publishes " +
                    "${colliding.size} @AgentState properties as '$name' (" +
                    colliding.joinToString(", ", transform = AgentStateEntry::propertyName) +
                    "). A digest key addresses one value; give each an @AgentState(name = \"...\").",
                owner,
            )
        }
        return false
    }

    private companion object {
        val SOURCE_KINDS = setOf(ClassKind.CLASS, ClassKind.OBJECT)
    }
}
