package dev.wildware.udea.net.transport

import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.js.Js

/** The JavaScript host's own `WebSocket`: the browser's, or the `ws` package's on Node. */
internal actual val webSocketEngine: HttpClientEngineFactory<HttpClientEngineConfig> = Js
