// The trinkets: items that go in the seventh slot.
//
// A trinket is an ordinary item with `trinket = true`, and not a kind of its own, because
// everything else about one - a cost, a unique, an active, a stat block - is an item's. What the
// flag buys is that a champion carrying six items can still buy one, which is the whole reason
// the genre gives the trinket a slot rather than counting it among the six.
//
// **A trinket may not have a recipe.** `Item`'s own `init` refuses one, and the reason is
// mechanical rather than a design rule: a recipe is satisfied out of the six carried slots, so a
// trinket's components could never be found there and every purchase of it would silently be at
// full price.
//
// Vision items and wards are explicitly out of scope for issue #132 - they arrive with fog on the
// full map - so `item/scouting_totem` grants an ability and reveals nothing. It is here because
// an inventory with a trinket slot and no trinket in the tree is a slot that cannot be exercised.

item(
    name = "scouting_totem",
    // Free, as a starting trinket is in this genre. Zero is a real price and not a sentinel: it
    // still goes through every check the shop makes, so buying one while dead or outside the
    // fountain is refused exactly as an expensive purchase is.
    cost = 0,
    trinket = true,
    grantedAbility = reference("ability/soldier_fire_arrow"),
)

item(
    name = "warding_lens",
    cost = 250,
    trinket = true,
    stats = mapOf("armour" to 5F),
)
