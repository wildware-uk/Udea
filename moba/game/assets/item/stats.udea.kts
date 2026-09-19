// The effects that carry an item's flat stat bonus.
//
// ## Why there is one per attribute rather than one for all of them
//
// A `gameplayEffect` has exactly one `target`, so an item whose `stats` map names two attributes
// is two applications and there is no shape in which it could be one. The magnitude is
// `setByCaller`, so the *number* is not authored here: `ItemPassiveSystem` sums what a champion is
// actually carrying and stages the total, which is what makes buying a second strength item move
// a stat that already exists rather than adding a second effect beside it.
//
// ## Infinite, and swept by nothing
//
// A carried item's bonus lasts exactly as long as the item is carried, which is not a duration a
// tick count can express - so it is `infinite()` and `ItemPassiveSystem` is what removes it. That
// system reconciles against the inventory every tick, so selling an item takes the bonus with it
// on the next tick and a `time.rewind` that restores an older inventory is followed by a
// reconcile that restores the bonuses that inventory had.
//
// ## The names are the attributes, not the words an item description would use
//
// `maxHealth`, not `health`. `health` is declared `max = value(maxHealth)` and
// `AttributeRecompute` clamps every modifier as it applies it, so an infinite additive modifier on
// `health` is discarded in full on any unit at full health - which is every champion walking out
// of its own fountain. `MobaEffects.ITEM_STATS` says the same thing on the Kotlin side.

gameplayEffect(
    name = "stat_strength",
    target = "strength",
    modifierType = "Additive",
    magnitude = setByCaller("Data.ItemStat"),
    effectDuration = infinite(),
)

gameplayEffect(
    name = "stat_armour",
    target = "armour",
    modifierType = "Additive",
    magnitude = setByCaller("Data.ItemStat"),
    effectDuration = infinite(),
)

gameplayEffect(
    name = "stat_max_health",
    target = "maxHealth",
    modifierType = "Additive",
    magnitude = setByCaller("Data.ItemStat"),
    effectDuration = infinite(),
)

gameplayEffect(
    name = "stat_max_mana",
    target = "maxMana",
    modifierType = "Additive",
    magnitude = setByCaller("Data.ItemStat"),
    effectDuration = infinite(),
)

gameplayEffect(
    name = "stat_health_regen",
    target = "healthRegen",
    modifierType = "Additive",
    magnitude = setByCaller("Data.ItemStat"),
    effectDuration = infinite(),
)

// --- the unique passives ---------------------------------------------------------------------
//
// A *named* bonus on top of an item's stat block, and the thing an item's `unique` group
// deduplicates: `ItemPassiveSystem` applies one of these per unique group per champion, so
// carrying `item/bulwark` and `item/sentinel_greaves` - both `unique/fortified` - grants one
// `item/passive_fortified`, not two. Their stat blocks still stack; the named passive does not.
// That is what "unique passive" means in this genre.
//
// One effect per unique group, and the group in the item asset is what points at it. An item with
// a `unique` and no `passive` would be a group that deduplicates nothing, which is legal and
// pointless; every `unique` in this tree names one.
//
// ## The bonus is not authored here, and that is a gap rather than a choice
//
// `EffectMagnitude` is a two-case sealed interface - `setByCaller` and `attribute` - so the asset
// model cannot say "a constant fifteen". The numbers live in `MobaEffects.ITEM_PASSIVES` and are
// staged onto the application under `Data.ItemStat`, exactly as an ability's cooldown ticks are
// staged rather than authored on `ability/cooldown`. Closing it is an `EffectMagnitude.Constant`
// case with a codec discriminator, a packer branch and a validator rule in `udea-assets`, which
// is an engine change and not this game's; it is written down here because an effect whose
// magnitude reads as "whatever the caller says" looks the same as one nobody bothered to author.

gameplayEffect(
    name = "passive_fortified",
    target = "armour",
    modifierType = "Additive",
    magnitude = setByCaller("Data.ItemStat"),
    effectDuration = infinite(),
)

gameplayEffect(
    name = "passive_vitality",
    target = "maxHealth",
    modifierType = "Additive",
    magnitude = setByCaller("Data.ItemStat"),
    effectDuration = infinite(),
)

gameplayEffect(
    name = "passive_sharpened",
    target = "strength",
    modifierType = "Additive",
    magnitude = setByCaller("Data.ItemStat"),
    effectDuration = infinite(),
)

// A passive that belongs to **no** unique group. `item/bloodletter` and `item/archmage_staff` both
// name it and neither declares a `unique`, so a champion carrying both carries two of it - which
// is the case that says the deduplication is keyed on the unique id rather than on the effect.
gameplayEffect(
    name = "passive_vigour",
    target = "healthRegen",
    modifierType = "Additive",
    magnitude = setByCaller("Data.ItemStat"),
    effectDuration = infinite(),
)
