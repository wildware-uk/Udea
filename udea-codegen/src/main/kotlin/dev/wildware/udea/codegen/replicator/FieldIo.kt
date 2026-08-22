package dev.wildware.udea.codegen.replicator

import com.squareup.kotlinpoet.CodeBlock
import dev.wildware.udea.codegen.CoreNames
import dev.wildware.udea.codegen.NetNames

/**
 * The per-field halves of `capture`, `diff`, `write`, `read` and `apply`.
 *
 * Everything a *kind* of field decides lives here, so the emitter next door reads as the
 * shape of a `Replicator` rather than as a type switch. The `when`s below are exhaustive over
 * [FieldStorage] with no `else`, which is the point: a storage kind added later fails to
 * compile at each of the five sites that must handle it, instead of falling through to a
 * silent default — the old generator's default was `putSerializable`, and that is exactly the
 * failure mode being designed out.
 *
 * The asymmetry between the store and the wire is deliberate and is the whole reason this
 * file exists:
 *
 * - the **store** always holds the full-precision value. `@Q` never degrades a snapshot, so
 *   a rewind is exact and a desync report compares what the simulation actually computed
 *   (`dev.wildware.udea.annotations.Q`'s own contract);
 * - the **wire** applies the quantisation, with the three constants folded in as literals at
 *   generation time. No runtime parameter lookup, no annotation read, nothing to configure.
 */
internal object FieldIo {

    /** `store.setFloat(slot, FIELD_X, component.position.x)` */
    fun capture(field: ReplicatedField): CodeBlock =
        CodeBlock.builder()
            .add("store.set%L(slot, %L, ", field.storage.accessor, field.constant)
            .add(componentValue(field))
            .add(")\n")
            .build()

    /**
     * The expression `capture` stores, as read off the component.
     *
     * An enum contributes its ordinal; everything else contributes itself. `NetId` and `Tick`
     * are handed over whole, because the store has accessors for them — that is what "a
     * `NetId` is a primitive field type, not a special case" means in practice.
     */
    private fun componentValue(field: ReplicatedField): CodeBlock = when (field.storage) {
        FieldStorage.ENUM -> CodeBlock.builder().add(access(field)).add(".ordinal").build()
        FieldStorage.BOOLEAN,
        FieldStorage.INT,
        FieldStorage.LONG,
        FieldStorage.FLOAT,
        FieldStorage.NET_ID,
        FieldStorage.TICK,
        -> access(field)
    }

    /**
     * The `if (…)` condition of one `diff` clause: true when the two slots differ.
     *
     * A `Float` is compared as `toRawBits()` and never with `!=`, because `FieldStore`'s
     * semantics are the stored representation: `NaN` equals itself and `-0.0f` differs from
     * `0.0f`, the opposite of IEEE 754 on both counts. `NetId` and `Tick` are value classes
     * over an `Int`/`Long`, so `!=` on them already *is* representation comparison.
     */
    fun differs(field: ReplicatedField): CodeBlock {
        val suffix = if (field.storage == FieldStorage.FLOAT) ".toRawBits()" else ""
        return CodeBlock.of(
            "store.get%L(slotA, %L)%L != store.get%L(slotB, %L)%L",
            field.storage.accessor, field.constant, suffix,
            field.storage.accessor, field.constant, suffix,
        )
    }

    /** One `write` line: the field, off the store and onto the wire. */
    fun write(field: ReplicatedField): CodeBlock {
        val get = CodeBlock.of("store.get%L(slot, %L)", field.storage.accessor, field.constant)
        val quantisation = field.quantisation
        if (quantisation != null) {
            return CodeBlock.builder()
                .add("out.%M(", NetNames.WRITE_FIXED)
                .add(get)
                .add(
                    ", %L, %L, %L)\n",
                    floatLiteral(quantisation.min),
                    floatLiteral(quantisation.max),
                    quantisation.bits,
                )
                .build()
        }
        return when (field.storage) {
            // A NetId is its packed word and a Tick is its count: the wire carries the
            // primitive, and `read` rebuilds the value class through its own validating
            // factory. Writing the wrapper would need a codec the bit layer does not have.
            FieldStorage.NET_ID -> CodeBlock.builder().add("out.writeInt(").add(get).add(".raw)\n").build()
            FieldStorage.TICK -> CodeBlock.builder().add("out.writeLong(").add(get).add(".value)\n").build()
            FieldStorage.BOOLEAN,
            FieldStorage.INT,
            FieldStorage.LONG,
            FieldStorage.FLOAT,
            FieldStorage.ENUM,
            -> CodeBlock.builder()
                .add("out.write%L(", field.storage.accessor)
                .add(get)
                .add(")\n")
                .build()
        }
    }

