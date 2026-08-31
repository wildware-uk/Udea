// The basic items: the things a build path starts from.
//
// A component has no recipe of its own, so its `cost` is both the price on the shelf and what it
// is worth when it is traded in toward a finished item. That symmetry is the whole reason the
// build path is a saving rather than an accounting trick: a champion who bought `item/blade` for
// 350 and later buys `item/greatsword` pays 750 - 350, and is not out of pocket for having bought
// the sword early.
//
// ## No icons, deliberately
//
// Issue #132 puts item icon art out of scope and says to reuse existing effect sprites as
// placeholders. Neither is done here, because there is nothing yet that *draws* an item: the
// inventory is six integers on a component and no `RenderSystem` reads it. A `spritePath` on
// every one of these would be twenty references to art nothing loads, and `MissingFileValidator`
// would then be checking files for a picture no frame contains. When something draws an
// inventory, the field it needs is a `Ref<SpriteAnimation>` and it is one line per item.
//
// ## The attribute names
//
// Short names - `health`, `strength`, `armour` - matching what `character/soldier.udea.kts`
// writes in its own `attributes` map. They are interned into an `AttributeId` by a running game
// (`CharacterAttributes`), which is why the authored form is a name; see `Item`'s KDoc.

item(
    name = "blade",
    cost = 350,
    stats = mapOf("strength" to 8F),
)

item(
    name = "whetstone",
    cost = 150,
    stats = mapOf("strength" to 3F),
)

item(
    name = "cloak",
    cost = 300,
    stats = mapOf("armour" to 12F),
)

item(
    name = "plate",
    cost = 500,
    stats = mapOf("armour" to 20F),
)

item(
    name = "vial",
    cost = 250,
    stats = mapOf("health" to 80F),
)

item(
    name = "band",
    cost = 200,
    stats = mapOf("health" to 40F),
)

item(
    name = "gem",
    cost = 400,
    stats = mapOf("mana" to 100F),
)

item(
    name = "boots",
    cost = 300,
    stats = mapOf("healthRegen" to 2F),
)
