package dev.wildware.udea.assets.compiler.edit

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import dev.wildware.udea.assets.compiler.AssetCompiler
import dev.wildware.udea.assets.compiler.scan.KtParser
import dev.wildware.udea.assets.compiler.scan.LineIndex
import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import dev.wildware.udea.diagnostics.SourceSpan
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtScriptInitializer
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readBytes

/** What [AssetSources.create] would write, or why it will not. */
public sealed class CreateResult {

    /** The new script: [text] for [file], declaring [id]. Nothing is on disk until the caller writes it. */
    public class Created(
        public val id: String,
        public val file: Path,
        /** [file], repo-relative and `/`-separated. */
        public val path: String,
        public val text: String,
    ) : CreateResult() {
        override fun toString(): String = "Created($id at $file)"
    }

    public data class Refused(val message: String) : CreateResult()
}

/**
 * The asset scripts under one asset root, read for editing (issue #195): which asset is declared
 * where, what each of its fields is written as, and which of them the editor may save.
 *
 * ## Syntactic, like pass 1
 *
 * Everything here is PSI from pass 1's [KtParser]: no classpath, no compile, no evaluation. That is
 * what makes a save exact. Pass 2 evaluates a script and knows `scale` is `1.58` but not where that
 * number is written; pass 1 knows the span of every argument, which is the only thing a save can
 * replace without touching the rest of the file. And it is why a computed value is read-only: the
 * text at its span is `soldierScale`, and writing `1.6F` there would change what the file *says*,
 * not the one value the editor showed.
 *
 * Ids are pass 1's own - [UdeaDeclarationScanner] computes them and this class finds the call each
 * one names - so an id here is the id a build, the daemon and `assets.list` use.
 *
 * ## Where it runs
 *
 * Wherever the asset daemon does: `udea-assets-compiler` carries `kotlin-compiler-embeddable`, so this
 * is on no shipped classpath (`UDEA-MG-005`), only on a game's `agent` and `editor` source sets and
 * on build tooling.
 *
 * Not thread-safe, for [UdeaDeclarationScanner]'s reason: PSI is not.
 */
