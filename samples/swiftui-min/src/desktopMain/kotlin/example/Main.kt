package example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import letmutex.compose.nativehost.ComposeNativeHost

fun main() = ComposeNativeHost { App() }

@Composable
private fun App(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF2F2F7))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "Hello from Compose",
            modifier = Modifier,
        )
    }
}
