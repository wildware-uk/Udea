package dev.wildware.udea.agent

/**
 * The MCP surface and the test harness, over one code path. describe_entity and
 * set_component_field are consequences of Replicator.getField/setField, so no reflection
 * is needed and the surface survives R8 (spec 3.1).
 *
 * This object is a placeholder so the module has a source root and appears in an IDE
 * sync. Later Phase 0 waves replace it with the real declarations.
 */
internal object AgentModule
