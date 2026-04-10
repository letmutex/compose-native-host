package letmutex.compose.nativehost.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import letmutex.compose.nativehost.LocalComposeNativeHostHandle

@Composable
internal fun WindowsPage(
    onInteraction: (String) -> Unit,
) {
    val hostHandle = LocalComposeNativeHostHandle.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "Multi-window host bridge",
            color = Color(0xFF1C1917),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "This page uses the native host handle from the composition local to ask Swift for another hosted window.",
            color = Color(0xFF57534E),
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(
            onClick = {
                hostHandle?.sendEvent(SampleOpenWindowEvent)
                onInteraction("Requested a new hosted window from Compose.")
            },
            enabled = hostHandle != null,
        ) {
            Text("Open another hosted window")
        }
        Surface(
            color = Color(0xFFE7DDD0),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (hostHandle != null) "Host bridge available" else "Host bridge unavailable",
                    color = Color(0xFF292524),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (hostHandle != null) {
                        "Hosted samples can open another native window with the same Compose content and an independent runtime."
                    } else {
                        "This standalone Compose Desktop sample does not provide a native host handle, so window requests stay disabled here."
                    },
                    color = Color(0xFF57534E),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
