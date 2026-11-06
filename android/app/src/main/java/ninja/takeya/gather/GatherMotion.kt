package ninja.takeya.gather

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

/** Short, concurrent Compose animations inherit Android's animator duration scale, including 0. */
internal object GatherMotion {
    const val Quick = 160
    const val Standard = 220

    fun <T> quick(): FiniteAnimationSpec<T> = tween(Quick, easing = FastOutSlowInEasing)

    fun <T> standard(): FiniteAnimationSpec<T> = tween(Standard, easing = FastOutSlowInEasing)
}

internal fun <T> AnimatedContentTransitionScope<T>.gatherFade(
    resize: Boolean = false
): ContentTransform =
    (fadeIn(GatherMotion.quick()) togetherWith fadeOut(GatherMotion.quick())).using(
        if (resize) SizeTransform { _, _ -> GatherMotion.standard() } else null
    )

internal fun <T> AnimatedContentTransitionScope<T>.gatherPage(direction: Int): ContentTransform =
    ((fadeIn(GatherMotion.standard()) +
            slideInHorizontally(GatherMotion.standard()) { direction * it / 12 }) togetherWith
            (fadeOut(GatherMotion.quick()) +
                slideOutHorizontally(GatherMotion.standard()) { -direction * it / 12 }))
        .using(null)

/** Retain each outgoing page's data while it leaves, rather than reading the next page's state. */
internal data class GatherPage(val key: String, val state: GatherState) {
    val isDetail: Boolean
        get() = key.startsWith("detail:")

    val order: Int
        get() =
            when (key) {
                "library" -> 0
                "favorites" -> 1
                "templates" -> 2
                "settings" -> 3
                "archive" -> 4
                "all" -> 5
                else -> 6
            }
}

/** An exiting page is visual only: it cannot receive taps or accessibility actions. */
internal fun Modifier.gatherPageInput(active: Boolean): Modifier =
    if (active) this
    else
        clearAndSetSemantics {}
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            }

/** Enter only: a signed-out session disposes its private composition immediately. */
@Composable
internal fun GatherReveal(content: @Composable () -> Unit) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = visible,
        enter =
            fadeIn(GatherMotion.standard()) +
                slideInVertically(GatherMotion.standard()) { it / 40 },
        exit = ExitTransition.None,
        label = "Session reveal",
    ) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}
