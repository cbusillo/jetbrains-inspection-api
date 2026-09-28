package com.shiny.inspectionmcp

import io.mockk.every
import io.mockk.mockk
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.QueryStringDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue

internal fun processInspectionRequest(
    handler: InspectionHandler,
    uri: String,
    method: HttpMethod = HttpMethod.GET,
    content: ByteBuf = Unpooled.EMPTY_BUFFER,
): MutableList<FullHttpResponse> {
    val mockRequest = mockk<FullHttpRequest>()
    val mockContext = mockk<ChannelHandlerContext>()
    val responses = mutableListOf<FullHttpResponse>()
    every { mockRequest.uri() } returns uri
    every { mockRequest.method() } returns method
    every { mockRequest.content() } returns content
    every { mockContext.writeAndFlush(any()) } answers {
        responses += firstArg<FullHttpResponse>()
        mockk(relaxed = true)
    }
    assertTrue(handler.process(QueryStringDecoder(uri), mockRequest, mockContext))
    return responses
}

internal fun jsonResponseValue(body: String): Any? = jsonValue(Json.parseToJsonElement(body))

private fun jsonValue(element: JsonElement): Any? = when (element) {
    is JsonObject -> element.mapValuesTo(linkedMapOf()) { (_, value) -> jsonValue(value) }
    is JsonArray -> element.map(::jsonValue)
    JsonNull -> null
    is JsonPrimitive -> when {
        element.isString -> element.content
        element.content == "true" || element.content == "false" -> element.content.toBoolean()
        else -> element.content.toIntOrNull() ?: element.content.toLongOrNull() ?: element.content.toDouble()
    }
}
