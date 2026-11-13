package com.aieditor.text.data

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiClientTest {

    private fun client(baseUrl: String, stream: Boolean = true) = AiClient(
        ApiConfig(baseUrl = baseUrl, apiKey = "test-key", model = "test-model", stream = stream)
    )

    @Test
    fun endpoint_is_built_from_common_base_urls() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            client("https://api.deepseek.com/v1").endpoint(),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            client("https://api.deepseek.com/v1/").endpoint(),
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            client("https://api.openai.com").endpoint(),
        )
        assertEquals(
            "http://192.168.1.10:11434/v1/chat/completions",
            client("192.168.1.10:11434").endpoint(),
        )
        assertEquals(
            "https://example.com/custom/chat/completions",
            client("https://example.com/custom/chat/completions").endpoint(),
        )
    }

    @Test
    fun streaming_response_is_assembled_from_sse_chunks() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"，世界\"}}]}\n\n" +
                        "data: [DONE]\n\n"
                )
        )
        server.start()
        try {
            val base = server.url("/v1").toString()
            val chunks = client(base).chat(listOf(ChatMessage("user", "hi"))).toList()
            assertEquals("你好，世界", chunks.joinToString(""))

            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"stream\":true"))
            assertTrue(body.contains("\"model\":\"test-model\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun non_streaming_response_reads_message_content() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"润色后的文本\"}}]}")
        )
        server.start()
        try {
            val chunks = client(server.url("/v1").toString(), stream = false)
                .chat(listOf(ChatMessage("user", "hi")))
                .toList()
            assertEquals("润色后的文本", chunks.joinToString(""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun http_error_is_reported_with_server_message() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("{\"error\":{\"message\":\"Invalid API key\"}}")
        )
        server.start()
        try {
            val error = runCatching {
                client(server.url("/v1").toString()).chat(listOf(ChatMessage("user", "hi"))).toList()
            }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("401"))
            assertTrue(error?.message.orEmpty().contains("Invalid API key"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun api_base_drops_chat_completions_and_keeps_custom_path() {
        assertEquals(
            "https://api.deepseek.com/v1",
            client("https://api.deepseek.com/v1/chat/completions").apiBase(),
        )
        assertEquals(
            "https://example.com/custom",
            client("https://example.com/custom/chat/completions").apiBase(),
        )
        assertEquals(
            "https://api.openai.com/v1",
            client("https://api.openai.com/v1/").apiBase(),
        )
    }

    @Test
    fun list_models_parses_ids_and_sends_auth() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"object":"list","data":[{"id":"deepseek-reasoner"},{"id":"deepseek-chat"}]}"""
            )
        )
        server.start()
        try {
            val models = client(server.url("/v1").toString()).listModels()

            assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), models)
            val request = server.takeRequest()
            assertEquals("/v1/models", request.path)
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun list_models_reports_http_error() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"bad key\"}}"))
        server.start()
        try {
            val error = runCatching {
                client(server.url("/v1").toString()).listModels()
            }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("401"))
            assertTrue(error?.message.orEmpty().contains("bad key"))
        } finally {
            server.shutdown()
        }
    }
}
