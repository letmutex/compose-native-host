package letmutex.compose.nativehost.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

@Composable
internal fun EventsPage(
    onInteraction: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("Native shell input works.") }
    var switchEnabled by remember { mutableStateOf(true) }
    var buttonCount by remember { mutableIntStateOf(0) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "Events Playground",
            color = Color(0xFF1C1917),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Use this page to test buttons, focus, and hardware keyboard text entry. Page shortcuts now require Command.",
            color = Color(0xFF57534E),
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                onInteraction("Edited the events text field.")
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Event notes") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    buttonCount += 1
                    onInteraction("Pressed the primary events button.")
                },
            ) {
                Text("Primary action")
            }
            OutlinedButton(
                onClick = {
                    draft = ""
                    onInteraction("Cleared the events text field.")
                },
            ) {
                Text("Clear")
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = switchEnabled,
                    onCheckedChange = {
                        switchEnabled = it
                        onInteraction("Toggled the events switch.")
                    },
                )
                Text(
                    text = if (switchEnabled) "Live controls enabled" else "Live controls paused",
                    color = Color(0xFF44403C),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
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
                    text = "Button count: $buttonCount",
                    color = Color(0xFF292524),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Draft length: ${draft.length}",
                    color = Color(0xFF57534E),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Switch: ${if (switchEnabled) "On" else "Off"}",
                    color = Color(0xFF57534E),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
