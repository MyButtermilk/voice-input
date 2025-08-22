package org.futo.voiceinput.soniox

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SonioxAsyncClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: SonioxAsyncClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = SonioxAsyncClient(
            apiKey = "test-key",
            httpClient = OkHttpClient(),
            baseUrl = server.url("/transcribe/async").toString()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun submitAndPoll() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"file_id\":\"123\"}"))
        server.enqueue(MockResponse().setBody("{\"status\":\"done\",\"text\":\"hello\"}"))

        val fileId = client.submitJob("audio".toByteArray())
        assertEquals("123", fileId)

        val result = client.waitForResult(fileId)
        assertEquals("hello", result)

        val request1 = server.takeRequest()
        assertEquals("/transcribe/async", request1.path)
        assertEquals("Bearer test-key", request1.getHeader("Authorization"))

        val request2 = server.takeRequest()
        assertEquals("/transcribe/async/123", request2.path)
    }
}
