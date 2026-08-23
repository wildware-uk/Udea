package dev.wildware.udea.build

/**
 * Keeps `docs/migration/trello-map.md` covering every card spec section 9 names.
 *
 * Section 9 is the one part of the plan that lives on somebody else's system. Bookkeeping that
 * spans two tools decays into nothing unless something fails when it drifts, and the drift that
 * matters is one-directional: a card the spec accounts for and the map does not is a card whose
 * fate nobody can look up.
 *
 * The reverse is deliberately allowed. The map may cover cards section 9 never mentioned —
 * later cards get added to the board — and failing on those would punish keeping the map more
 * complete than the spec.
 */
public object TrelloMap {

    /** Spec section 9 names a card the map does not. */
    public val UNMAPPED_CARD: RuleId = RuleId("UDEA-DOC-003")

    /** The heading section 9 opens with. */
    public const val SPEC_SECTION: String = "## 9. Carried-forward Trello work"

    /** A card id anywhere in prose: `#5`, `#31`. */
    private val CARD_IN_PROSE = Regex("""#(\d+)""")

    /**
     * A card id as the **first cell** of a table row.
     *
     * First cell only, and this is the whole reason the check is not a naive substring search:
     * the map cites GitHub issue numbers in its right-hand columns, and those collide with
     * Trello card ids — GitHub #14 and #15 are epics, Trello #14 and #15 are deferred cards. A
     * search over the whole file would find `#14` in a citation and call the card mapped.
     */
    private val CARD_IN_TABLE = Regex("""^\|\s*#(\d+)\s*\|""", RegexOption.MULTILINE)

    /**
     * Every card id spec section 9 names, in ascending order.
     *
     * @throws IllegalArgumentException if the section is absent, or names no cards at all —
     *   either would make this gate pass by finding nothing to check.
     */
    public fun cardsInSpec(spec: String): List<Int> {
        val start = spec.indexOf(SPEC_SECTION)
        require(start >= 0) { "the design spec has no '$SPEC_SECTION' section" }
        val cards = CARD_IN_PROSE.findAll(spec.substring(start))
            .map { it.groupValues[1].toInt() }
            .toSortedSet()
            .toList()
        require(cards.isNotEmpty()) { "'$SPEC_SECTION' names no cards, so nothing would be checked" }
        return cards
    }

    /** Every card id the map's tables key on, in ascending order. */
    public fun cardsInMap(map: String): List<Int> =
        CARD_IN_TABLE.findAll(map).map { it.groupValues[1].toInt() }.toSortedSet().toList()

    /**
     * Every card section 9 names that the map does not cover.
     *
     * @param mapPath repo-relative path, for the finding's location.
     */
    public fun findings(
        spec: String,
        map: String,
        mapPath: String = "docs/migration/trello-map.md",
    ): List<MigrationFinding> {
        val mapped = cardsInMap(map).toSet()
        return cardsInSpec(spec).filterNot { it in mapped }.map {
            MigrationFinding(
                rule = UNMAPPED_CARD,
                path = mapPath,
                line = 1,
                message = "spec section 9 names Trello card #$it, which has no row here. Give it " +
                    "a disposition - absorbed, obsoleted, deferred or scheduled - or a card on " +
                    "the board has no recorded fate.",
            )
        }
    }
}
