package com.github.mkulak.kortx

import io.vertx.core.MultiMap
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.CaseInsensitiveHeaders
import io.vertx.core.http.HttpVersion
import io.vertx.core.json.*
import io.vertx.ext.web.client.HttpResponse
import io.vertx.ext.web.codec.impl.BodyCodecImpl

fun mockHttpClient(handler: (HttpRequest) -> HttpResponse<Buffer>) = MockHttpClient(handler)

class MockHttpClient(val handler: (HttpRequest) -> HttpResponse<Buffer>) : HttpClient {
    override fun execute(req: HttpRequest): Future<HttpResponse<Buffer>> {
        val res = Future<HttpResponse<Buffer>>()
        res.complete(handler(req))
        return res
    }
}

fun Any.toJsonResponse(): HttpResponse<Buffer> =
    MockHttpResponse(200, Buffer.buffer(Json.mapper.writeValueAsString(this)))

class MockHttpResponse(
        val statusCode: Int,
        val body: Buffer,
        val statusMessage: String = "",
        val headers: Map<String, String> = emptyMap()
) : HttpResponse<Buffer> {

    override fun statusCode(): Int = statusCode

    override fun bodyAsString(): String = body.toString()

    override fun bodyAsString(encoding: String): String = body.toString(encoding)

    override fun bodyAsBuffer(): Buffer = body

    override fun cookies(): MutableList<String> = ArrayList<String>()

    override fun version(): HttpVersion = HttpVersion.HTTP_2

    override fun trailers(): MultiMap = CaseInsensitiveHeaders()

    override fun bodyAsJsonArray(): JsonArray = BodyCodecImpl.JSON_ARRAY_DECODER.apply(body)

    override fun getHeader(headerName: String): String? = headers[headerName]

    override fun body(): Buffer = body

    override fun headers(): MultiMap = headers.toMultiMap()

    override fun statusMessage(): String = statusMessage

    override fun getTrailer(trailerName: String): String = ""

    override fun <R> bodyAsJson(type: Class<R>): R = body.asJson<R>(type)

    override fun bodyAsJsonObject(): JsonObject = body.asJsonObj
}


