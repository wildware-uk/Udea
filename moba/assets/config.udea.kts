// The root of the eager set. `BundleContent.reachable` walks from `gameConfig` to decide which
// blobs load at launch and which stream, so a bundle without one streams everything and the
// first frame waits on a disk read it did not need to.
gameConfig(defaultCharacter = reference("blueprint/grunt"))
