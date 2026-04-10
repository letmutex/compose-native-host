package letmutex.compose.nativehost.sample

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val AnimationCardShape = RoundedCornerShape(18.dp)

@Composable
internal fun AnimationsPage(
    onInteraction: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    var accentMode by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val infiniteTransition = rememberInfiniteTransition(label = "animations-page")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val drift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 22f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "drift",
    )
    val accentColor by animateColorAsState(
        targetValue = if (accentMode) Color(0xFFEA580C) else Color(0xFF1D4ED8),
        animationSpec = tween(durationMillis = 320),
        label = "accent",
    )
    val badgeScale by animateFloatAsState(
        targetValue = if (expanded) 1f else 0.9f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "badge-scale",
    )

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "Animation Playground",
            color = Color(0xFF1C1917),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "This page tests animated size, visibility, color, and continuously moving content in the native host.",
            color = Color(0xFF57534E),
            style = MaterialTheme.typography.bodyLarge,
        )
        AnimationControls(
            expanded = expanded,
            accentMode = accentMode,
            onToggleExpanded = {
                expanded = !expanded
                onInteraction("Toggled the animation panel.")
            },
            onToggleAccent = {
                accentMode = !accentMode
                onInteraction("Changed the animation accent color.")
            },
        )
        AnimationPanel(
            expanded = expanded,
            pulse = pulse,
            accentColor = accentColor,
        )
        AnimatedBadgeShowcase(
            drift = drift.dp,
            badgeScale = badgeScale,
            accentColor = accentColor,
        )
    }
}

@Composable
private fun AnimationControls(
    expanded: Boolean,
    accentMode: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleAccent: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Button(onClick = onToggleExpanded) {
            Text(if (expanded) "Collapse panel" else "Expand panel")
        }
        OutlinedButton(onClick = onToggleAccent) {
            Text(if (accentMode) "Use blue accent" else "Use orange accent")
        }
    }
}

@Composable
private fun AnimationPanel(
    expanded: Boolean,
    pulse: Float,
    accentColor: Color,
) {
    val panelBrush =
        Brush.horizontalGradient(
            colors = listOf(
                accentColor.copy(alpha = 0.95f),
                accentColor.copy(alpha = pulse),
            ),
        )

    Surface(
        modifier =
            Modifier.fillMaxWidth()
                .animateContentSize(tween(durationMillis = 320, easing = FastOutSlowInEasing)),
        color = accentColor.copy(alpha = 0.14f),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Animated panel",
                color = Color(0xFF292524),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(280)) + fadeIn(animationSpec = tween(220)),
                exit = shrinkVertically(animationSpec = tween(220)) + fadeOut(animationSpec = tween(180)),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "The container grows and collapses without involving AWT window widgets.",
                        color = Color(0xFF57534E),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .height(62.dp)
                                .clip(AnimationCardShape)
                                .background(panelBrush),
                    ) {
                        Text(
                            text = "Pulse ${"%.2f".format(pulse)}",
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedBadgeShowcase(
    drift: Dp,
    badgeScale: Float,
    accentColor: Color,
) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFFEFE3D2)),
    ) {
        Surface(
            modifier =
                Modifier.offset(x = drift, y = 24.dp)
                    .width(180.dp)
                    .height(84.dp),
            color = accentColor,
            shape = RoundedCornerShape(22.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Animated badge",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            text = "Offset ${drift.value.toInt()}dp  •  Scale ${"%.2f".format(badgeScale)}",
            modifier = Modifier.align(Alignment.BottomStart).padding(18.dp),
            color = Color(0xFF57534E),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
