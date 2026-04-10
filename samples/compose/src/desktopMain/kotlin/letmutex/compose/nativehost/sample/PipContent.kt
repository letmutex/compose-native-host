package letmutex.compose.nativehost.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import letmutex.compose.nativehost.ComposeNativeHostScope

@Composable
fun ComposeNativeHostScope.PipHostedSampleApp() {
    val currentWindowInfo by windowInfo
    var level by remember { mutableFloatStateOf(0.38f) }
    var isPlaying by remember { mutableStateOf(true) }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFE9EEF5),
        ) {
            Box(
                modifier =
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFFF5F8FC), Color(0xFFD8E3F0)),
                        ),
                    ),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Picture in Picture (Runtime 2)",
                            color = Color(0xFF16324F),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "This view is hosted by a separate ComposeNativeHost entry point.",
                            color = Color(0xFF334155),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Surface(
                        color = Color.White.copy(alpha = 0.62f),
                        shape = RoundedCornerShape(22.dp),
                    ) {
                        PipControls(
                            level = level,
                            isPlaying = isPlaying,
                            isWindowFocused = currentWindowInfo.isFocused,
                            onLevelChange = { level = it },
                            onPlayPauseClick = { isPlaying = !isPlaying },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PipControls(
    level: Float,
    isPlaying: Boolean,
    isWindowFocused: Boolean,
    onLevelChange: (Float) -> Unit,
    onPlayPauseClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playbackLabel = if (isPlaying) "Now Playing" else "Paused"
    val focusLabel =
        if (isWindowFocused) {
            "Window focus is active."
        } else {
            "Window focus is in the background."
        }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = playbackLabel,
                    color = Color(0xFF0F172A),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${(level * 100).toInt()}%",
                    color = Color(0xFF475569),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = onPlayPauseClick) {
                Text(if (isPlaying) "Pause" else "Play")
            }
        }
        Slider(
            value = level,
            onValueChange = onLevelChange,
        )
        Text(
            text = focusLabel,
            color = Color(0xFF334155),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
