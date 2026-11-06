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
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class TokenVault(context: Context) {
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

    fun read(): String? {
        val encoded = prefs.getString("session", null) ?: return null
        return try {
            val parts = encoded.split(':')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun write(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val value =
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                ":" +
                Base64.encodeToString(
                    cipher.doFinal(token.toByteArray(Charsets.UTF_8)),
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

class ApiFailure(val status: Int, message: String) : IOException(message)

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
    // GETs may safely recover a stale pooled socket; POSTs are never replayed.
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

    suspend fun request(path: String, token: String? = null, body: JsonElement? = null): String =
        suspendCancellableCoroutine { continuation ->
            val builder =
                Request.Builder()
                    .url(BuildConfig.SERVER_URL.trimEnd('/') + path)
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-store")
            if (token != null) builder.header("Authorization", "Bearer $token")
            if (body != null)
                builder.post(
                    body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                )
            val call = (if (body == null) readClient else client).newCall(builder.build())
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
                                    stream?.readNBytes(6 * 1024 * 1024 + 1) ?: "{}".toByteArray()
                                if (bytes.size > 6 * 1024 * 1024)
                                    throw IOException("The response is too large to open.")
                                val raw = String(bytes, Charsets.UTF_8)
                                if (!it.isSuccessful) {
                                    val message =
                                        runCatching {
                                                json
                                                    .parseToJsonElement(raw)
                                                    .jsonObject["error"]
                                                    ?.jsonPrimitive
                                                    ?.content
                                            }
                                            .getOrNull()
                                    throw ApiFailure(
                                        it.code,
                                        message
                                            ?: "Gather couldn’t complete that request (${it.code}).",
                                    )
                                }
                                if (continuation.isActive) continuation.resume(raw)
                            } catch (error: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                        }
                    }
                }
            )
        }
}
