package letmutex.compose.nativehost.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp

@Composable
internal fun LinksPage() {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Links",
            color = Color(0xFF1C1917),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        HostedLinkText(
            label = "Jetpack Compose",
            url = "https://developer.android.com/jetpack/compose",
        )
        HostedLinkText(
            label = "Compose Multiplatform",
            url = "https://www.jetbrains.com/lp/compose-multiplatform/",
        )
        HostedLinkText(
            label = "Compose Native Host - Github",
            url = "https://github.com/letmutex/compose-native-host",
        )
    }
}

@Composable
private fun HostedLinkText(
    label: String,
    url: String,
) {
    val linkText =
        buildAnnotatedString {
            withLink(
                LinkAnnotation.Url(
                    url = url,
                    styles =
                        TextLinkStyles(
                            style =
                                SpanStyle(
                                    color = Color(0xFF1D4ED8),
                                ),
                        ),
                ),
            ) {
                append(label)
            }
        }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = linkText,
            color = Color(0xFF57534E),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = url,
            color = Color(0xFF78716C),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
