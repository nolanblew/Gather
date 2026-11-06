package ninja.takeya.gather

/** Invalidates every outstanding read before token rotation or account replacement starts. */
internal class SessionEpoch {
    var current: Long = 0L
        private set

    fun advance() {
        current++
    }

    fun isCurrent(captured: Long): Boolean = captured == current
}
