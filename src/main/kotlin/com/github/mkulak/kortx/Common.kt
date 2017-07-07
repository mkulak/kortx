package com.github.mkulak.kortx

import io.vertx.core.MultiMap
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import java.net.URL
import java.util.Base64
import java.util.concurrent.CompletableFuture

typealias Future<T> = CompletableFuture<T>

fun <A> Future<A>.withErrorMessage(message: String): Future<A> {
    val future = Future<A>()
    handle { result, throwable ->
        if (throwable != null) future.completeExceptionally(RuntimeException(message, throwable))
        else future.complete(result)
    }
    return future
}

fun <A> Future<A>.void(): Future<Unit> = thenApply { Unit }

fun URL.withPath(path: String): URL = URL("$this$path")

inline val Buffer.asJsonObj get() = JsonObject(toString())

inline fun <reified T> Buffer.asJson() = asJson(T::class.java)

inline fun <T> Buffer.asJson(clazz: Class<T>) = Json.mapper.readValue(toString(), clazz)

fun Map<String, String>.toMultiMap(): MultiMap = MultiMap.caseInsensitiveMultiMap().apply { addAll(this@toMultiMap) }

fun String.encodeBase64(): String =
        Base64.getEncoder().encode(this.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8)
