package com.github.mkulak.kortx

import io.vertx.core.AsyncResult
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.client.HttpResponse
import io.vertx.ext.web.client.WebClient
import java.net.URL
import java.time.Duration

interface HttpClient {
    fun execute(req: HttpRequest): Future<HttpResponse<Buffer>>
}

class HttpClientImpl(vertx: Vertx) : HttpClient {
    val client = WebClient.create(vertx)

    override fun execute(req: HttpRequest): Future<HttpResponse<Buffer>> {
        val future = Future<HttpResponse<Buffer>>()
        fun handler(res: AsyncResult<HttpResponse<Buffer>>) {
            if (res.succeeded()) future.complete(res.result()) else future.completeExceptionally(res.cause())
        }

        val port = if (req.url.port == -1) req.url.defaultPort else req.url.port
        val vertxReq = client.request(req.method, port, req.url.host, req.url.path)
        vertxReq.ssl(req.url.protocol == "https")
        vertxReq.timeout(req.timeout.toMillis())
        req.params.forEach { (name, value) -> vertxReq.addQueryParam(name, value) }
        req.headers.forEach { (name, value) -> vertxReq.putHeader(name, value) }
        when (req.body) {
            is EmptyEntity -> vertxReq.send(::handler)
            is JsonEntity -> vertxReq.sendJson(req.body.payload, ::handler)
            is FormEntity -> vertxReq.sendForm(req.body.params.toMultiMap(), ::handler)
        }
        return future
    }
}

data class HttpRequest(
        val url: URL,
        val method: HttpMethod = HttpMethod.GET,
        val params: Map<String, String> = emptyMap(),
        val body: HttpEntity = EmptyEntity,
        val headers: Map<String, String> = emptyMap(),
        val timeout: Duration = Duration.ofSeconds(10)) {

    fun withMethod(method: HttpMethod) = copy(method = method)

    fun withBody(body: HttpEntity) = copy(body = body)

    fun addParams(params: Map<String, String>) = copy(params = this.params + params)

    fun addParam(param: Pair<String, String>) = copy(params = params + param)

    fun addHeaders(headers: Map<String, String>) = copy(headers = this.headers + headers)

    fun addHeader(header: Pair<String, String>) = copy(headers = headers + header)

    fun withBasicAuth(name: String, password: String) = addHeader(basicAuthHeader(name, password))

    fun withTimeout(timeout: Duration) = copy(timeout = timeout)

    fun withOAuth2(token: String) = addHeader(oauth2Header(token))
}

sealed class HttpEntity

data class FormEntity(val params: Map<String, String>) : HttpEntity()

data class JsonEntity(val payload: Any) : HttpEntity()

object EmptyEntity : HttpEntity() {
    override fun toString() = "EmptyEntity"
}

fun basicAuthHeader(name: String, password: String): Pair<String, String> =
        "Authorization" to ("Basic " + ("$name:$password".encodeBase64()))

fun oauth2Header(token: String): Pair<String, String> =
        "Authorization" to "Bearer $token"

fun HttpClient.expect(req: HttpRequest): Future<HttpResponse<Buffer>> =
        execute(req).thenApply { result ->
            if (result.statusCode() in 200..299) result
            else throw RuntimeException("Unexpected return code: ${result.statusCode()}")
        }

inline fun <reified T> HttpClient.json(req: HttpRequest): Future<T> =
        expect(req).thenApply { it.bodyAsJson(T::class.java) }
