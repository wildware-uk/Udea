// The finished items: the ones with a build path.
//
// ## Every `cost` here is the price on the shelf
//
// A champion with an empty inventory pays it in full. One who already carries a component pays
// the difference, and the components are consumed. `ItemRecipeValidator` (`UDEA0037`) fails the
// build if a cost here is ever below the sum of the components' costs, because the subtraction
// that produces the counter price would then go negative and the shop would pay gold *out* on a
// purchase.
//
// The combine cost - what is left after the parts - is written beside each recipe, so a reader
// can check the arithmetic without opening the components file. It is a comment and not a field
// on purpose: two numbers that must agree and no compiler that checks they do is exactly the
// drift `MobaAuthoredContentTest` was written to close, one level down.
//
// ## `unique`, `grantedAbility` and `passive` are declared and not yet acted on
//
// Issue #166 owns the systems: `ItemPassiveSystem` applying `stats` as GAS effects, unique
// deduplication so two `unique/fortified` items grant one instance, and item actives on a shared
// item-cooldown slot. They are authored here rather than left for that ticket because a schema
// field that arrives later is an asset tree that has to be re-authored later - and because a
// reference is only checked at build time if something declares it as a reference. Point
// `grantedAbility` at an id nothing declares and this file fails to build today.
//
// The abilities and the effect below are the ones this game already ships. They are placeholders
// in the sense that a warhammer swinging the orc elite's spin is not the final design; they are
// **not** placeholders in the sense of naming nothing - each resolves, and `UDEA0013` would
// refuse one that named an effect where an ability belongs.

item(
    name = "greatsword",
    cost = 750, // blade 350 + whetstone 150 + 250 to combine
    stats = mapOf("strength" to 18F),
    components = listOf(reference("item/blade"), reference("item/whetstone")),
    unique = "unique/sharpened",
    passive = reference("ability/passive_health_regen"),
)

item(
    name = "twin_blades",
    cost = 900, // blade 350 + blade 350 + 200 to combine
    stats = mapOf("strength" to 18F),
    // The same component twice. The shop requires two *distinct* slots holding it, which is why
    // `Item.components` is a list and not a set - and it is the case `RecipeTest` uses to prove
    // that a champion carrying one blade pays 550 and a champion carrying two pays 200.
    components = listOf(reference("item/blade"), reference("item/blade")),
)

item(
    name = "bulwark",
    cost = 1100, // cloak 300 + plate 500 + 300 to combine
    stats = mapOf("armour" to 40F, "health" to 100F),
    components = listOf(reference("item/cloak"), reference("item/plate")),
    unique = "unique/fortified",
)

item(
    name = "sentinel_greaves",
    cost = 850, // boots 300 + cloak 300 + 250 to combine
    stats = mapOf("armour" to 18F, "healthRegen" to 5F),
    components = listOf(reference("item/boots"), reference("item/cloak")),
    // The same unique group as `item/bulwark`, on purpose: a champion carrying both is the case
    // issue #166's `UniquePassiveTest` needs, and there has to be a pair in the tree for it to
    // have one.
    unique = "unique/fortified",
)

item(
    name = "lifestone",
    cost = 650, // vial 250 + band 200 + 200 to combine
    stats = mapOf("health" to 200F),
    components = listOf(reference("item/vial"), reference("item/band")),
    unique = "unique/vitality",
)

item(
    name = "bloodletter",
    cost = 900, // blade 350 + vial 250 + 300 to combine
    stats = mapOf("strength" to 12F, "health" to 100F),
    components = listOf(reference("item/blade"), reference("item/vial")),
    passive = reference("ability/passive_health_regen"),
)

item(
    name = "archmage_staff",
    cost = 850, // gem 400 + whetstone 150 + 300 to combine
    stats = mapOf("mana" to 200F, "strength" to 10F),
    components = listOf(reference("item/gem"), reference("item/whetstone")),
    passive = reference("ability/passive_health_regen"),
)

item(
    name = "warhammer",
    cost = 1500, // greatsword 750 + blade 350 + 400 to combine
    stats = mapOf("strength" to 30F),
    // A component that is itself a finished item. The shop consumes *direct* components only, so
    // buying this from a champion carrying a greatsword trades in the greatsword and not the
    // blade and whetstone inside it - which are not in the inventory any more, because buying
    // the greatsword consumed them.
    components = listOf(reference("item/greatsword"), reference("item/blade")),
    grantedAbility = reference("ability/orc_elite_spin"),
)

item(
    name = "aegis",
    cost = 1700, // bulwark 1100 + vial 250 + 350 to combine
    stats = mapOf("armour" to 55F, "health" to 250F),
    components = listOf(reference("item/bulwark"), reference("item/vial")),
    unique = "unique/fortified",
    grantedAbility = reference("ability/priest_heal"),
)

item(
    name = "phoenix_charm",
    cost = 1500, // lifestone 650 + gem 400 + 450 to combine
    stats = mapOf("health" to 250F, "mana" to 150F),
    components = listOf(reference("item/lifestone"), reference("item/gem")),
    unique = "unique/vitality",
    grantedAbility = reference("ability/priest_heal"),
)
