package ninja.takeya.gather

import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Uses only real public APIs against the explicitly selected disposable local server. */
internal class LocalAcceptanceFixture {
    private val args = InstrumentationRegistry.getArguments()
    val baseUrl = args.getString("gatherTestBaseUrl") ?: BuildConfig.SERVER_URL
    val username = requireNotNull(args.getString("gatherTestUsername")) { "Supply gatherTestUsername=android-e2e-..." }
    val password = requireNotNull(args.getString("gatherTestPassword")) { "Supply gatherTestPassword for the local synthetic account" }
    val runId = java.util.UUID.randomUUID().toString().take(8)
    var token: String = ""
        private set
    var userId: String = ""
        private set

    init {
        val configured = URI(BuildConfig.SERVER_URL)
        val fixture = URI(baseUrl)
        require(BuildConfig.DEBUG) { "Acceptance tests require a debug build" }
        for (uri in listOf(configured, fixture)) {
            require(uri.scheme == "http" && uri.host in listOf("127.0.0.1", "localhost") && uri.port == 48733 && uri.userInfo == null) {
                "Acceptance tests only use the disposable localhost:48733 server; configure ADB reverse first"
            }
        }
        require(configured.host == fixture.host && configured.port == fixture.port) { "App and fixture must use the same local server" }
        require(username.startsWith("android-e2e-") && username.length <= 60) { "Use a synthetic android-e2e- account" }
        require(password.length >= 6) { "Supply the local fixture password" }
        val capabilities = request("/api/capabilities")
        assertEquals(1, capabilities.getInt("apiVersion"))
        assertTrue(capabilities.getJSONArray("authentication").toString().contains("bearer"))
        val credentials = json("username" to username, "password" to password)
        // Repeated lifecycle methods reuse their synthetic account without
        // spending another registration attempt on an expected conflict.
        val existing = raw("/api/native/auth/login", credentials)
        val session = if (existing.status == 401) request("/api/native/auth/register", credentials)
            else existing.also { assertEquals(it.value.toString(), 200, it.status) }.value
        token = session.getString("accessToken")
        userId = session.getString("userId")
    }

    data class Reply(val status: Int, val value: JSONObject, val cookie: String?)

    fun raw(path: String, body: JSONObject? = null, bearer: String? = null, cookie: String? = null): Reply {
        val accountEntry = path in listOf("/api/native/auth/register", "/api/native/auth/login", "/api/auth/login")
        var retries = 0
        while (true) {
            val reply = rawOnce(path, body, bearer, cookie)
            // A known pre-hash rejection cannot have created an account or
            // session. Only fixture account setup retries this exact response;
            // never replay writes, network failures, 429s or arbitrary 503s.
            if (!accountEntry || reply.status != 503 ||
                reply.value.optString("error") != "Authentication is temporarily busy. Try again shortly." || retries >= 3) return reply
            android.os.SystemClock.sleep(300L shl retries)
            retries++
        }
    }

    private fun rawOnce(path: String, body: JSONObject?, bearer: String?, cookie: String?): Reply {
        require(path.startsWith("/api/"))
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        cookie?.let { connection.setRequestProperty("Cookie", it) }
        try {
            if (body != null) {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            return Reply(status, JSONObject(text), connection.getHeaderField("Set-Cookie")?.substringBefore(';'))
        } finally {
            connection.disconnect()
        }
    }

    fun request(path: String, body: JSONObject? = null, bearer: String? = null, cookie: String? = null): JSONObject {
        val reply = raw(path, body, bearer, cookie)
        assertEquals("$path: ${reply.value}", 200, reply.status)
        return reply.value
    }

    fun workspace() = request("/api/workspace", bearer = token)
    fun action(type: String, payload: JSONObject, bearer: String = token, cookie: String? = null) =
        request("/api/actions", json("type" to type, "payload" to payload), if (cookie == null) bearer else null, cookie)

    fun list(title: String): JSONObject? {
        val lists = workspace().getJSONArray("lists")
        return (0 until lists.length()).map { lists.getJSONObject(it) }.firstOrNull { it.getString("title") == title }
    }

    fun item(listTitle: String, text: String): JSONObject? {
        val items = list(listTitle)?.getJSONArray("items") ?: return null
        return (0 until items.length()).map { items.getJSONObject(it) }.firstOrNull { it.getString("text") == text }
    }

    fun browserLogin(): String {
        val result = raw("/api/auth/login", json("username" to username, "password" to password))
        assertEquals(200, result.status)
        assertTrue("Browser JSON must never contain a token", !result.value.has("accessToken"))
        return requireNotNull(result.cookie)
    }

    fun secondAccount(): String {
        val session = request("/api/native/auth/register", json("username" to "$username-$runId", "password" to password))
        return session.getString("accessToken")
    }

    companion object {
        fun json(vararg fields: Pair<String, Any?>): JSONObject = JSONObject().apply {
            fields.forEach { (name, value) -> put(name, value ?: JSONObject.NULL) }
        }
    }
}
