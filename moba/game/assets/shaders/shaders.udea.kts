// The game's screen effects. A `.frag` is an asset like a model or a sound: declared here, read
// and checked by the build, packed into the `.udeapak`, and named in Kotlin as
// `GameAssets.shaders.scanlines` - never as a path, and never read through a platform's resource
// API, which is what let the old documented form compile on the desktop alone.
shader(name = "scanlines", file = "shaders/scanlines.frag")
