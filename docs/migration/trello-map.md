# Trello board reconciliation

The pre-existing backlog is Trello board [`3JqieuNR`](https://trello.com/b/3JqieuNR/udea). Spec
section 9 records what happens to each of its cards; this file is that record with the GitHub
issue or epic beside it, so a card can be traced forward and an issue can be traced back.

Every card named in section 9 appears below exactly once. `./gradlew udeaVerifyTrelloMap` fails
if section 9 names a card id that this file does not, so the map cannot quietly fall behind the
spec.

**Not** a migration to GitHub Projects. The board stays where it is; this settles what is on it.

---

## Absorbed into the rewrite

Work the rewrite does anyway. The card is answered by the design, not by a one-for-one
replacement issue, so several map to an epic rather than a single issue.

| Card | Title | Lands as |
|---|---|---|
| #5 | Eager and Dirty sync | D5 and spec section 5 *Dirty determination*: capture-and-diff over the generated `Replicator<T>`, never setter instrumentation. Epic #6, then #14 |
| #6 | Tick count sync | Spec section 5 *Time*: `Tick` is universal and `SimClock.time` is derived. Epic #8 |
| #8 | Server-only architecture with a local queue | Phase 3 `net.spawn_session(clients=2)` over `LoopbackTransport` — no sockets, no threads, no sleeps. #105 |
| #12 | Copy KDoc to DSL | Moved to Phase 0, enabled by D8: KSP cannot read or re-emit KDoc, the K2 plugin can. #42 |
| #13 | Precompiled assets | Answered: Gradle. The build is the compiler (spec section 3.6). Epic #12 |
| #16 | Custom network packets | Becomes typed RPC with declared authority, generated rather than remembered. #109 |
| #24 | Consolidate Asset vs AssetReference | One `Ref<T : AssetData>` carrying a pack-time interned index. #84 |
| #26 | Separate network connection from level/game screen | Dissolved by `RenderMode` and the God-object rule: `GameScreen` no longer exists to own a connection. Epics #8, #14 |
| #28 | Example game → the MOBA | D7. `moba` replaces `example`. Epic #16 |
| #32 | Asset refs resolve to integers | `AssetIndex`, interned at pack time — the only asset identity that may enter a snapshot. #84, #89, #123 |
| #33 | K2 compiler plugin instead of KSP | Reconciled rather than adopted: D8 keeps **both**, because they compose. Epic #7 |
| #34 | Cache remote entities | Spec section 5 *Entity identity*: `NetId` plus an `IntArray` index, O(1), no family scan. Epic #14 |
| #35 | Custom user attributes | A `ServiceLoader` `AttributeModule` registry: a game module declares attributes with no engine-side edit. #96 |

## Obsoleted by D6

The IDEA plugin, the level editor and `compose-ui` are dropped: the MCP tool surface **is** the
editor (spec section 1). These three cards describe features of a thing that no longer exists.
Archived on the board, with a comment naming D6.

| Card | Title | Why it is gone |
|---|---|---|
| #9 | Implement animation sequencer | An editor feature. Animation data is authored in `.udea.kts` and validated at build time |
| #10 | Implement binary level format | Superseded by `.udeapak`, written deterministically by the asset compiler |
| #11 | Implement level editor | D6 outright. `level-editor` was deleted in Phase 0 |

## Deferred, not planned

Not in Phases 0–7. Labelled deferred on the board rather than archived: they are wanted one day,
they are simply not scheduled, and archiving would lose that distinction.

| Card | Title | Note |
|---|---|---|
| #14 | Mod support | Needs the sandboxing story below to exist first |
| #15 | Safe script sandboxing | D4 removes the runtime script host entirely, which changes the question rather than answering it |
| #31 | Make Udea multiplatform | Spec section 8, open question 1: desktop JVM is the working assumption. Multiplatform would change the offscreen-render story and whether `kotlin-compiler-embeddable` can be tolerated near the runtime |

## Still wanted, scheduled by phase

| Card | Title | Phase | Lands as |
|---|---|---|---|
| #18 | Axis controller | 3 | `Axis2D` sampled once per **tick**, not once per frame. #124 |
| #19 | Documentation wiki | 6 | #143, #144 |
| #20 | Ability cooldowns | 3 | Tick-denominated, so a rewind cannot end a cooldown early. #99 |
| #21 | Ability assets | 3 | Build-time validated, no `KClass`/`KProperty` reflection on the hot path. #101 |
| #22 | Loading screen | 5 | #125 |
| #27 | In-game UI | 5 | The HUD is presentation only — a `RenderSystem`, never a Fleks system. #125, #131 |
| #29 | Tilesheets | 5 | The same mechanism as #32, seen from the other end. #123 |

---

## What was done on the board

Done, and verifiable by opening the board:

- All 26 cards carry a comment recording the disposition and linking the issue or epic.
- **#9, #10 and #11 are archived**, each with a comment quoting D6.
- **#14, #15 and #31 carry a new `DEFERRED` label** (black), keeping their existing labels.

Cards are not closed on absorption. A card stays open until the work actually ships, so the
board keeps meaning something to anyone reading it without the spec in hand.
