package com.github.mkulak.kortx

import io.vertx.core.*
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.*
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.*
import io.vertx.ext.web.client.HttpResponse
import io.vertx.ext.web.client.WebClient
import java.net.URL
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture


fun HttpServer.start(router: Router, url: URL) = requestHandler(router::accept).listen(url.port, url.host)

fun HttpServerResponse.endWithJson(obj: Any) {
    putHeader("Content-Type", "application/json; charset=utf-8").end(Json.encode(obj))
}

fun Map<String, String>.toMultiMap(): MultiMap = MultiMap.caseInsensitiveMultiMap().apply { addAll(this@toMultiMap) }

fun String.encodeBase64(): String =
        Base64.getEncoder().encode(this.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8)

fun basicAuthHeader(name: String, password: String): Pair<String, String> =
        "Authorization" to ("Basic " + ("$name:$password".encodeBase64()))

fun oauth2Header(token: String): Pair<String, String> =
        "Authorization" to "Bearer $token"

fun URL.withPath(path: String): URL = URL("$this$path")

val Buffer.asJsonObj
    get() = JsonObject(toString())

inline fun <reified T> Buffer.asJson() = Json.mapper.readValue(toString(), T::class.java)

typealias Handler = (RoutingContext) -> Unit

fun Router.route(method: HttpMethod, path: String, beforeHandler: Handler?, handler: Handler): Route {
    val route = route(method, path)
    val routeWithBefore = if (beforeHandler != null) route.handler(beforeHandler) else route
    return routeWithBefore.handler(handler)
}

fun protect(vararg scopes: String) = { ctx: RoutingContext ->
    val token = ctx.request().getHeader("Authorization")
    if (token == null) ctx.response().setStatusCode(401).end("No auth token")
    if (checkScope(token, scopes.toList())) ctx.next() else ctx.response().setStatusCode(403).end("Not authorized")
}

fun checkScope(token: String, scopes: List<String>) = true //TODO: implement

fun Router.get(path: String, handler: Handler): Route = route(HttpMethod.GET, path, null, handler)
fun Router.post(path: String, handler: Handler): Route = route(HttpMethod.POST, path, null, handler)
fun Router.put(path: String, handler: Handler): Route = route(HttpMethod.PUT, path, null, handler)
fun Router.patch(path: String, handler: Handler): Route = route(HttpMethod.PATCH, path, null, handler)
fun Router.head(path: String, handler: Handler): Route = route(HttpMethod.HEAD, path, null, handler)
fun Router.delete(path: String, handler: Handler): Route = route(HttpMethod.DELETE, path, null, handler)
fun Router.option(path: String, handler: Handler): Route = route(HttpMethod.OPTIONS, path, null, handler)

fun Router.get(path: String, beforeHandler: Handler?, handler: Handler): Route = route(HttpMethod.GET, path, beforeHandler, handler)
fun Router.post(path: String, beforeHandler: Handler?, handler: Handler): Route = route(HttpMethod.POST, path, beforeHandler, handler)
fun Router.put(path: String, beforeHandler: Handler?, handler: Handler): Route = route(HttpMethod.PUT, path, beforeHandler, handler)
fun Router.patch(path: String, beforeHandler: Handler?, handler: Handler): Route = route(HttpMethod.PATCH, path, beforeHandler, handler)
fun Router.head(path: String, beforeHandler: Handler?, handler: Handler): Route = route(HttpMethod.HEAD, path, beforeHandler, handler)
fun Router.delete(path: String, beforeHandler: Handler?, handler: Handler): Route = route(HttpMethod.DELETE, path, beforeHandler, handler)
fun Router.option(path: String, beforeHandler: Handler?, handler: Handler): Route = route(HttpMethod.OPTIONS, path, beforeHandler, handler)



open class HttpApi(val routes: Router.() -> Unit) {
    fun api(vertx: Vertx): Router = Router.router(vertx).apply { routes() }
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

typealias Future<T> = CompletableFuture<T>

sealed class HttpEntity

data class FormEntity(val params: Map<String, String>) : HttpEntity()

data class JsonEntity(val payload: Any) : HttpEntity()

object EmptyEntity : HttpEntity() {
    override fun toString() = "EmptyEntity"
}


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

fun HttpClient.expect(req: HttpRequest): Future<HttpResponse<Buffer>> =
        execute(req).thenApply { result ->
            if (result.statusCode() in 200..299) result
            else throw RuntimeException("Unexpected return code: ${result.statusCode()}")
        }

inline fun <reified T> HttpClient.json(req: HttpRequest): Future<T> =
        expect(req).thenApply { it.bodyAsJson(T::class.java) }

fun <A> Future<A>.withErrorMessage(message: String): Future<A> {
    val future = Future<A>()
    handle { result, throwable ->
        if (throwable != null) future.completeExceptionally(RuntimeException(message, throwable))
        else future.complete(result)
    }
    return future
}
