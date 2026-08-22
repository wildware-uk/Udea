package dev.wildware.udea.assets.compiler

/**
 * The five-pass asset compiler: PSI scan, isolated evaluation, graph validation,
 * deterministic pack, accessor generation.
 *
 * Holds zero Gradle types (spec 3.6). It is the single implementation behind both the
 * Gradle task and the dev daemon, which is what lets a conformance test assert
 * byte-identical diagnostics.json from either path.
 *
 * This object is a placeholder so the module has a source root and appears in an IDE
 * sync. Later Phase 0 waves replace it with the real declarations.
 */
internal object AssetsCompilerModule
