package io.ktor.client.features.observer

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.response.*
import kotlinx.coroutines.io.*

internal class ObservableResponse(
    override val content: ByteReadChannel,
    origin: HttpResponse,
    scope: HttpClient
) : HttpResponse by origin {

    override val call: HttpClientCall = HttpClientCall(scope).apply {
        request = DelegatedRequest(this, origin.call.request)
        response = this@ObservableResponse
    }

    override fun close() {}
}