    /**
     * One `read` body: the field, off the wire and into the store.
     *
     * `read` is the **trust boundary** — the only entry point that puts bytes it did not
     * produce into a `FieldStore` — so every value that has a legal range is checked *here*,
     * before the store sees it, and not in `apply` where the same bad value would already sit
     * in every snapshot slot captured from it. An enum ordinal is range-checked inline;
     * `NetId.ofRaw` does the equivalent for an entity reference and throws with its own
     * message naming the offending word.
     */
    fun read(component: ReplicatedComponent, field: ReplicatedField): CodeBlock {
        val set = { value: CodeBlock ->
            CodeBlock.builder()
                .add("store.set%L(slot, %L, ", field.storage.accessor, field.constant)
                .add(value)
                .add(")\n")
                .build()
        }
        val quantisation = field.quantisation
        if (quantisation != null) {
            return set(
                CodeBlock.of(
                    "src.%M(%L, %L, %L)",
                    NetNames.READ_FIXED,
                    floatLiteral(quantisation.min),
                    floatLiteral(quantisation.max),
                    quantisation.bits,
                ),
            )
        }
        return when (field.storage) {
            FieldStorage.ENUM -> readEnum(component, field)
            FieldStorage.NET_ID -> set(CodeBlock.of("%T.ofRaw(src.readInt())", CoreNames.NET_ID))
            FieldStorage.TICK -> set(CodeBlock.of("%T(src.readLong())", CoreNames.TICK))
            FieldStorage.BOOLEAN,
            FieldStorage.INT,
            FieldStorage.LONG,
            FieldStorage.FLOAT,
            -> set(CodeBlock.of("src.read%L()", field.storage.accessor))
        }
    }

    /**
     * An enum arrives as an unconstrained 32-bit ordinal, so it is bounds-checked before the
     * store sees it.
     *
     * The check names the component, the field, the offending ordinal and the valid range,
     * because the two realistic sources are a corrupt datagram and ordinary version skew
     * between two builds whose enum has a different number of constants — and a bare
     * `IndexOutOfBoundsException` from inside generated code names none of them.
     */
    private fun readEnum(component: ReplicatedComponent, field: ReplicatedField): CodeBlock {
        val enum = requireNotNull(field.enumEntries) { "an ENUM field must carry its entries class" }
        val local = field.path.joinToString("") + "Ordinal"
        return CodeBlock.builder()
            .add(
                "// The trust boundary: this ordinal is unconstrained until checked, and a\n" +
                    "// bad one must never reach the store — the snapshot ring shares it.\n",
            )
            .addStatement("val %N = src.readInt()", local)
            .addStatement(
                "require(%N in %T.entries.indices) { TYPE_NAME + %S + %N + %S + %T.entries.size + %S }",
                local,
                enum,
                ".${field.name}: ordinal ",
                local,
                " is not a ${enum.simpleNames.joinToString(".")} constant (0 until ",
                enum,
                ")",
            )
            .addStatement("store.set%L(slot, %L, %N)", field.storage.accessor, field.constant, local)
            .build()
    }

    /**
     * One `apply` line: the field, out of the store and back onto the component.
     *
     * Writing through the access path is what makes lowering work and what keeps `apply`
     * in place: `component.position.x = …` restores the vector without replacing it, so
     * every reference rendering and physics hold stays valid.
     */
    fun apply(field: ReplicatedField): CodeBlock {
        val get = CodeBlock.of("store.get%L(slot, %L)", field.storage.accessor, field.constant)
        val value = if (field.storage == FieldStorage.ENUM) {
            // Safe to index directly *because* of the two writers into the store: capture
            // writes `.ordinal`, in range by construction, and read range-checks. There is no
            // third writer.
            CodeBlock.builder().add("%T.entries[", requireNotNull(field.enumEntries)).add(get).add("]").build()
        } else {
            get
        }
        return CodeBlock.builder().add(access(field)).add(" = ").add(value).add("\n").build()
    }

    /** `component.position.x` — every segment through `%N`, so a keyword name is escaped. */
    fun access(field: ReplicatedField): CodeBlock {
        val block = CodeBlock.builder().add("component")
        for (segment in field.path) block.add(".%N", segment)
        return block.build()
    }

    /**
     * A `Float` constant as Kotlin source: `-3.1416f`.
     *
     * Spelled out rather than left to `%L`, because a `Float` literal without the `f` suffix
     * is a `Double` in Kotlin and would not compile at the `writeFixed(…, Float, Float, Int)`
     * call. Only finite values reach here — `ComponentModelBuilder` rejects a non-finite `@Q`
     * bound, which is also what keeps `Infinity` and `NaN` (neither of which is Kotlin
     * source) out of the emitted text.
     */
    private fun floatLiteral(value: Float): String = "${value}f"
}
