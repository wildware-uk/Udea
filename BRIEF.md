# BRIEF.md - issue #271: a model lists its nodes, and carries its glTF extras

SHA: (filled at handover)

## Frozen predictions (written before any mutation ran)

Each mutation is applied alone to the finished branch, the named tests run, then reverted.
"Red" is a test that fails; "green" is one that passes. Predictions are fixed here before the run.

| # | Mutation (diff recorded after the run) | Predicted red | Predicted green, and why that is correct |
|---|---|---|---|
| M1 | Remove `ModelContents.fill` from `AssetCompiler.compile` (the feature: nothing enters the graph) | `ModelNodesAssetTest`: nodes-from-ref, packed-equals-accessors, node custom properties, scene custom properties, fbx nodes (5). `ModelNodesFromRefTest` (moba, real bundle): all 3 | `ModelNodesAssetTest` "a file with no custom properties packs none" (empty either way) and "the daemon's values carry the same nodes" (both sides empty, still equal): 2 |
| M2 | `UdeaGeneratedMemberChecker.MODEL_NODE` back to `dev.wildware.udea.core.spatial.ModelNode` | `UdeaGeneratedMemberCheckerTest`: misspelled socket, name like no node, missing clip vs node rule (3) | the 8 clip tests; "correctly spelled sockets compile clean" and "Suppress by the node rule id" (no diagnostic is the expected answer either way): 10 |
| M3 | `AssetCodecs` Model codec reads `nodes = emptyList()` | same 5 of `ModelNodesAssetTest` as M1, and all 3 of `ModelNodesFromRefTest` | the same 2 as M1 |
| M4 | `GltfExtras.flatten` drops a nested object instead of recursing | `GltfNodesTest`: "a group of extras is read as dotted keys", "Blender's custom properties land where they are read" (2); `ModelNodesAssetTest` "a node's Blender custom properties" (1) | `AccessorCompilationTest` extras test (it checks the accessors compile, and `int("fitting.slots")` is still an `Int?`) |
| M5 | `AccessorGenerator.extrasArgument` always returns an empty block | `ModelClipAccessorsTest` "a node's extras are written into its accessor" (1) | `ModelClipAccessorsTest` "a node with no extras is written exactly as before", the Fox golden, and `AccessorCompilationTest` (compiles either way) |
| M6 | `ModelExtras.float` returns `null` for a value of another type instead of throwing | `ModelExtrasTest` "a value of another type fails naming the key and both types" (1) | the other 7 `ModelExtrasTest` tests |
