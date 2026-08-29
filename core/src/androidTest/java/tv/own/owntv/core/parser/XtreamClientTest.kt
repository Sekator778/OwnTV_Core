package tv.own.owntv.core.parser

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient

@RunWith(AndroidJUnit4::class)
class XtreamClientTest {

    private fun createClient(jsonResponse: String): XtreamClient {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(jsonResponse.toByteArray(Charsets.UTF_8).toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        return XtreamClient(HttpClient(okHttpClient))
    }

    private val testSource = SourceEntity(
        id = 1,
        name = "Test Source",
        type = SourceType.XTREAM,
        url = "http://example.com",
        username = "user",
        password = "pass",
    )

    @Test
    fun fetchAccountDetails_detectsHlsSupport() = runBlocking {
        val json = """
            {
              "user_info": {
                "username": "testuser",
                "status": "Active",
                "exp_date": "1796598000",
                "allowed_output_formats": ["m3u8", "ts", "rtmp"]
              }
            }
        """.trimIndent()

        val client = createClient(json)
        val details = requireNotNull(client.fetchAccountDetails(testSource))
        assertTrue(details.hlsSupported)
        assertEquals(1796598000000L, details.expiryMs)
    }

    @Test
    fun fetchAccountDetails_hlsNotSupportedWhenOmitted() = runBlocking {
        val json = """
            {
              "user_info": {
                "username": "testuser",
                "status": "Active",
                "exp_date": "1796598000"
              }
            }
        """.trimIndent()

        val client = createClient(json)
        val details = requireNotNull(client.fetchAccountDetails(testSource))
        assertFalse(details.hlsSupported)
        assertEquals(1796598000000L, details.expiryMs)
    }

    @Test
    fun fetchAccountDetails_hlsNotSupportedWhenTsOnly() = runBlocking {
        val json = """
            {
              "user_info": {
                "username": "testuser",
                "status": "Active",
                "allowed_output_formats": ["ts"]
              }
            }
        """.trimIndent()

        val client = createClient(json)
        val details = requireNotNull(client.fetchAccountDetails(testSource))
        assertFalse(details.hlsSupported)
    }
}
