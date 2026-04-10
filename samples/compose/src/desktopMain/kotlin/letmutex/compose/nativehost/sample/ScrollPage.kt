package letmutex.compose.nativehost.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val ScrollCardShape = RoundedCornerShape(18.dp)

@Composable
internal fun ScrollPage(
    onInteraction: (String) -> Unit,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Scrollable Content",
            color = Color(0xFF1C1917),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "This page keeps a long scrollable list inside the host-controlled Compose surface.",
            color = Color(0xFF57534E),
            style = MaterialTheme.typography.bodyLarge,
        )
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(18) { index ->
                ScrollItemCard(
                    title = "List item ${index + 1}",
                    detail = "Scroll item ${index + 1} verifies clipping, scrolling, and pointer tracking.",
                    onClick = { onInteraction("Selected scroll item ${index + 1}.") },
                )
            }
        }
    }
}

@Composable
private fun ScrollItemCard(
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFF9F5EE),
        shape = ScrollCardShape,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                color = Color(0xFF292524),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = detail,
                color = Color(0xFF57534E),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