public class AssetSources(
    repoRoot: Path,
    assetRoot: Path,
    private val scanner: UdeaDeclarationScanner = UdeaDeclarationScanner(repoRoot, assetRoot),
    private val parser: KtParser = KtParser.shared,
) {

    private val repoRoot: String = repoRoot.toAbsolutePath().normalize().toString()
    private val assetRoot: Path = assetRoot.toAbsolutePath().normalize()

    /** Every script under the asset root, sorted. */
    internal fun scripts(): List<Path> = AssetCompiler.scriptsUnder(assetRoot).map { it.toAbsolutePath().normalize() }

    /** The script that declares [id], by pass 1, or `null` when none does. */
    public fun fileOf(id: String): Path? =
        scripts().firstOrNull { file -> scanner.scanFile(file).declarations.any { it.id == id } }

    /** [id]'s declaring call, read from its script as it is on disk now; `null` when no script declares it. */
    public fun read(id: String): AssetSource? {
        val file = fileOf(id) ?: return null
        return read(file, String(file.readBytes(), Charsets.UTF_8), id)
    }

    /** [id]'s declaring call in [text], read as the contents of [file]; `null` when [text] does not declare it. */
    internal fun read(file: Path, text: String, id: String): AssetSource? {
        val absolute = file.toAbsolutePath().normalize()
        val declaration = scanner.scanSource(absolute, text.toByteArray(Charsets.UTF_8)).declarations
            .firstOrNull { it.id == id } ?: return null
        val normalized = UdeaDeclarationScanner.normalizeLineEndings(text)
        val lines = LineIndex(normalized)
        val ktFile = parser.parse(absolute.name, normalized)
        val call = PsiTreeUtil.collectElementsOfType(ktFile, KtCallExpression::class.java).firstOrNull { call ->
            val callee = call.calleeExpression ?: return@firstOrNull false
            val offset = callee.textRange.startOffset
            lines.lineOf(offset) == declaration.span.startLine && lines.columnOf(offset) == declaration.span.startColumn
        } ?: return null
        val path = relative(absolute)
        val reader = FieldReader(
            path = path,
            lines = lines,
            constants = fileConstants(ktFile.script?.blockExpression?.statements.orEmpty()),
            rawOffset = RawOffsets(text),
        )
        val fields = call.valueArguments.mapIndexed { index, argument -> reader.field(index, argument) }
        return AssetSource(declaration.id, declaration.kind, absolute, path, fields, text)
    }

    /**
     * A new script declaring an asset called [name]: the kind and values of [fromId], written
     * beside it with KotlinPoet (issue #195). Nothing is written to disk.
     *
     * Every value is a plain literal in the new file. One that [fromId]'s file wrote as the name of
     * a constant holding a literal is written as that literal, since the new file does not have the
     * constant. Any other value - a `mapOf(...)`, a block, arithmetic - refuses the whole copy,
     * naming the field, what builds it and its line: guessing at a rewrite of an expression is how
     * a generated file comes to mean something its template did not.
     */
    public fun create(fromId: String, name: String): CreateResult {
        if (!PLAIN_NAME.matches(name)) {
            return CreateResult.Refused("`$name` is not a plain asset name: use letters, digits, `_` and `-` only")
        }
        val template = read(fromId) ?: return CreateResult.Refused("no script declares `$fromId`")
        val prefix = template.file.parent.let { if (it == assetRoot) "" else "${assetRoot.relativize(it).toString().replace('\\', '/')}/" }
        val id = "$prefix$name"
        fileOf(id)?.let { return CreateResult.Refused("`$id` already exists, in ${relative(it)}") }
        val file = template.file.resolveSibling("$name${UdeaDeclarationScanner.SCRIPT_SUFFIX}")
        if (file.exists()) return CreateResult.Refused("${relative(file)} already exists")

        val call = CodeBlock.builder().add("%N(\n", template.kind).indent()
        for (field in template.fields) {
            val value = when {
                field.name == NAME_ARGUMENT -> LiteralValue.StringValue(name)
                else -> when (val editability = field.editability) {
                    is Editability.Editable -> editability.value
                    is Editability.ReadOnly -> editability.constant ?: return CreateResult.Refused(
                        "`${field.name}` is ${editability.reason.message}, and a new asset copies plain values only",
                    )
                }
            }
            call.add("%N = %L,\n", field.name, value.expression())
        }
        call.unindent().add(")\n")
        val script = FileSpec.scriptBuilder(file.name.removeSuffix(KTS_EXTENSION))
            .indent(INDENT)
            .addFileComment("Created by the Udea editor from `%L`.", fromId)
            .addCode(call.build())
            .build()
        return CreateResult.Created(id, file, relative(file), script.toString())
    }

    private fun relative(file: Path): String = SourceSpan.relativize(repoRoot, file.toAbsolutePath().normalize().toString())

    /** The script's top-level `val`s and `var`s, by name. */
    private fun fileConstants(statements: List<KtExpression>): Map<String, KtProperty> =
        statements.mapNotNull { (it as? KtScriptInitializer)?.body ?: it }
            .filterIsInstance<KtProperty>()
            .filter { it.name != null }
            .associateBy { it.name!! }

    /** Reads one argument into a [FieldSite]. */
    private class FieldReader(
        private val path: String,
        private val lines: LineIndex,
        private val constants: Map<String, KtProperty>,
        private val rawOffset: RawOffsets,
    ) {

        fun field(index: Int, argument: KtValueArgument): FieldSite {
            val expression = checkNotNull(argument.getArgumentExpression()) { "an argument with no expression: ${argument.text}" }
            val name = argument.getArgumentName()?.asName?.asString() ?: "$POSITIONAL ${index + 1}"
            val editability = when {
                name == NAME_ARGUMENT -> Editability.ReadOnly(
                    ReadOnlyReason("the asset's name, which is its id, on line ${lineOf(expression)}", lineOf(expression)),
                )
                argument.getArgumentName() == null -> Editability.ReadOnly(
                    ReadOnlyReason("passed by position on line ${lineOf(expression)}, so the file does not name its field", lineOf(expression)),
                )
                else -> editabilityOf(expression)
            }
            // A reference's save replaces its string literal only; everything else, the whole value.
            val written: KtExpression = (literalOf(expression) as? LiteralValue.ReferenceValue)
                ?.let { (expression as KtCallExpression).valueArguments.single().getArgumentExpression() }
                ?: expression
            val range = expression.textRange
            return FieldSite(
                name = name,
                text = expression.text,
                span = SourceSpan(
                    path,
                    lines.lineOf(range.startOffset),
                    lines.columnOf(range.startOffset),
                    lines.lineOf(range.endOffset),
                    lines.columnOf(range.endOffset),
                ),
                editability = editability,
                start = rawOffset.of(written.textRange.startOffset),
                end = rawOffset.of(written.textRange.endOffset),
            )
        }

        private fun editabilityOf(expression: KtExpression): Editability {
            literalOf(expression)?.let { return Editability.Editable(it) }
            val line = lineOf(expression)
            return when (expression) {
                is KtNameReferenceExpression -> {
                    val name = expression.getReferencedName()
                    val property = constants[name]
                    if (property == null) {
                        Editability.ReadOnly(ReadOnlyReason("set by `$name` on line $line, which this file does not declare", line))
                    } else {
                        val keyword = if (property.isVar) "var" else "val"
                        val declared = lineOf(property)
                        Editability.ReadOnly(
                            ReadOnlyReason("set by `$keyword $name` on line $declared", declared),
                            constant = if (property.isVar) null else property.initializer?.let(::literalOf),
                        )
                    }
                }
                is KtCallExpression -> Editability.ReadOnly(
                    ReadOnlyReason("built by `${expression.calleeExpression?.text ?: "a call"}(...)` on line $line", line),
                )
                is KtLambdaExpression -> Editability.ReadOnly(ReadOnlyReason("built by a block on line $line", line))
                else -> Editability.ReadOnly(ReadOnlyReason("computed by `${shortText(expression)}` on line $line", line))
            }
        }

        private fun lineOf(element: PsiElement): Int = lines.lineOf(element.textRange.startOffset)
    }

    /**
     * Where each offset of the parser's line-ending-normalised text is in the file's raw text.
     *
     * The parser only ever sees `\n` ([UdeaDeclarationScanner.normalizeLineEndings]), but a save
     * writes into the file as it is, and a file with `\r\n` has one more character per line than
     * the parser counted. Without this a save into a Windows-ended file lands a line's worth of
     * characters early per line above it.
     */
    private class RawOffsets(raw: String) {

        private val map: IntArray? = if ('\r' !in raw) {
            null
        } else {
            val offsets = ArrayList<Int>(raw.length + 1)
            var i = 0
            while (i < raw.length) {
                offsets += i
                i += if (raw[i] == '\r' && i + 1 < raw.length && raw[i + 1] == '\n') 2 else 1
            }
            offsets += raw.length
            offsets.toIntArray()
        }

        fun of(normalized: Int): Int = map?.get(normalized) ?: normalized
    }

    private companion object {
        const val NAME_ARGUMENT = "name"
        const val POSITIONAL = "argument"
        const val KTS_EXTENSION = ".kts"
        const val INDENT = "    "

        /** What `create` accepts as a name: nothing that could climb out of the directory or need quoting. */
        val PLAIN_NAME = Regex("[A-Za-z0-9_-]+")

        /** The longest expression text a read-only reason quotes before it shortens it. */
        const val QUOTED = 40

        const val REFERENCE_CALLEE = "reference"

        fun shortText(expression: KtExpression): String {
            val first = expression.text.lineSequence().first()
            return if (first.length > QUOTED || first != expression.text) first.take(QUOTED).trimEnd() + "..." else first
        }

        /**
         * The plain literal [expression] is, or `null`: a number (negative included), a string
         * with no template in it, `true`/`false`, or `reference("...")` of such a string.
         */
        fun literalOf(expression: KtExpression): LiteralValue? = when (expression) {
            is KtConstantExpression -> constantOf(expression.node.elementType, expression.text, negative = false)
            is KtPrefixExpression -> {
                val base = expression.baseExpression as? KtConstantExpression
                if (expression.operationToken == KtTokens.MINUS && base != null) {
                    constantOf(base.node.elementType, base.text, negative = true)
                } else {
                    null
                }
            }
            is KtStringTemplateExpression -> plainString(expression)?.let(LiteralValue::StringValue)
            is KtCallExpression -> {
                val only = expression.valueArguments.singleOrNull()
                val string = only?.takeIf { it.getArgumentName() == null }?.getArgumentExpression() as? KtStringTemplateExpression
                if (expression.calleeExpression?.text == REFERENCE_CALLEE && expression.lambdaArguments.isEmpty() && string != null) {
                    plainString(string)?.let(LiteralValue::ReferenceValue)
                } else {
                    null
                }
            }
            else -> null
        }

        private fun constantOf(type: IElementType, text: String, negative: Boolean): LiteralValue? {
            val sign = if (negative) "-" else ""
            val digits = text.replace("_", "")
            return when (type) {
                KtNodeTypes.BOOLEAN_CONSTANT -> if (negative) null else LiteralValue.BooleanValue(text == "true")
                KtNodeTypes.INTEGER_CONSTANT -> when {
                    digits.startsWith("0x", ignoreCase = true) || digits.startsWith("0b", ignoreCase = true) -> null
                    digits.endsWith("L") -> (sign + digits.removeSuffix("L")).toLongOrNull()?.let(LiteralValue::LongValue)
                    else -> (sign + digits).toIntOrNull()?.let(LiteralValue::IntValue)
                        ?: (sign + digits).toLongOrNull()?.let(LiteralValue::LongValue)
                }
                KtNodeTypes.FLOAT_CONSTANT -> if (digits.endsWith("f") || digits.endsWith("F")) {
                    (sign + digits.dropLast(1)).toFloatOrNull()?.let(LiteralValue::FloatValue)
                } else {
                    (sign + digits).toDoubleOrNull()?.let(LiteralValue::DoubleValue)
                }
                else -> null
            }
        }

        /**
         * The value of a `"..."` with no `$` template in it, escapes decoded; `null` for anything
         * else, raw `"""` strings included - their text is not escaped the same way, and a save
         * would have to choose which form to write back.
         */
        private fun plainString(template: KtStringTemplateExpression): String? {
            if (template.text.startsWith("\"\"\"")) return null
            val out = StringBuilder()
            for (entry in template.entries) {
                when (entry) {
                    is KtLiteralStringTemplateEntry -> out.append(entry.text)
                    is KtEscapeStringTemplateEntry -> out.append(entry.unescapedValue)
                    else -> return null
                }
            }
            return out.toString()
        }
    }
}
