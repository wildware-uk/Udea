# Contract: `META-INF/udea/asset-index.json`

**Status:** proposed (Phase 2, issue #40). Frozen once pass 5 (issue #90) writes it.
**Format type:** `dev.wildware.udea.diagnostics.assets.AssetCatalog` / `AssetCatalogJson` (`udea-diagnostics`)
**Kind vocabulary:** `dev.wildware.udea.assets.compiler.AssetKind` (`udea-assets-compiler`)
**Producer:** `udea-assets-compiler`'s `AssetGraph.toCatalog()` — pass 5 *writes the file*, still to do
**Consumer:** `udea-compiler-plugin`'s `ClasspathAssetCatalogScanner` → `UdeaAssetReferenceChecker`
**Spec:** §3.2 (`reference("typo")` is a K2 FIR checker), §3.6 (pass 5), §5 (rule ids, did-you-mean)

The build-time asset graph, published on the compile classpath so a FIR checker can validate
`reference("...")` inside `.kt` **in the editor**, rather than at the `udeaValidateAssets` task
boundary.

---

## This is not `AssetIndex`

Spec §3.6 also uses the word "AssetIndex" for a **pack-time-stable integer** — the only asset
identity allowed into a snapshot, owned by `udea-assets` at runtime. This document is about a
different thing that happens to share the word: a list of **strings** on a **compile** classpath.

A review flagged the clash. The resolution: the *file* keeps the name `asset-index.json`
(issue #90's acceptance criteria already name it), and the *type* is called `AssetCatalog`. No
type named `AssetIndex` exists in `udea-diagnostics` or `udea-compiler-plugin`.

---

## Where it lives

One resource per module, at exactly:

```
META-INF/udea/asset-index.json
```

Delivery is the **classpath and nothing else**. A checker must not read a file by absolute
path: that breaks Gradle up-to-date checking (the file is not a declared input of
`compileKotlin`), breaks build-cache relocatability (an absolute path in a task input), and is
unavailable to the IDE's in-memory analysis, which has a classpath but not the build's argument
list. Every module already contributes its resources to its consumers' compile classpaths, so
merging across roots is exactly how an upstream module's assets become visible downstream.

---

## The document

```json
{
  "version": 1,
  "assets": [
    {"id": "character/orc_idle", "kind": "dev.wildware.udea.assets.SpriteSheet"},
    {"id": "level/spawner_0", "kind": "dev.wildware.udea.assets.Blueprint"}
  ]
}
```

(An earlier draft of this document illustrated the kinds as `BlueprintAsset` and
`CharacterAsset`. No such types exist; the runtime model calls them `Blueprint` and
`SpriteSheet`. Corrected in the integration wave, and `AssetCatalogSeamTest` now asserts every
published kind against the `KClass` rather than against a string written here.)

| Key | Type | Meaning |
|---|---|---|
| `version` | integer | Format version. `AssetCatalog.FORMAT_VERSION`, currently `1`. |
| `assets` | array | Every asset the module declares. May be empty. |
| `assets[].id` | string, non-blank | The reference string an author writes in `reference("...")`. |
| `assets[].kind` | string, non-blank | Fully qualified name of the asset's declared type. |

Unknown keys are ignored by the reader, at both levels, so a producer may add a field without
breaking older consumers. A **missing or non-integer `version`**, a missing `assets`, or a
blank `id`/`kind` is malformed and reported.

### Encoding is pinned, not conventional

Issue #90 makes this a cache-correct task output, so two runs over the same tree must be
byte-identical. `AssetCatalogJson.encode` fixes every degree of freedom:

- entries sorted by `(id, kind)` — never a map iteration order;
- key order inside an object fixed by the encoder;
- two-space indent, `\n` line endings only, exactly one trailing newline;
- pure ASCII: anything outside printable ASCII escaped `\uXXXX`, so the bytes do not depend on
  the producer's default charset;
- **no timestamps, no paths, no producer name, no host name.** Nothing varies with when or
  where the build ran.

A producer that writes this file should encode through `AssetCatalogJson.encode` rather than
hand-rolling it — that is the whole reason the type lives in a leaf module both sides depend on.

---

## Reader behaviour (frozen half)

| Situation | Behaviour |
|---|---|
| No index on any classpath root | Empty catalog, **zero diagnostics**. Never an error. |
| Root does not exist on disk | Ignored. Common on a stale classpath; not the plugin's business. |
| Several roots carry an index | Merged: entries sorted and deduplicated across all of them. |
| One id, one kind, two modules | Deduplicated to one entry. Silent. |
| One id, **two kinds** | Both entries kept; reported **once** as an `AssetCatalogConflict`. `resolve` answers with the lowest kind in sort order, so every reference to it is still validated. |
| `version` this build cannot read | One `UDEA0014` naming **both** versions. Never silently treated as empty. |
| Unreadable / malformed / not an archive | One `UDEA0014` naming the origin and the reason. Not an exception. |
| Plugin disabled (`-Pudea.compilerPlugin.enabled=false`) | Nothing is read at all; the plugin is not on the compilation. |

The classpath is walked **at most once per compilation** (`AssetCatalogSource`), not once per
`reference("...")`, and not at all in a module that contains no asset reference.

Diagnostic origins are **file names, never paths** — §5 forbids an absolute path in a
diagnostic, and a path relative to nothing is worse than a name.

---

## Rule ids this feeds

Registered in `dev.wildware.udea.diagnostics.UdeaRules`, which both the K2 checker and the
asset validator read. §5 requires a developer never to see two ids for one defect, so neither
producer mints one locally.

| Id | Rule | Raised by |
|---|---|---|
| `UDEA0004` | `UNRESOLVED_REFERENCE` — the id names no declared asset | K2 checker (`.kt`), asset validator (`.udea.kts`) |
| `UDEA0013` | `REFERENCE_KIND_MISMATCH` — the id resolves, but not to the referenced type | K2 checker, asset validator |
| `UDEA0014` | `ASSET_INDEX_FORMAT` — the index on the classpath cannot be read | K2 checker only |

`UDEA0004` and `UDEA0013` messages carry up to three Levenshtein candidates
(`AssetCatalog.nearest`, capped at `MAX_SUGGESTIONS`). §5 makes the did-you-mean mandatory: it
is what lets an agent correct a typo in the same turn instead of spending one listing the asset
tree.

---

## One vocabulary for "kind" — the three-party agreement

Three modules use the word *kind* and, until the integration wave, meant three things by it:

| Module | Meant by "kind" | Example |
|---|---|---|
| `udea-assets` (model) | a Kotlin type implementing `AssetData` | `SpriteSheet` |
| `udea-assets-compiler` (producer) | the **DSL function name** | `spriteSheet` |
| `udea-compiler-plugin` (consumer) | `AssetCatalogEntry.kindFqn`, resolved via `ClassId` | `dev.wildware.udea.assets.SpriteSheet` |

**The consumer's meaning is normative**, and not by seniority: it is the only one that can
answer the question the checker asks. `reference<SpriteAnimation>("character/orc")` is wrong
when the id resolves to something that is not a `SpriteAnimation`, and deciding that needs a
name a `ClassId` can be built from. `"spriteAnimation"` is not such a name.

A casing convention would not fix it either. `AssetData` is deliberately **not** sealed — "a
game declares its own kinds" — so a table mapping DSL words to engine types would be wrong in
principle as well as in fact, because a game's own kinds are in no table the engine could hold.

### How the producer speaks it

`AssetKind` (in `udea-assets-compiler`) is how. A declaration function states its kind by
handing over the `KClass` it produces, and `AssetKind.Declared.fqn` reads the name off that
class. There is no string kept in step by hand, which is the only reason it will stay in step —
a rename in `udea-assets` moves both sides at once. `DeclaredAsset.kindFqn` carries the result,
including across the isolated worker boundary (`AssetRecord.kindFqn`; `WorkerTest` fails if it
is dropped).

### A DSL word with no runtime type is `null`, and is reported

`AssetKind.Unpublishable` exists because the provisional DSL in `AssetScope` declares kinds the
runtime model has no type for. `character` is the live example — `udea-assets` has no
`Character` — as is the generic `asset(kind, ...)` escape hatch, whose kind is by construction
a game's own word.

Such a declaration is **absent from the catalog and listed in `CatalogExport.unpublishable`**.
It is not published as `dev.wildware.udea.assets.Character` on the strength of the function
being called `character`. That would be worse than absence: an unresolvable `kindFqn` is a
*silent* case in the checker by contract, so the id would be indexed **and** unvalidated, and
nothing anywhere would go red. `AssetCatalogSeamTest` asserts both halves.

---

## What the producer still owes

`AssetGraph.toCatalog()` builds the `AssetCatalog`, and `AssetCatalogJson.encode` is the only
encoder. **Nothing writes the file yet** — that is pass 5, issue #90. What it owes:

1. Write `META-INF/udea/asset-index.json` into the module's resources output, through
   `AssetCatalogJson.encode(export.catalog)`.
2. Declare it as an output of a cache-correct task, so a downstream `compileKotlin` sees it.
3. Include byte-identical-across-two-runs in that task's acceptance criteria.
   (`AssetCatalogSeamTest` already asserts this for the document; the task's own inputs are the
   remaining half.)
4. Decide what a `CatalogExport.unpublishable` entry does to the build. It is currently data on
   a return value that no task reads, which is the honest state: the provisional DSL produces
   two of them for the fixture corpus, so failing on one today would fail every build. It stops
   being provisional when `udea-assets` owns the generated DSL (#84's remaining half), and at
   that point an unpublishable kind becomes a defect worth a diagnostic. No id is minted for it
   here: an unused rule constant is a switch nothing reads. The next free id in this module's
   reserved band is `UDEA0024`.
5. Report `UDEA0004` / `UDEA0013` for `.udea.kts` defects, from the same `UdeaRules` constants
   the checker uses — the parity that Phase 2's exit criteria require.
