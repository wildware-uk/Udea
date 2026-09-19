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
// Short names - `maxHealth`, `strength`, `armour` - matching what `character/soldier.udea.kts`
// writes in its own `attributes` map. They are interned into an `AttributeId` by a running game
// (`CharacterAttributes`), which is why the authored form is a name; see `Item`'s KDoc.
// `MobaEffects.ITEM_STATS` is the list of names that exist, and `ItemPassiveSystem` refuses one
// that is not on it by name rather than dropping the bonus.
//
// These said `health` and `mana` until issue #166 gave them something to do. They are the
// ceilings now, and that is a correction rather than a retune: `health` is declared
// `max = value(maxHealth)`, and `AttributeRecompute` clamps each modifier against that bound as it
// applies it - so an infinite additive modifier on `health` is discarded in full on any unit at
// full health, which is every champion walking out of its own fountain. A `+80 health` vial would
// have been a stat that visibly did nothing. Raising the ceiling is also what the genre means by
// the words.

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
    stats = mapOf("maxHealth" to 80F),
)

item(
    name = "band",
    cost = 200,
    stats = mapOf("maxHealth" to 40F),
)

item(
    name = "gem",
    cost = 400,
    stats = mapOf("maxMana" to 100F),
)

item(
    name = "boots",
    cost = 300,
    stats = mapOf("healthRegen" to 2F),
)
