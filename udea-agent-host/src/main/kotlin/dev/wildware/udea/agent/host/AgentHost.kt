package dev.wildware.udea.agent.host

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentSubmission
import dev.wildware.udea.agent.AgentThreads
import dev.wildware.udea.agent.activity.AgentSessionId
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.agent.Json
import dev.wildware.udea.core.host.RenderMode
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The `game-bridge-mcp` HTTP surface, served from a JDK [HttpServer] on loopback.
 *
 * ## What makes this a debug surface and not a network service
 *
 * Three things, and all three are structural rather than configuration:
 *
 * 1. **It binds 127.0.0.1 and there is no way to ask for anything else.** No host parameter, no
 *    config key, no `0.0.0.0` path. [LOOPBACK] is a constant and [start] hard-codes it, so there
 *    is no line anybody can change in a hurry that exposes a remote-control channel into a live
 *    game to a LAN.
 * 2. **It is off unless a JVM argument turns it on.** [startIfRequested] requires
 *    `-Dudea.agent.port` *and* [BuildFlags.AGENT_ALLOWED]; there is deliberately no environment
 *    variable, because an end user's launcher script sets environment variables and does not
 *    normally pass `-D`.
 * 3. **The classes are absent from a release build.** `udeaVerifyRelease` fails the release
 *    assemble if `dev/wildware/udea/agent/` appears in the packaged jar. The flag removes the
 *    code path; the missing classes make the flag irrelevant. Either alone has failed in prior
 *    art (`FruitGameKTX`'s `DebugHttpServer` gates on a system property and ships in the game).
 *
 * ## The HTTP thread touches `AgentBridge` and nothing else
 *
 * No handler here can reach `World`, `Body`, `Stage` or `Gdx.*` - not by rule, but because this
 * class holds an [AgentBridge] and the module has none of those on its classpath. `/command`
 * queues onto the bridge and answers the moment the command is *queued*; the command runs later,
 * on the simulation thread, inside a `SimBarrier` drain. That is the single most important
 * property of the contract and it is why there is no synchronous wait here: confirmation is the
 * caller polling `/state` for `completedCommandId >= commandId`.
 *
 * ## Every thread is a daemon
 *
 * The executor is a cached pool over [AgentThreads.daemonFactory], never `executor = null`.
 * `HttpServer`'s default dispatch thread is **non-daemon**, and that one default wedged six
 * automated sessions in the reference implementation: the game died, the main thread returned,
 * and the JVM stayed alive holding the port with a `/health` that answered `ok:true` behind a
 * world that no longer existed. See [AgentThreads] for the measured failure.
 */
public class AgentHost private constructor(
    private val server: HttpServer,
    private val executor: ExecutorService,
    private val bridge: AgentBridge,
    private val config: AgentHostConfig,
) {

    /** The port actually bound. Resolved, so a `port = 0` request reports what the OS chose. */
    public val port: Int = server.address.port

    private val running = AtomicBoolean(true)

    private val shutdownHook = Thread({ stop() }, "udea-agent-host-shutdown").apply { isDaemon = false }

    /** Whether this host is still serving. */
    public val isRunning: Boolean get() = running.get()

    /**
     * Stops the server, withdraws the registry entry and shuts the executor down. Idempotent.
     *
     * `HttpServer.stop(0)` rather than a grace period: an in-flight `/state` read is a snapshot
     * of a world that is going away, and a caller waiting on it gets a closed connection, which
     * it can tell apart from a stall. A grace period here would delay JVM exit for a read whose
     * answer is already worthless.
     */
    public fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (_: IllegalStateException) {
            // Already shutting down; the hook is running and must not remove itself.
        }
        config.registry.withdraw()
        server.stop(0)
        executor.shutdownNow()
    }

    private fun install() {
        server.createContext("/health", handler(::health))
        server.createContext("/state", handler(::state))
        server.createContext("/command", handler(::command))
        server.createContext("/tools", handler(::tools))
        server.createContext("/artifact", handler(::artifact))
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    // --- the endpoints -------------------------------------------------------------------

    /**
     * `{"ok":true,"frame":N,"tick":T,"paused":b,"renderMode":"Headless|Offscreen|Windowed"}`.
     *
     * `ok:true` is what marks this port as one of ours; a port answering HTTP with anything else
     * is reported to the agent as "something else has taken this port", which is a different
     * problem with a different fix from "the game isn't running".
     *
     * `frame` **increases for the life of the process**, and the bridge watches it: a `frame` that
     * goes backwards means a new process is answering here, so the cached tool manifest is dropped
     * and rebuild-and-rerun is invisible to the agent. `tick` is deliberately *not* monotonic - a
     * rewind moves it backwards, which is the point of a rewind - and that is exactly why the two
     * are separate fields rather than one.
     *
     * `renderMode` is additive to the contract and is why an agent does not have to call a render
     * tool to find out that the toolset is not live in this process.
     *
     * Discovery sweeps this across a whole port range, so it reads five atomics and allocates one
     * short string. Nothing here can block.
     */
    private fun health(exchange: HttpExchange) {
        respond(
            exchange,
            HTTP_OK,
            Json.render {
                put("ok", true)
                put("frame", bridge.frame)
                put("tick", bridge.tick)
                put("paused", config.paused())
                put("renderMode", config.renderMode.name)
            },
        )
    }

    /**
     * The published digest, verbatim.
     *
     * The handler `get`s a `String` an atomic reference already holds. It does not build the
     * document, does not walk the world, and could not: building it is the simulation thread's
     * job and happens once per publish, gated on whether anybody read the last one.
     */
    private fun state(exchange: HttpExchange) {
        respond(exchange, HTTP_OK, bridge.snapshot())
    }

    /**
     * `GET /command?cmd=spawn&type=cherry&x=-1.5` - fire and forget.
     *
     * The command name is keyed **`cmd`**, not `name`. That is not a style choice: commands
     * routinely take a `name` argument of their own (`follow_entity?name=hero`), and a duplicate
     * query key would silently overwrite the command being invoked. Every other query parameter
     * is passed through as an argument, verbatim, as text.
     *
     * A full queue answers `{"accepted":false,"error":"queue_full"}` with **HTTP 200**, on
     * purpose. A 503 here would be read by a bridge as a sick port and reported as an offline
     * game; 200 with `accepted:false` says the port is healthy and the *simulation* is behind,
     * which is the actionable version of the same fact.
     */
    private fun command(exchange: HttpExchange) {
        val query = parseQuery(exchange.requestURI.rawQuery)
        val name = query[COMMAND_KEY]
        if (name.isNullOrBlank()) {
            respond(
                exchange,
                HTTP_BAD_REQUEST,
                Json.render {
                    put("accepted", false)
                    put("error", "missing_cmd")
                    put(
                        "message",
                        "GET /command needs the command in ?cmd=<name>; every other query " +
                            "parameter is passed to it as an argument. Received: " +
                            query.keys.sorted().joinToString().ifEmpty { "no parameters" },
                    )
                },
            )
            return
        }
        val args = query.filterKeys { it != COMMAND_KEY && it != SESSION_KEY }
        val submission = bridge.submit(AgentCommand(name, args, session = sessionOf(exchange, query)))
        respond(
            exchange,
            // 200 for a rejection too - see the KDoc.
            HTTP_OK,
            Json.render {
                when (submission) {
                    is AgentSubmission.Accepted -> {
                        put("accepted", true)
                        put("commandId", submission.commandId)
                    }

                    is AgentSubmission.Rejected -> {
                        put("accepted", false)
                        put("error", submission.error.kind.id)
                        put("message", submission.error.message)
                    }
                }
                put("frame", bridge.frame)
            },
        )
    }

    /**
     * Who is calling, for the human-facing activity overlay (spec 3.7).
     *
     * `?session=` when the caller named itself, and the remote address otherwise, so two agents
     * driving one instance are two colours in the overlay even when neither has been taught to
     * send the parameter. It is stripped from the arguments a tool sees, exactly as `cmd` is:
     * a tool that could read it could branch on which session it is, and a tool that could
     * *write* it could impersonate another one in a human's panel.
     *
     * The label is interned rather than kept, and the table is bounded, because the value comes
     * from outside - the same reason the command queue is capped.
     */
    private fun sessionOf(exchange: HttpExchange, query: Map<String, String>): AgentSessionId {
        val named = query[SESSION_KEY]
        if (!named.isNullOrBlank()) return config.sessions.intern(named)
        val remote = exchange.remoteAddress ?: return AgentSessionId.LOCAL
        return config.sessions.intern(remote.address?.hostAddress ?: return AgentSessionId.LOCAL)
    }

    /**
     * The tool manifest, or a 404.
     *
     * A 404 here is survivable by contract: the bridge falls back to a built-in manifest of the
     * contract-level tools, reports the instance as `live-no-manifest`, and the agent works
     * through `raw_command`. So a host wired without a manifest is degraded, not broken, and
     * nothing else in this class depends on one existing.
     */
    private fun tools(exchange: HttpExchange) {
        val manifest = config.manifest
        if (manifest == null) {
            respond(
                exchange,
                HTTP_NOT_FOUND,
                Json.render {
                    put("error", "no_manifest")
                    put(
                        "message",
                        "this instance publishes no tool manifest; drive it through raw commands " +
                            "and read /state",
                    )
                },
            )
            return
        }
        respond(exchange, HTTP_OK, manifest.json)
    }

    /**
     * `GET /artifact?id=cap_0007` - the bytes a JSON digest cannot carry.
     *
     * The id is validated against `cap_[0-9]+` **before** any filesystem call, so the endpoint
     * cannot be walked out of its directory: `../../build.gradle.kts` and `cap_0001/../x` are
     * both refused by [ArtifactId.parse] without a `Files` call being made.
     *
     * A 404 carries a JSON body naming the id and saying whether it was evicted or never existed.
     * The shape matters: the bridge distinguishes "nothing there" from "something else took this
     * port", and a bare HTML error page reads as the latter.
     */
    private fun artifact(exchange: HttpExchange) {
        val store = config.artifacts
        val raw = parseQuery(exchange.requestURI.rawQuery)["id"]
        if (store == null) {
            respondNotFound(exchange, raw, "no_artifact_store", "this instance stores no artifacts")
            return
        }
        val id = ArtifactId.parse(raw)
        if (id == null) {
            respondNotFound(
                exchange,
                raw,
                "bad_artifact_id",
                "an artifact id is cap_ followed by digits, for example cap_0007",
            )
            return
        }
        val found = store.get(id)
        if (found == null) {
            val evicted = store.wasEvicted(id)
            respondNotFound(
                exchange,
                raw,
                if (evicted) "artifact_evicted" else "artifact_not_found",
                if (evicted) {
                    "$id was dropped by the artifact LRU; capture again"
                } else {
                    "$id was never stored by this process"
                },
            )
            return
        }
        stream(exchange, found)
    }

    // --- the plumbing --------------------------------------------------------------------

    /**
     * Wraps [body] so nothing escapes into `HttpServer`'s default handling.
     *
     * An unhandled exception in a JDK handler closes the connection with no response at all, and
     * a bridge reads that as a dead port - so a tool that threw would be reported as a crashed
     * game. Here it is a 500 with a typed body, which says the opposite and is true.
     */
    private fun handler(body: (HttpExchange) -> Unit): com.sun.net.httpserver.HttpHandler =
        com.sun.net.httpserver.HttpHandler { exchange ->
            try {
                if (exchange.requestMethod != "GET") {
                    respond(
                        exchange,
                        HTTP_METHOD_NOT_ALLOWED,
                        Json.render {
                            put("error", "method_not_allowed")
                            put("message", "the agent surface is GET only; got ${exchange.requestMethod}")
                        },
                    )
                } else {
                    body(exchange)
                }
            } catch (e: Throwable) {
                try {
                    respond(
                        exchange,
                        HTTP_SERVER_ERROR,
                        Json.render {
                            put("ok", false)
                            put("error", "handler_threw")
                            put("message", "${e.javaClass.simpleName}: ${e.message}")
                        },
                    )
                } catch (_: IOException) {
                    // The client is gone. Nothing to report to and nothing to do.
                }
            } finally {
                exchange.close()
            }
        }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun respondNotFound(exchange: HttpExchange, id: String?, kind: String, message: String) {
        respond(
            exchange,
            HTTP_NOT_FOUND,
            Json.render {
                put("ok", false)
                put("id", id)
                put("error", kind)
                put("evicted", kind == "artifact_evicted")
                put("message", message)
            },
        )
    }

    /** Streams [found] with an accurate `Content-Length`, so a client can size the read. */
    private fun stream(exchange: HttpExchange, found: Artifact) {
        val size = try {
            Files.size(found.path)
        } catch (e: IOException) {
            respondNotFound(
                exchange,
                found.id.value,
                "artifact_unreadable",
                "${found.path} is recorded by the store but could not be read: ${e.message}",
            )
            return
        }
        exchange.responseHeaders.add("Content-Type", found.mediaType)
        exchange.sendResponseHeaders(HTTP_OK, size)
        exchange.responseBody.use { out: OutputStream -> Files.copy(found.path, out) }
    }

    public companion object {

        /** The only address this server binds. There is no option and no override. */
        public const val LOOPBACK: String = "127.0.0.1"

        /** The query key carrying the command name. `cmd`, never `name`. */
        public const val COMMAND_KEY: String = "cmd"

        /**
         * The reserved query key naming the calling session.
         *
         * Reserved in the same sense as [COMMAND_KEY]: stripped from the arguments the tool
         * receives, so a tool can neither read nor forge it. A caller that omits it is
         * identified by its remote address instead, which is enough to keep two concurrent
         * agents apart in the overlay.
         */
        public const val SESSION_KEY: String = "session"

        /** `HttpServer`'s connection backlog. Zero means the system default. */
        private const val BACKLOG: Int = 0

        private const val HTTP_OK: Int = 200
        private const val HTTP_BAD_REQUEST: Int = 400
        private const val HTTP_NOT_FOUND: Int = 404
        private const val HTTP_METHOD_NOT_ALLOWED: Int = 405
        private const val HTTP_SERVER_ERROR: Int = 500

        /**
         * Starts a host if, and only if, this build permits one and a port was asked for.
         *
         * @return the running host, or `null` when [AgentHostGate] refused - which is the normal
         *   case for a player's launch and is not logged, because a line of startup noise on
         *   every run is how people learn to stop reading startup logs. A refusal that had a
         *   *reason* other than "no port was requested" is logged once.
         */
        public fun startIfRequested(
            bridge: AgentBridge,
            config: (Int) -> AgentHostConfig,
            agentAllowed: Boolean = BuildFlags.AGENT_ALLOWED,
            properties: (String) -> String? = System::getProperty,
        ): AgentHost? {
            val decision = AgentHostGate.decide(agentAllowed, properties(BuildFlags.PORT_PROPERTY))
            return when (decision) {
                is AgentHostGate.Decision.Bind -> start(bridge, config(decision.port))
                is AgentHostGate.Decision.Refuse -> {
                    if (properties(BuildFlags.PORT_PROPERTY) != null) {
                        System.err.println("[udea-agent-host] not serving: ${decision.reason}")
                    }
                    null
                }
            }
        }

        /**
         * Binds, starts serving, and only then advertises.
         *
         * The order is the contract's and is load-bearing: an entry naming a port that was never
         * claimed is worse than no entry, because a reader that trusts it reports a running game
         * where there is none. Registry failure is swallowed inside [AgentRegistry] and cannot
         * reach here, so a read-only home cannot stop a game from starting or serving.
         *
         * @throws IOException when the port cannot be bound. That one **is** fatal to the caller's
         *   intent - a launcher asked for a specific port so that a bridge could find the game on
         *   it, and a host quietly bound somewhere else would be undiscoverable.
         */
        public fun start(bridge: AgentBridge, config: AgentHostConfig): AgentHost {
            val address = InetSocketAddress(InetAddress.getByName(LOOPBACK), config.port)
            val server = HttpServer.create(address, BACKLOG)
            val executor = Executors.newCachedThreadPool(AgentThreads.daemonFactory("udea-agent-http"))
            server.executor = executor
            val host = AgentHost(server, executor, bridge, config)
            host.install()
            startOnADaemonThread(server)
            config.registry.advertise(host.port, config.identity, config.renderMode, config.workingDirectory)
            return host
        }

        /**
         * `a=1&b=two` as a map, percent-decoded.
         *
         * Last value wins on a duplicate key, which is why the command is keyed `cmd`: with the
         * contract's alternative spelling, `?name=follow&name=hero` would leave the server unable
         * to tell which `name` was the command.
         */
        public fun parseQuery(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            rawQuery.split('&').forEach { pair ->
                if (pair.isEmpty()) return@forEach
                val split = pair.indexOf('=')
                val key = if (split < 0) pair else pair.substring(0, split)
                val value = if (split < 0) "" else pair.substring(split + 1)
                out[decode(key)] = decode(value)
            }
            return out
        }

        /**
         * Calls `server.start()` from a daemon thread, so that the thread it creates is one too.
         *
         * ## The half of the daemon rule an executor cannot reach
         *
         * A daemon executor covers the threads that run *handlers*. It does not cover
         * `sun.net.httpserver.ServerImpl`'s own accept loop, which `start()` creates itself:
         *
         * ```java
         * dispatcherThread = new Thread(null, dispatcher, "HTTP-Dispatcher", 0, false);
         * ```
         *
         * A `Thread` inherits its daemon flag from whichever thread constructed it, and `start()`
         * is normally called from the game's main thread, so `HTTP-Dispatcher` comes out
         * **non-daemon** - and one non-daemon thread is all it takes to keep a JVM, and the port,
         * alive behind a game that has already died. That is the exact failure `AgentThreads`
         * documents, and setting `server.executor` does not fix it: `setDaemon` cannot be called on
         * a thread that has already started, so there is no repair after the fact either.
         *
         * Starting from a daemon thread is the fix, and it is why this is four lines of thread
         * juggling rather than one call. `AgentHostThreadsTest` runs a child JVM that starts a host
         * and returns from `main`; without this, that JVM hangs forever, which is how the defect
         * was found rather than assumed.
         */
        private fun startOnADaemonThread(server: HttpServer) {
            var failure: Throwable? = null
            val starter = Thread({
                try {
                    server.start()
                } catch (e: Throwable) {
                    failure = e
                }
            }, "udea-agent-http-start")
            starter.isDaemon = true
            starter.start()
            // Joined, so `start` returns only once the port is genuinely accepting: a launcher that
            // was handed a port expects the very next `/health` to answer, and `launch_instance`
            // polls for exactly that.
            starter.join()
            failure?.let { throw it }
        }

        private fun decode(text: String): String =
            try {
                URLDecoder.decode(text, StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                // A malformed percent escape is the client's problem, not a reason to 500. The
                // raw text reaches the tool, which reports it as a bad argument naming the value.
                text
            }
    }
}

/**
 * Everything an [AgentHost] needs that is not the bridge.
 *
 * A value rather than a pile of constructor parameters because it is also what
 * [AgentHost.startIfRequested] hands back through its `(Int) -> AgentHostConfig` factory: the
 * port is not known until the gate has read it, and the identity, the manifest and the artifact
 * store all are.
 */
public class AgentHostConfig(
    /** The port to bind. `0` asks the OS for an ephemeral one - how every test here binds. */
    public val port: Int,
    /** How this instance names itself, to `/tools` and to its registry entry. */
    public val identity: GameIdentity = GameIdentity.UNKNOWN,
    /** Reported by `/health`, so an agent knows which toolsets are live before calling one. */
    public val renderMode: RenderMode = RenderMode.Headless,
    /** The manifest served by `/tools`. `null` serves a 404, which the bridge survives. */
    public val manifest: ToolManifest? = null,
    /** Backs `/artifact`. `null` serves a typed 404 for every id. */
    public val artifacts: AgentArtifacts? = null,
    /**
     * Whether the simulation is paused, read on every `/health`.
     *
     * A supplier rather than a flag because `/health` has to answer the *current* state and this
     * module holds no simulation. The host loop owns the answer; this reads it.
     */
    public val paused: () -> Boolean = { false },
    /** Writes and deletes the discovery entry. */
    public val registry: AgentRegistry = AgentRegistry(),
    /** Reported as `cwd` in the registry entry: which checkout this build came from. */
    public val workingDirectory: Path = Path.of("").toAbsolutePath(),
    /**
     * The session label table the overlay colours and names sessions from (spec 3.7).
     *
     * Shared with whatever draws the overlay, so the id recorded on a command and the label
     * printed beside it are the same table's. A host that draws no overlay still interns, which
     * costs one map entry per distinct caller and keeps the wiring identical in both cases.
     */
    public val sessions: AgentSessions = AgentSessions(),
) {
    init {
        require(port >= 0 && port <= AgentHostGate.MAX_PORT) {
            "port must be 0..${AgentHostGate.MAX_PORT}, was $port"
        }
    }

    override fun toString(): String = "AgentHostConfig(port=$port, $identity, $renderMode)"
}
