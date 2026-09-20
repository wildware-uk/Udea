import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import java.util.concurrent.TimeUnit

plugins {
    // THE iOS SWITCH. udea-core has iOS targets since Fleks was vendored (issue #215), but this
    // module does not yet: `webSocketEngine` is an `expect` with `actual`s for the socket targets
    // and Wasm only, so an iOS target fails to compile. With a native engine for it, this line
    // becomes `id("dev.wildware.udea.kotlin-multiplatform")`, and the `socket` group below gains `withNative()`
    // so the iOS targets get `UdpTransport` too.
    id("udea.kotlin-multiplatform-no-ios")
}

/**
 * `ktor-server-core` asks for kotlin-reflect, which UDEA-MG-005 bans from the shipped game's runtime
 * classpath, and `moba` depends on this module. The WebSocket server's paths - an embedded CIO
 * engine, one route, a lambda module - do not load it, and `WebSocketTransportTest` runs every
 * server test on a classpath without it and asserts that it is absent.
 */
fun ExternalModuleDependency.withoutKotlinReflect() {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
}

kotlin {
    // One shared source set beside the default hierarchy (issue #209, spec section 6): `socketMain`,
    // the targets with operating-system sockets. It holds `UdpTransport` - a browser has no UDP, so
    // Wasm has only the WebSocket transport, which is spec D11 and not a gap - and the CIO engine the
    // WebSocket client runs on there. By platform type, as in `udea-core`: AGP's multiplatform
    // library target is not the `androidTarget()` that `withAndroidTarget()` matches.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("socket") {
                withCompilations {
                    it.target.platformType == KotlinPlatformType.jvm || it.target.platformType == KotlinPlatformType.androidJvm
                }
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":udea-core"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.websockets)
            }
        }
        getByName("socketMain") {
            dependencies {
                // `api`: `UdpTransport.server` and `client` take a Ktor `InetSocketAddress`.
                api(libs.ktor.network)
                implementation(libs.ktor.client.cio)
                implementation(libs.cryptography.core)
                implementation(libs.cryptography.provider.jdk)
                // The lock around the one counter the socket reader and the game loop share.
                implementation(libs.kotlinx.atomicfu)
            }
        }
        wasmJsMain {
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

dependencies {
    // The desktop server's end of `WebSocketTransport` (spec section 6). In this block rather than
    // `kotlin.sourceSets`, whose dependency handler cannot configure a catalog entry's excludes.
    "jvmMainImplementation"(libs.ktor.server.core) { withoutKotlinReflect() }
    "jvmMainImplementation"(libs.ktor.server.cio) { withoutKotlinReflect() }
    "jvmMainImplementation"(libs.ktor.server.websockets) { withoutKotlinReflect() }

    // The Replicator contract's executable specification and the working service doubles
    // `SnapshotService` needs. The networking tests drive a *real* Fleks world through a real
    // snapshot ring rather than a mock of one, because "the ring is the baseline store" (spec
    // 3.1) is only proven by using the ring.
    "jvmTestImplementation"(testFixtures(project(":udea-core")))
}

/**
 * The golden hex fixture pins the bit layout, so it has to be regenerable on purpose and
 * never by accident. `./gradlew :udea-net:jvmTest -Dupdate.goldens=true` rewrites it; without
 * the flag a layout change is a failing diff.
 *
 * Gradle has no `--update-goldens` CLI option for a plain `Test` task, so the flag is a
 * system property. `udea.projectDir` gives the test the source path to rewrite, which the
 * classpath alone cannot provide.
 */
val updateGoldens: Provider<String> = providers.systemProperty("update.goldens").orElse("false")

tasks.withType<Test>().configureEach {
    systemProperty("udea.projectDir", projectDir.absolutePath)
    systemProperty("update.goldens", updateGoldens.get())
}


// --- The JVM server the Wasm client test replicates from (issue #209) -----------------------------
//
// A Wasm test cannot start a JVM, so the task that runs it does: `wasmJsNodeTest` asks the service
// below for a running `WebSocketSnapshotServer` before its tests start, and passes the server's URL
// in the environment. The service is closed when the build finishes, pass or fail, and closing it
// kills the process. The server also exits by itself after its lifetime in ticks, so a Gradle daemon
// killed outright leaves nothing listening for longer than that.

/** Starts `WebSocketSnapshotServer`'s `main` once per build and kills it when the build ends. */
abstract class WebSocketSnapshotServerProcess : BuildService<WebSocketSnapshotServerProcess.Params>, AutoCloseable {

    interface Params : BuildServiceParameters {
        /** Where the server writes its URL once it is listening. */
        val urlFile: RegularFileProperty

        /** The server's own output, for when it does not start. */
        val log: RegularFileProperty

        /** Ticks the server serves before exiting by itself. */
        val lifetimeTicks: Property<Long>
    }

    private var process: Process? = null

    /**
     * The server's URL, starting it on [classpath] the first time.
     *
     * On the JVM running this build: the build needs 21 (`AGENTS.md`), which is also the toolchain
     * the server's classes are compiled for.
     */
    @Synchronized
    fun url(classpath: String): String {
        val urlFile = parameters.urlFile.get().asFile
        val log = parameters.log.get().asFile
        val running = process ?: run {
            urlFile.delete()
            urlFile.parentFile.mkdirs()
            ProcessBuilder(
                File(System.getProperty("java.home"), "bin/java").absolutePath,
                "-cp",
                classpath,
                "dev.wildware.udea.net.transport.WebSocketSnapshotServer",
                urlFile.absolutePath,
                parameters.lifetimeTicks.get().toString(),
            ).redirectErrorStream(true).redirectOutput(log).start().also { process = it }
        }
        // A deadline on a process starting, not a latency budget.
        repeat(STARTUP_POLLS) {
            if (urlFile.isFile) return urlFile.readText().trim()
            check(running.isAlive) { "the WebSocket snapshot server exited with ${running.exitValue()}; see $log" }
            running.waitFor(STARTUP_POLL_MILLIS, TimeUnit.MILLISECONDS)
        }
        error("the WebSocket snapshot server did not start listening; see $log")
    }

    override fun close() {
        val running = process ?: return
        running.destroy()
        if (!running.waitFor(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) running.destroyForcibly()
    }

    private companion object {
        const val STARTUP_POLLS = 600
        const val STARTUP_POLL_MILLIS = 100L
        const val STOP_WAIT_SECONDS = 5L
    }
}

val snapshotServer: Provider<WebSocketSnapshotServerProcess> = gradle.sharedServices.registerIfAbsent(
    "udeaNetWebSocketSnapshotServer",
    WebSocketSnapshotServerProcess::class,
) {
    parameters.urlFile.set(layout.buildDirectory.file("udea/websocket-snapshot-server/url"))
    parameters.log.set(layout.buildDirectory.file("udea/websocket-snapshot-server/server.log"))
    // Ten minutes at 60Hz.
    parameters.lifetimeTicks.set(36_000L)
}

val jvmTestCompilation = (the<KotlinMultiplatformExtension>().targets.getByName("jvm") as KotlinJvmTarget)
    .compilations.getByName("test")

tasks.named<KotlinJsTest>("wasmJsNodeTest") {
    val serverClasspath: FileCollection =
        files(jvmTestCompilation.output.allOutputs, jvmTestCompilation.runtimeDependencyFiles)
    // The server is half of what this task tests, so a change to it reruns the task.
    inputs.files(serverClasspath).withPropertyName("webSocketSnapshotServerClasspath")
    usesService(snapshotServer)
    val server = snapshotServer
    doFirst {
        environment("UDEA_WS_SNAPSHOT_URL", server.get().url(serverClasspath.asPath))
    }
}

// The one-line description Maven Central requires of a published artifact (issue #265).
description =
    "Udea's networking: transports, baselines, relevancy, client prediction and remote calls " +
    "over the same generated replicators that snapshots use."
