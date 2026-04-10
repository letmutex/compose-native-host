package letmutex.compose.nativehost.sample

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import letmutex.compose.nativehost.WindowInfo

@Composable
internal fun OverviewPage(
    windowInfo: WindowInfo,
    resizeHint: String,
) {
    val scrollState = rememberScrollState()
    val transition = rememberInfiniteTransition(label = "overview-progress")
    val progress =
        transition.animateFloat(
            initialValue = 0.08f,
            targetValue = 0.92f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "progress",
        ).value
    val glow =
        transition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "glow",
        ).value

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Host Metrics",
            color = Color(0xFF1C1917),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        OverviewMetricRow(label = "Drawable", value = "${windowInfo.width} x ${windowInfo.height}")
        OverviewMetricRow(label = "Scale", value = "%.2f".format(windowInfo.scale))
        OverviewMetricRow(label = "Refresh", value = "${windowInfo.refreshRate} Hz")
        Text(
            text = resizeHint,
            color = Color(0xFF57534E),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Frame pacing demo",
            color = Color(0xFF7C2D12),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier =
                Modifier.fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(999.dp)),
            color = Color(0xFF1D4ED8).copy(alpha = glow),
            trackColor = Color(0xFFD6D3D1),
        )
    }
}

@Composable
private fun OverviewMetricRow(
    label: String,
    value: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            color = Color(0xFF7C2D12),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            color = Color(0xFF292524),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}
