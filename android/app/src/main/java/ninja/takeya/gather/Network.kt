package ninja.takeya.gather

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
internal data class StoredNativeSession(
    val token: String,
    val userId: String? = null,
    val username: String? = null,
    val expiresAt: String? = null,
) {
    fun expired(): Boolean = expiresAt?.let { value ->
        runCatching { !java.time.Instant.parse(value).isAfter(java.time.Instant.now()) }.getOrDefault(false)
    } == true
}

internal class TokenVault(context: Context) {
    private val prefs = context.getSharedPreferences("gather_credentials", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("gather.session.v1", null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "gather.session.v1",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    fun read(): StoredNativeSession? {
        val encoded = prefs.getString("session", null) ?: return null
        return try {
            val parts = encoded.split(':')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            val decoded = String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
            // Existing installs store a token alone. A verified session adds the account identity.
            if (decoded.startsWith("{")) Json.decodeFromString<StoredNativeSession>(decoded)
            else StoredNativeSession(decoded)
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun write(session: StoredNativeSession) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val value =
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                ":" +
                Base64.encodeToString(
                    cipher.doFinal(Json.encodeToString(session).toByteArray(Charsets.UTF_8)),
                    Base64.NO_WRAP,
                )
        check(prefs.edit().putString("session", value).commit()) {
            "Couldn’t securely save your session. Please try again."
        }
    }

    fun clear() {
        prefs.edit().clear().commit()
    }
}

class ApiFailure(val status: Int, message: String, val code: String? = null) : IOException(message)

private const val WORKSPACE_RESPONSE_LIMIT = 6 * 1024 * 1024
internal const val COVER_RESPONSE_LIMIT = 2 * 1024 * 1024

class GatherApi {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val client =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(35, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    // GETs recover stale pooled sockets. The durable outbox controls retries for actions.
    private val readClient = client.newBuilder().retryOnConnectionFailure(true).build()

    init {
        require(
            BuildConfig.SERVER_URL.startsWith("https://") ||
                (BuildConfig.DEBUG &&
                    java.net.URI(BuildConfig.SERVER_URL).host in
                        listOf("127.0.0.1", "localhost", "10.0.2.2"))
        )
    }

    suspend inline fun <reified T> decode(raw: String): T =
        withContext(Dispatchers.Default) { json.decodeFromString<T>(raw) }

    suspend fun request(path: String, token: String? = null, body: JsonElement? = null): String {
        val builder = requestBuilder(BuildConfig.SERVER_URL.trimEnd('/') + path, token)
        if (body != null) {
            builder.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        }
        return exchange(builder.build(), WORKSPACE_RESPONSE_LIMIT).toString(Charsets.UTF_8)
    }

    suspend fun listCover(listId: String, coverId: String, token: String): ByteArray {
        val url = coverUrl(listId).addQueryParameter("v", coverId).build()
        val request = requestBuilder(url.toString(), token).header("Accept", "image/webp").build()
        return exchange(request, COVER_RESPONSE_LIMIT)
    }

    suspend fun uploadListCover(
        listId: String,
        bytes: ByteArray,
        token: String,
        operationId: String? = null,
        expectedCoverId: String? = null,
    ): String {
        val request = requestBuilder(coverUrl(listId).build().toString(), token)
            .apply {
                if (operationId != null) header("Idempotency-Key", operationId)
                if (expectedCoverId != null) header("If-Match", expectedCoverId)
            }
            .put(bytes.toRequestBody("image/webp".toMediaType())).build()
        return exchange(request, WORKSPACE_RESPONSE_LIMIT).toString(Charsets.UTF_8)
    }

    private fun requestBuilder(url: String, token: String?): Request.Builder =
        Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .apply { if (token != null) header("Authorization", "Bearer $token") }

    private fun coverUrl(listId: String): HttpUrl.Builder =
        BuildConfig.SERVER_URL.toHttpUrl().newBuilder().addPathSegments("api/lists")
            .addPathSegment(listId).addPathSegment("cover")

    private suspend fun exchange(request: Request, limit: Int): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val call = (if (request.method == "GET") readClient else client).newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            try {
                                val stream = it.body?.byteStream()
                                val bytes =
                                    stream?.readNBytes(limit + 1) ?: "{}".toByteArray(Charsets.UTF_8)
                                if (bytes.size > limit)
                                    throw IOException("The response is too large to open.")
                                if (!it.isSuccessful) {
                                    val details =
                                        runCatching {
                                                json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                                            }
                                            .getOrNull()
                                    throw ApiFailure(
                                        it.code,
                                        (details?.get("error") as? JsonPrimitive)?.contentOrNull
                                            ?: "Gather couldn’t complete that request (${it.code}).",
                                        (details?.get("code") as? JsonPrimitive)?.contentOrNull,
                                    )
                                }
                                if (continuation.isActive) continuation.resume(bytes)
                            } catch (error: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                        }
                    }
                }
            )
        }
}
