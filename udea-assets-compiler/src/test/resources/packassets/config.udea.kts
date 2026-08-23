// The pack corpus (issue #89). Separate from `resources/assets` on purpose: that tree exercises
// the *front ends*, and several of its references point at `character(...)` declarations, which
// the provisional DSL gives no runtime type. Packing turns a reference into a typed `Ref<T>`, so
// those are kind mismatches - see `KindMismatchTest`, which asserts the packer reports them.
//
// This tree is kind-correct end to end, so a round-trip failure here is a bug in the writer or
// the reader rather than a known gap in the DSL.
// `gameConfig` takes no `defaultLevel` in the provisional DSL, only `defaultCharacter`.
gameConfig(defaultCharacter = reference("blueprint/player"))
