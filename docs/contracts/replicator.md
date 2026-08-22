# Contract: `Replicator<T>`

**Status:** frozen (Phase 0)
**Module:** `udea-core`, package `dev.wildware.udea.core.replication`
**Golden dump:** `udea-core/api/replicator-contract.api`
**Spec:** [§3.1](../superpowers/specs/2026-08-22-udea-ai-native-rewrite-design.md), §5 "Serialization", §7 (risk row)

Exactly one generated artefact per component. It is frozen before a single game component is
annotated, because four modules compile against it and they break together if it moves.

---

## The five consumers

| # | Consumer | Calls |
|---|---|---|
| 1 | network delta write | `capture` → `diff` → `and(diff, netMask)` → `write` |
| 2 | network full write | `capture` → `write` with `netMask` |
| 3 | snapshot capture | `capture` with `allMask` |
| 4 | snapshot restore | `apply` in place |
| 5 | agent field access | `getField` / `setField`, `fieldNames` |

Consequences that fall out of having one mechanism instead of four:

- the snapshot ring **is** the replication baseline store, so time travel and delta encoding
  are one thing rather than two that can disagree;
- `describe_entity` and `set_component_field` need no reflection and survive R8;
- `desync_report(tick)` is a field-by-field `FieldStore` comparison, not a byte diff.

## The interface

```kotlin
interface Replicator<T> {
    val typeId: ComponentTypeId
    val fieldNames: List<String>
    val netMask: FieldMask
    val allMask: FieldMask

    fun capture(component: T, store: FieldStore, slot: Int)
    fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask
    fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter)
    fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask
    fun apply(store: FieldStore, slot: Int, component: T, mask: FieldMask)
    fun getField(component: T, fieldIndex: Int): Any?
    fun setField(component: T, fieldIndex: Int, value: Any?)
}
```

The executable specification is `TransformReplicator` in `udea-core`'s test fixtures, driven
end to end by `ReplicatorContractTest`. Everything `udea-codegen` emits must behave the same.

---

## Two masks, one annotation family

Not everything snapshotted should be replicated. Jungle respawn timers and bot blackboards
must rewind and must never reach a client.

| Annotation | In `netMask` | In `allMask` | Meaning |
|---|---|---|---|
| `@Net` | yes | yes | replicated **and** snapshotted |
| `@Sim` | no | yes | snapshotted only |

Both land in the same `FieldStore`. Delta write considers only `netMask`; capture uses
`allMask`. `netMask` is always a subset of `allMask`; a bit in `netMask` and not in `allMask`
is a contradiction and a generator must never emit one.

---

## Index alignment

`fieldNames[i]` is the field at `FieldMask` bit `i` **and** at `FieldStore` field index `i`.
All three are the same index.

This is load-bearing rather than tidy: `desync_report(tick)` reports a differing tick *and
field*, which it can only do by indexing `fieldNames` with each set bit of the difference
between two slots.

### Composite values are lowered

A composite value type is lowered to **one field index per primitive component**, with
`fieldNames` carrying the dotted path. `Transform` has three annotated properties and four
fields:

| index | name | mask |
|---|---|---|
| 0 | `position.x` | `@Net` |
| 1 | `position.y` | `@Net` |
| 2 | `rotation` | `@Net` (`@Q(bits = 12, min = -3.1416f, max = 3.1416f)`) |
| 3 | `lastGroundedTick` | `@Sim` |

Lowering is what keeps one mask bit meaning one comparable value, keeps the store columnar
and allocation-free, and keeps the 64-field budget countable. The 64-field limit therefore
counts lowered fields, not annotated properties.

---

## Field comparison is bit-identical, not IEEE

Two field values are equal **iff their stored representations are identical**. For a `Float`
that means comparison is over `Float.toRawBits`, so `NaN` equals itself and `-0.0f` differs
from `0.0f` — the opposite of `==` on both counts.

Both comparison paths must agree on this: `FieldStore.fieldEquals`, which `desync_report`
walks without knowing the component's Kotlin types, and the typed comparison a generated
`Replicator.diff` uses for speed. A `diff` written with `getFloat(a) != getFloat(b)` is
**wrong**; it must compare `toRawBits()`. Only `Float` needs the treatment — `Int`, `Long`
and `Boolean` have no representation `==` disagrees with.

The rule is what makes a delta converge, rather than a matter of taste:

