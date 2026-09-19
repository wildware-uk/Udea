package dev.wildware.udea.build.determinism

import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wall clocks multiplatform common code can actually reach (issue #217).
 *
 * `System.nanoTime` does not resolve in `commonMain`, so a scan that only knows the JVM's clocks
 * polices the one nobody can call there and passes the ones they can: `TimeSource.Monotonic` and
 * `Clock.System`. With the rules as they were, a `TimeSource.Monotonic.markNow()` and a
 * `Clock.System.now()` planted in `udea-core`'s `commonMain` produced `findings: 0`.
 *
 * ## Why these fixtures are written with ASM rather than `javac`
 *
 * [FixtureCompiler] transliterates Kotlin into Java, which works while the two emit the same
 * reference. Here they cannot: `markNow` returns an inline value class, so Kotlin calls it by its
 * mangled JVM name `markNow-z9LOYto`, and a hyphen is not a Java identifier. So each fixture below
 * writes the field and method references `kotlinc` 2.4.20 emitted for the same Kotlin planted in
 * `udea-core`'s `commonMain` and read back with `javap -c` - mangled names, owners, descriptors and
 * the value-class boxing that follows a read, which a rule must not mistake for a second clock.
 * What is left out is what no rule reads: parameter null checks and stack map frames.
 *
 * The classes are laid out the way a multiplatform module lays them out - sources under
 * `src/commonMain/kotlin`, bytecode under `build/classes/kotlin/jvm/main` - and scanned through
 * [DeterminismLayout] against the real declared scopes, which is the path `udeaVerifyDeterminism`
 * takes.
 */
class KotlinClockTest {

    @TempDir
    lateinit var repo: File

    /** One JVM instruction a fixture method is made of, as `javap -c` prints it. */
    private sealed interface Insn {
        data class GetStatic(val owner: String, val name: String, val descriptor: String) : Insn
        data class InvokeVirtual(val owner: String, val name: String, val descriptor: String) : Insn
        data class InvokeStatic(val owner: String, val name: String, val descriptor: String) : Insn
        data class InvokeInterface(val owner: String, val name: String, val descriptor: String) : Insn
        data class LoadLong(val slot: Int) : Insn
        data class LoadObject(val slot: Int) : Insn
    }

    /** A static method: its name, descriptor, the source line it is written on, and its body. */
    private data class Method(val name: String, val descriptor: String, val line: Int, val body: List<Insn>)

    private val monotonic = "kotlin/time/TimeSource\$Monotonic"
    private val valueTimeMark = "kotlin/time/TimeSource\$Monotonic\$ValueTimeMark"

    /** `TimeSource.Monotonic.markNow()`, exactly as kotlinc compiles it. */
    private fun markNow(line: Int) = Method(
        "plantedMonotonic", "()Ljava/lang/Object;", line,
        listOf(
            Insn.GetStatic(monotonic, "INSTANCE", "L$monotonic;"),
            Insn.InvokeVirtual(monotonic, "markNow-z9LOYto", "()J"),
            Insn.InvokeStatic(valueTimeMark, "box-impl", "(J)L$valueTimeMark;"),
        ),
    )

    /** `Clock.System.now()` for the given `Clock` owner, exactly as kotlinc compiles it. */
    private fun systemNow(clockPackage: String, line: Int) = Method(
        "plantedSystemClock", "()Ljava/lang/Object;", line,
        listOf(
            Insn.GetStatic("$clockPackage/Clock\$System", "INSTANCE", "L$clockPackage/Clock\$System;"),
            Insn.InvokeVirtual("$clockPackage/Clock\$System", "now", "()L$clockPackage/Instant;"),
        ),
    )

    /**
     * Writes a Kotlin-shaped class and its source into `<module>/` in multiplatform layout, and
     * scans every declared simulation scope the way the task does.
     */
    private fun plant(
        module: String,
        className: String,
        sourceLines: List<String>,
        methods: List<Method>,
    ): ScanResult {
        val internalName = className.replace('.', '/')
        val sourceName = internalName.substringAfterLast('/').removeSuffix("Kt") + ".kt"
        val packagePath = internalName.substringBeforeLast('/')
        repo.resolve("$module/src/commonMain/kotlin/$packagePath/$sourceName").apply {
            parentFile.mkdirs()
            writeText(sourceLines.joinToString("\n", postfix = "\n"))
        }
        repo.resolve("$module/build/classes/kotlin/jvm/main/$internalName.class").apply {
            parentFile.mkdirs()
            writeBytes(classBytes(internalName, sourceName, methods))
        }
        val project = ":" + module.replace('/', ':')
        val inputs = DeterminismRules.SIMULATION_SCOPES
            .filter { it.project == project }
            .map { DeterminismLayout.scopeInput(repo, it) }
        assertEquals(1, inputs.size, "no declared simulation scope for $project")
        return DeterminismScan.run(inputs = inputs, allowlist = Allowlist.parse(""), repoRoot = repo)
    }

    private fun classBytes(internalName: String, sourceName: String, methods: List<Method>): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, internalName, null, "java/lang/Object", null)
        writer.visitSource(sourceName, null)
        methods.forEach { method ->
            val params = Type.getArgumentTypes(method.descriptor)
            val mv = writer.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                method.name,
                method.descriptor,
                null,
                null,
            )
            mv.visitCode()
            val start = Label()
            mv.visitLabel(start)
            mv.visitLineNumber(method.line, start)
            method.body.forEach { mv.emit(it) }
            mv.visitInsn(Type.getReturnType(method.descriptor).getOpcode(Opcodes.IRETURN))
            mv.visitMaxs(0, params.sumOf { it.size })
            mv.visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun MethodVisitor.emit(insn: Insn) {
        when (insn) {
            is Insn.GetStatic -> visitFieldInsn(Opcodes.GETSTATIC, insn.owner, insn.name, insn.descriptor)
            is Insn.InvokeVirtual -> visitMethodInsn(Opcodes.INVOKEVIRTUAL, insn.owner, insn.name, insn.descriptor, false)
            is Insn.InvokeStatic -> visitMethodInsn(Opcodes.INVOKESTATIC, insn.owner, insn.name, insn.descriptor, false)
            is Insn.InvokeInterface -> visitMethodInsn(Opcodes.INVOKEINTERFACE, insn.owner, insn.name, insn.descriptor, true)
            is Insn.LoadLong -> visitVarInsn(Opcodes.LLOAD, insn.slot)
            is Insn.LoadObject -> visitVarInsn(Opcodes.ALOAD, insn.slot)
        }
    }

    private fun ScanResult.rendered(): String = findings.joinToString("\n") { it.render() }

    @Test
    fun `TimeSource Monotonic markNow planted in udea-core commonMain fails the scan as DET001`() {
        val result = plant(
            module = "udea-core",
            className = "dev.wildware.udea.core.planted.PlantedClocksKt",
            sourceLines = listOf(
                "package dev.wildware.udea.core.planted",
                "",
                "import kotlin.time.TimeSource",
                "",
                "internal fun plantedMonotonic(): Any = TimeSource.Monotonic.markNow()",
            ),
            methods = listOf(markNow(line = 5)),
        )

        assertTrue(result.failed, "the scan passed a monotonic clock read:\n${DeterminismScan.report(result)}")
        assertEquals(setOf("DET001"), result.findings.map { it.ruleId }.toSet(), result.rendered())
        assertEquals(
            setOf("kotlin.time.TimeSource\$Monotonic.INSTANCE", "kotlin.time.TimeSource\$Monotonic.markNow-z9LOYto"),
            result.findings.map { it.target }.toSet(),
            "boxing the mark is not a second clock read; the finding is the source and the read",
        )
        result.findings.forEach {
            assertEquals("udea-core/src/commonMain/kotlin/dev/wildware/udea/core/planted/PlantedClocks.kt:5:1", it.span)
            assertEquals("plantedMonotonic", it.method)
            assertTrue(it.didYouMean.contains("SimClock.tick"), it.didYouMean)
        }
    }

    @Test
    fun `kotlin time Clock System now planted in udea-core commonMain fails the scan as DET003`() {
        val result = plant(
            module = "udea-core",
            className = "dev.wildware.udea.core.planted.PlantedClocksKt",
            sourceLines = listOf(
                "package dev.wildware.udea.core.planted",
                "",
                "import kotlin.time.Clock",
                "",
                "internal fun plantedSystemClock(): Any = Clock.System.now()",
            ),
            methods = listOf(systemNow("kotlin/time", line = 5)),
        )

        assertTrue(result.failed, "the scan passed a calendar clock read:\n${DeterminismScan.report(result)}")
        assertEquals(
            listOf("DET003" to "kotlin.time.Clock\$System.INSTANCE", "DET003" to "kotlin.time.Clock\$System.now"),
            result.findings.map { it.ruleId to it.target }.sortedBy { it.second },
            result.rendered(),
        )
        result.findings.forEach {
            assertEquals("udea-core/src/commonMain/kotlin/dev/wildware/udea/core/planted/PlantedClocks.kt:5:1", it.span)
        }
    }

    /**
     * kotlinx-datetime before 0.7 has its own `Clock.System`; from 0.7 its `Clock` is a deprecated
     * alias of `kotlin.time.Clock` and compiles to the stdlib owner above. Neither is on this
     * repository's classpath today, which is exactly when a rule for it costs nothing.
     */
    @Test
    fun `kotlinx datetime Clock System now fails the scan as DET003`() {
        val result = plant(
            module = "udea-gas",
            className = "dev.wildware.udea.gas.planted.PlantedClocksKt",
            sourceLines = listOf(
                "package dev.wildware.udea.gas.planted",
                "",
                "import kotlinx.datetime.Clock",
                "",
                "internal fun plantedSystemClock(): Any = Clock.System.now()",
            ),
            methods = listOf(systemNow("kotlinx/datetime", line = 5)),
        )

        assertEquals(
            setOf("kotlinx.datetime.Clock\$System.INSTANCE", "kotlinx.datetime.Clock\$System.now"),
            result.findings.filter { it.ruleId == "DET003" }.map { it.target }.toSet(),
            result.rendered(),
        )
    }

    /**
     * A mark handed in from somewhere else is still read against the wall clock when asked how
     * much time has passed. `measureTime { }` is inline, and kotlinc expands it to exactly the
     * `markNow` above followed by the `elapsedNow` here.
     */
    @Test
    fun `asking a monotonic mark how much time has passed is a wall-clock read`() {
        val result = plant(
            module = "udea-core",
            className = "dev.wildware.udea.core.planted.PlantedClocksKt",
            sourceLines = listOf(
                "package dev.wildware.udea.core.planted",
                "",
                "import kotlin.time.Duration",
                "import kotlin.time.TimeSource",
                "",
                "internal fun plantedElapsed(mark: TimeSource.Monotonic.ValueTimeMark): Any = mark.elapsedNow()",
                "internal fun plantedPassed(mark: TimeSource.Monotonic.ValueTimeMark): Boolean = mark.hasPassedNow()",
                "internal fun plantedNotPassed(mark: TimeSource.Monotonic.ValueTimeMark): Boolean = mark.hasNotPassedNow()",
                "internal fun plantedLater(mark: TimeSource.Monotonic.ValueTimeMark, by: Duration): Any = mark + by",
            ),
            methods = listOf(
                Method(
                    "plantedElapsed-6eNON_k", "(J)Ljava/lang/Object;", 6,
                    listOf(
                        Insn.LoadLong(0),
                        Insn.InvokeStatic(valueTimeMark, "elapsedNow-UwyO8pc", "(J)J"),
                        Insn.InvokeStatic("kotlin/time/Duration", "box-impl", "(J)Lkotlin/time/Duration;"),
                    ),
                ),
                Method(
                    "plantedPassed-6eNON_k", "(J)Z", 7,
                    listOf(Insn.LoadLong(0), Insn.InvokeStatic(valueTimeMark, "hasPassedNow-impl", "(J)Z")),
                ),
                Method(
                    "plantedNotPassed-6eNON_k", "(J)Z", 8,
                    listOf(Insn.LoadLong(0), Insn.InvokeStatic(valueTimeMark, "hasNotPassedNow-impl", "(J)Z")),
                ),
                Method(
                    "plantedLater-teoq2JE", "(JJ)Ljava/lang/Object;", 9,
                    listOf(
                        Insn.LoadLong(0),
                        Insn.LoadLong(2),
                        Insn.InvokeStatic(valueTimeMark, "plus-LRDsOJo", "(JJ)J"),
                        Insn.InvokeStatic(valueTimeMark, "box-impl", "(J)L$valueTimeMark;"),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                "plantedElapsed-6eNON_k" to "elapsedNow-UwyO8pc",
                "plantedNotPassed-6eNON_k" to "hasNotPassedNow-impl",
                "plantedPassed-6eNON_k" to "hasPassedNow-impl",
            ),
            result.findings.map { it.method to it.target.substringAfterLast('.') }.sortedBy { it.first },
            "reading a mark against now is a clock read; adding a duration to one is arithmetic:\n" +
                result.rendered(),
        )
        assertTrue(result.findings.all { it.ruleId == "DET001" }, result.rendered())
    }

    /**
     * The controls. A `TimeSource` or `Clock` received through its interface may be a deterministic
     * one driven by the tick - the scan cannot see the receiver, and the place a wall clock gets
     * *chosen* is flagged by the tests above. And presentation keeps its clocks: `udea-audio` seeds
     * its mixer from `Clock.System`, and `moba`'s HUD is outside its declared prefixes.
     */
    @Test
    fun `an interface-typed clock and a clock outside the declared prefixes are not findings`() {
        val throughInterface = plant(
            module = "udea-core",
            className = "dev.wildware.udea.core.planted.InjectedKt",
            sourceLines = listOf(
                "package dev.wildware.udea.core.planted",
                "",
                "internal fun mark(source: kotlin.time.TimeSource): Any = source.markNow()",
                "internal fun now(clock: kotlin.time.Clock): Any = clock.now()",
            ),
            methods = listOf(
                Method(
                    "mark", "(Lkotlin/time/TimeSource;)Ljava/lang/Object;", 3,
                    listOf(
                        Insn.LoadObject(0),
                        Insn.InvokeInterface("kotlin/time/TimeSource", "markNow", "()Lkotlin/time/TimeMark;"),
                    ),
                ),
                Method(
                    "now", "(Lkotlin/time/Clock;)Ljava/lang/Object;", 4,
                    listOf(
                        Insn.LoadObject(0),
                        Insn.InvokeInterface("kotlin/time/Clock", "now", "()Lkotlin/time/Instant;"),
                    ),
                ),
            ),
        )
        assertEquals("", throughInterface.rendered())

        val presentation = plant(
            module = "moba/game",
            className = "dev.wildware.moba.hud.HudClockKt",
            sourceLines = listOf(
                "package dev.wildware.moba.hud",
                "",
                "import kotlin.time.Clock",
                "",
                "internal fun plantedSystemClock(): Any = Clock.System.now()",
            ),
            methods = listOf(systemNow("kotlin/time", line = 5), markNow(line = 5)),
        )
        assertEquals("", presentation.rendered())
        assertTrue(presentation.scannedClasses.getValue(":moba:game") > 0, "the control scanned nothing")
    }
}
