// The soldier's arrow, which this build fires and does not draw.
//
// `example/src/main/resources/assets/blueprint/arrow.udea.kts` gave the arrow a
// `spriteRenderer(texture = loadSprite("/sprites/arrow/arrow.png", .1F))` over a kinematic sensor
// body. The port kept the flight and the damage - `ArrowBlueprint` is `Position` + `Motion` +
// `Projectile` - and dropped the picture, and `sprites/arrow/arrow.png` was never copied into this
// module at all. A soldier therefore shot invisible arrows that took ten health off a skeleton
// forty world units away with nothing between them.
//
// One frame, so `rows` and `columns` are both the default 1 and this is a still image the animator
// clamps on frame 0 forever.
//
// ## The scale
//
// The old sprite was authored at `0.1` world units per pixel in a world where a character was
// about **one** unit across (see `MobaScale`). A character here is about forty, so the same arrow
// at the same relative size is `0.1 * 40 = 4.0` - which draws the packed 32px frame at 128 world
// units, three times the length of the soldier that fired it. The number below is the size the
// arrow actually wants: about half a unit long, which is what the old picture looked like on
// screen once Box2D had scaled the world down to it.

spriteSheet(
    name = "arrow_sheet",
    spritePath = "sprites/arrow/arrow.png",
    rows = 1,
    columns = 1,
    scale = 0.9F,
)

spriteAnimation(name = "arrow", sheet = reference("sprites/arrow/arrow_sheet"), loop = false)
