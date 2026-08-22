package dev.wildware.udea.codegen

/**
 * The KSP2 processor and its KotlinPoet emitters. Owns net id assignment: sorted FQNs,
 * a checked-in net-protocol.lock, and the u16 protoHash in packet byte 0 (spec 5).
 *
 * Build-time only: nothing here ships in a game.
 *
 * This object is a placeholder so the module has a source root and appears in an IDE
 * sync. Later Phase 0 waves replace it with the real declarations.
 */
internal object CodegenModule
