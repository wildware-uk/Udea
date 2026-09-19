package dev.wildware.udea.physics2d

import dev.wildware.udea.core.identity.NetId

/**
 * A `Chain` changed after its body was built.
 *
 * A chain is not in a snapshot (its vertices are an array, which `udea-codegen` cannot lower), so
 * a rewind leaves it as it is. That is correct for static level geometry and wrong for anything
 * else: rewind across a chain edit and the world comes back with the new chain under the old
 * bodies. So the edit itself is refused, at the tick it happens, naming the entity - rather than
 * surfacing ticks or minutes later as a desync nobody can trace to it.
 */
internal class StaticGeometryChangedException(
    val owner: NetId,
    what: String,
) : IllegalStateException(
    "$owner: $what. A Chain is static level geometry - it is not in a snapshot, so a rewind " +
        "across a change to it would desync. Put the chain on the entity before its first " +
        "physics tick and never change or remove it until the scene is torn down; use Box, " +
        "Circle or Capsule for geometry that moves or changes.",
)
