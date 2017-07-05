package com.github.mkulak.kortx

import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import java.lang.Thread.sleep
import java.net.URL
import java.time.Duration
import kotlin.concurrent.thread

class AnalyzerServer(oauth: OAuthProtector) : HttpApi({
    post("/analyze", oauth.protect("uid")) { ctx ->
        ctx.request().bodyHandler { buffer ->
            val tweetJson = JsonObject(buffer.toString())
            ctx.response().endWithJson((tweetJson.getString("text").length % 2 == 0).toString())
        }
    }
})

class AnalyzerClientImpl(val serverUrl: URL, val http: HttpClient) {

    fun analyze(tweet: Tweet): Future<Boolean> {
        val req = HttpRequest(serverUrl.withPath("/analyze"), HttpMethod.POST)
                .withBody(JsonEntity(tweet))
                .withTimeout(Duration.ofSeconds(5))
        return http.json<Boolean>(req).withErrorMessage("Failed to analyze tweet $tweet")
    }
}

data class Tweet(val text: String, val user: String)

val LOG = LoggerFactory.getLogger("App")

fun main(args: Array<String>) {
    Json.mapper.registerKotlinModule()

    val vertx = Vertx.vertx()

    val httpClient = HttpClientImpl(vertx)
    thread {
        sleep(5000)
        val analyzerClient = AnalyzerClientImpl(URL("http://localhost:8080"), httpClient)
        analyzerClient.analyze(Tweet("text", "user")).thenApply {
            println("analyze result: $it")
        }
    }

    val oauthProtector: OAuthProtector = TODO()
    val router = AnalyzerServer(oauthProtector).api(vertx)

    LOG.info("starting http server on localhost:8080")
    vertx.createHttpServer().start(router, "localhost", 8080)
}