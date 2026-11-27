package ninja.takeya.gather

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ITEM_IMAGE_EDGE = 1440
private const val ITEM_IMAGE_BYTES = 360 * 1024
private const val MAX_ENCODED_ITEM_IMAGE = 2 * 1024 * 1024 * 4 / 3 + 4

private fun decodeItemBitmap(source: ImageDecoder.Source): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
    val size = info.size
    val scale = minOf(1f, ITEM_IMAGE_EDGE.toFloat() / maxOf(size.width, size.height))
    decoder.setTargetSize(
        (size.width * scale).roundToInt().coerceAtLeast(1),
        (size.height * scale).roundToInt().coerceAtLeast(1),
    )
    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
}

internal suspend fun prepareItemImage(resolver: ContentResolver, uri: Uri): String = withContext(Dispatchers.IO) {
    val bitmap = decodeItemBitmap(ImageDecoder.createSource(resolver, uri))
    try {
        val buffer = ByteArrayOutputStream()
        for (quality in listOf(86, 72, 56, 40)) {
            buffer.reset()
            if (bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, buffer) && buffer.size() in 1..ITEM_IMAGE_BYTES) {
                return@withContext "data:image/webp;base64,${Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)}"
            }
        }
        throw IOException("This image is too large. Choose a smaller image.")
    } finally {
        bitmap.recycle()
    }
}

internal suspend fun decodeItemImage(dataUrl: String): Bitmap? = withContext(Dispatchers.Default) {
    val header = dataUrl.substringBefore(',')
    if (header !in setOf("data:image/webp;base64", "data:image/png;base64", "data:image/jpeg;base64")) return@withContext null
    val encoded = dataUrl.substringAfter(',', "")
    if (encoded.isEmpty() || encoded.length > MAX_ENCODED_ITEM_IMAGE) return@withContext null
    runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        decodeItemBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
    }.getOrNull()
}
