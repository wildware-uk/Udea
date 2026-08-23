// The root of the eager set. `BundleContent.reachable` walks from `gameConfig` to decide which
// blobs load at launch and which stream, so a bundle without one streams everything and the
// first frame waits on a disk read it did not need to.
//
// `defaultLevel` is what makes the whole roster eager: the walk reaches `level/test_level`, and
// through it the four unit blueprints. Without this line the level would be packed and would
// stream, and the first scene swap - which happens on tick one of every entry point - would be
// the thing waiting on the disk.
gameConfig(
    defaultCharacter = reference("blueprint/soldier"),
    defaultLevel = reference("level/test_level"),
)
