package letmutex.compose.nativehost

data class TextInputGeometry(
    val focusedRectLeft: Float,
    val focusedRectTop: Float,
    val focusedRectRight: Float,
    val focusedRectBottom: Float,
    val selectionStart: Int,
    val selectionEnd: Int,
    val compositionStart: Int,
    val compositionEnd: Int,
)