- under IEEE equality a field holding `NaN` differs from itself, so `diff` sets its bit every
  tick forever — a delta that never converges and a baseline that never settles;
- under IEEE equality `0.0f -> -0.0f` is not a change, so no delta is sent and the destination
  keeps `+0.0f`, while `fieldEquals` goes on reporting that field as differing — a permanent
  false positive in `desync_report` with no path to convergence.

Under bit-identical comparison, what is reported as different is exactly what a write would
actually change.

---

## Wire framing

`write` emits, in order:

1. the mask, low `fieldNames.size` bits (via `MaskOps.writeTo`);
2. each selected field, ascending field index.

**An empty mask emits zero bits** — no mask, no header, nothing. An unchanged component
costs nothing, and the framing layer above is responsible for not emitting an entry for an
empty delta. `read` is correspondingly only called where the framing layer already knows a
payload is present; it reads the mask, then the selected fields, and returns the mask.

Fields outside the mask are left untouched by both `read` and `apply`. That is what makes a
delta a delta: the destination slot must already hold the baseline.

`apply` mutates the caller's component **in place**. Restoring 1000 entities must not
allocate 1000 components, and a mutable vector keeps the identity that rendering and physics
hold references to. This is the one idea kept from the old `InPlaceSerializer<T>`.

---

## Dirty determination

Capture-and-diff, never setter instrumentation.

`Transform.position` is a mutable vector mutated by `position.set(...)` and by physics
write-back, so **no setter ever fires for the field that matters most**. Dirty-on-assign
would silently under-replicate position. `ReplicatorContractTest` pins this: an in-place
`position.set(...)` shows up in `diff`.

---

## `FieldMask` and the 64-field limit

`FieldMask` is an opaque value class. Its storage is `internal`, it has no bitwise
operators, and every operation goes through `MaskOps`. Callers never see that it is currently
one `Long`.

That is the mitigation for the risk in spec §7: the extension everyone can already see coming
is a component with more than 64 replicated fields, which forces `Long` → `LongArray`.

**Rules that keep that widening non-breaking:**

1. never write `mask and other` — use `MaskOps.and`;
2. never store a `FieldMask` in game code. It is passed *through* the `Replicator` API and
   nowhere else. `ReplicatorApiShapeTest` fails if a module outside `udea-core`/`udea-net`
   declares a property of type `FieldMask`;
3. never pass a mask as a `Long`. `ReplicatorApiShapeTest` fails if any member of
   `Replicator` mentions `Long` at all;
4. if you need a raw word — the wire encoder does — use `MaskOps.wordCount()` and
   `MaskOps.word(mask, i)`, which already generalise to more than one word, and
   `MaskOps.fromWords` to rebuild.

### How the widening would land

Only `FieldMask.kt` changes:

- `FieldMask(internal val bits: Long)` becomes a mask over a `LongArray` (or a
  `Long` plus an overflow array, to keep the common case unboxed);
- every `MaskOps` operation gains a word loop;
- `MaskOps.wordCount()` starts returning more than 1, and `writeTo` / `readFrom` already
  chunk their bit writes, so the wire format widens with them;
- `MaskOps.MAX_FIELDS` rises.

Nothing in `udea-net`, `udea-agent` or `moba` recompiles differently, because none of them
can see the storage.

Until then, `udea-codegen` raises an error at 64 lowered fields and directs the author to
split the component — which is better ECS design anyway.

---

## Ownership

`udea-core` owns **only the declarations** needed to compile the frozen signature.

| Declared in `udea-core` | Implemented by |
|---|---|
| `Replicator<T>` | `udea-codegen` (one per `@Replicated` component) |
| `FieldStore` | the snapshot epic — pooled buffers, ring cadences, `diffInto`, the memory budget |
| `BitWriter` / `BitReader` | `udea-net` — framing, buffers, `@Q` resolution, the packet header |
| `FieldMask` / `MaskOps` | `udea-core`, and only `udea-core` |

Those modules must **implement** these declarations, never redeclare them. Three modules
shipping three `FieldStore`s is exactly the drift this contract exists to prevent.

Test fixtures in `udea-core` (`ArrayFieldStore`, `ArrayBitWriter`/`ArrayBitReader`,
`Transform`, `TransformReplicator`) are correctness references for tests, deliberately not
the production implementations.
