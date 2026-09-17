package dev.wildware.udea.net.transport

import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO

/** Ktor's coroutine engine, on the operating system's own sockets. */
internal actual val webSocketEngine: HttpClientEngineFactory<HttpClientEngineConfig> = CIO
