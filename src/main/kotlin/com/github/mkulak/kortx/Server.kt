package com.github.mkulak.kortx

import io.vertx.core.Vertx
import io.vertx.core.http.*
import io.vertx.core.json.Json
import io.vertx.ext.web.*

open class HttpApi(val routes: Router.() -> Unit) {
    fun api(vertx: Vertx): Router = Router.router(vertx).apply { routes() }
}

fun HttpServer.start(router: Router, host: String, port: Int) = requestHandler(router::accept).listen(port, host)

fun HttpServerResponse.endWithJson(obj: Any) {
    putHeader("Content-Type", "application/json; charset=utf-8").end(Json.encode(obj))
}

typealias Handler = (RoutingContext) -> Unit

fun Router.route(method: HttpMethod, path: String, beforeHandler: Handler?, handler: Handler): Route {
    val route = route(method, path)
    val routeWithBefore = if (beforeHandler != null) route.handler(beforeHandler) else route
    return routeWithBefore.handler(handler)
}

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


class Authenticator(val provider: TokenInfoProvider) {
    fun protect(vararg scopes: String): Handler = { ctx: RoutingContext ->
        val token = ctx.request().getHeader("Authorization")
        if (token == null) ctx.response().setStatusCode(401).end("No authentication")
        if (provider.hasScopes(token, scopes.toList())) ctx.next()
        else ctx.response().setStatusCode(403).end("Not authorized")
    }
}

interface TokenInfoProvider {
    fun hasScopes(token: String, scopes: List<String>): Boolean
}
