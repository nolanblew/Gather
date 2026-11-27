package ninja.takeya.gather

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val COVER_EDGE = 1440

internal class CoverPreparationFailure(message: String) : IOException(message)

private fun decodeCover(source: ImageDecoder.Source): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
    val size = info.size
    val ratio = minOf(1f, COVER_EDGE.toFloat() / maxOf(size.width, size.height))
    if (ratio < 1f) {
        decoder.setTargetSize(
            (size.width * ratio).roundToInt().coerceAtLeast(1),
            (size.height * ratio).roundToInt().coerceAtLeast(1),
        )
    }
    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
}

internal suspend fun prepareListCover(resolver: ContentResolver, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    val image = try {
        decodeCover(ImageDecoder.createSource(resolver, uri))
    } catch (_: Exception) {
        throw CoverPreparationFailure("Couldn’t open that image. Choose another photo.")
    }
    try {
        val output = ByteArrayOutputStream()
        for (quality in listOf(87, 76, 62)) {
            output.reset()
            if (image.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, output) && output.size() in 1..COVER_RESPONSE_LIMIT) {
                return@withContext output.toByteArray()
            }
        }
        throw CoverPreparationFailure("That image could not fit as a cover. Choose a smaller photo.")
    } finally {
        image.recycle()
    }
}

internal suspend fun decodeListCover(bytes: ByteArray, coverId: String): Bitmap? = withContext(Dispatchers.Default) {
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    if (hash != coverId) null else decodeCover(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
}

@Composable
internal fun ListCoverArtwork(list: GatherList, model: GatherViewModel, modifier: Modifier = Modifier) {
    key(list.id, list.coverId) {
        val image by produceState<Bitmap?>(initialValue = null, list.id, list.coverId) {
            val coverId = list.coverId
            if (coverId != null) value = model.loadListCover(list.id, coverId)
        }
        val cover = image
        if (cover != null) {
            Image(cover.asImageBitmap(), contentDescription = null, modifier = modifier.testTag("detail-cover"), contentScale = ContentScale.Crop)
        } else {
            Image(
                painterResource(R.drawable.list_hero), contentDescription = null, modifier = modifier,
                alignment = Alignment.CenterEnd, contentScale = ContentScale.Crop,
            )
        }
    }
}
