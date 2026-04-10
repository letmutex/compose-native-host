package letmutex.compose.nativehost.sample

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import letmutex.compose.nativehost.ComposeNativeHostScope
import letmutex.compose.nativehost.WindowInfo

const val SamplePageSelectedEvent = "sample.page.selected"
const val SampleOpenWindowEvent = "sample.window.open"

data class SampleCopy(
    val eyebrow: String,
    val title: String,
    val summaryText: String,
    val resizeHint: String,
)

private enum class SamplePage(val title: String) {
    Overview("Overview"),
    Events("Events"),
    Windows("Windows"),
    Animations("Animations"),
    Scroll("Scroll"),
    DragDrop("DragDrop"),
}

private data class SampleUiState(
    val selectedPage: SamplePage,
    val activationCount: Int,
    val lastInput: String,
)

private const val DefaultInstruction = "Click a page tab or use arrow keys, space, and enter."
private val PageChipShape = RoundedCornerShape(999.dp)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ComposeNativeHostScope.HostedSampleApp(copy: SampleCopy) {
    val currentWindowInfo by windowInfo
    SampleApp(
        windowInfo = currentWindowInfo,
        copy = copy,
        onPageSelected = { host.sendEvent(SamplePageSelectedEvent, it) },
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SampleApp(
    windowInfo: WindowInfo,
    copy: SampleCopy,
    onPageSelected: (String) -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }
    var uiState by remember {
        mutableStateOf(
            SampleUiState(
                selectedPage = SamplePage.Overview,
                activationCount = 0,
                lastInput = DefaultInstruction,
            ),
        )
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(uiState.selectedPage) {
        onPageSelected(uiState.selectedPage.title)
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFF4EFE6),
        ) {
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .focusRequester(focusRequester)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            handlePreviewKeyEvent(event, uiState) { uiState = it }
                        }
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFFF7F2EA), Color(0xFFF0E7DA)),
                            ),
                        ),
            ) {
                Column(
                    modifier =
                        Modifier.fillMaxSize()
                            .padding(horizontal = 56.dp, vertical = 40.dp),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.Top,
                ) {
                    SampleHeader(copy = copy)
                    SamplePageSelector(
                        selectedPage = uiState.selectedPage,
                        onPageClick = { page ->
                            uiState = clickedPageState(uiState, page)
                        },
                    )
                    SamplePageSurface(
                        windowInfo = windowInfo,
                        uiState = uiState,
                        resizeHint = copy.resizeHint,
                        onInteraction = { message ->
                            uiState = uiState.copy(lastInput = message)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SampleHeader(copy: SampleCopy) {
    Text(
        text = copy.eyebrow,
        color = Color(0xFF7C2D12),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = copy.title,
        color = Color(0xFF1C1917),
        style = MaterialTheme.typography.displayLarge,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = copy.summaryText,
        modifier = Modifier.padding(top = 16.dp),
        color = Color(0xFF57534E),
        style = MaterialTheme.typography.titleLarge,
    )
}

private fun handlePreviewKeyEvent(
    event: KeyEvent,
    uiState: SampleUiState,
    onStateChanged: (SampleUiState) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) {
        return false
    }
    if (!event.isMetaPressed) {
        return false
    }
    val pages = SamplePage.entries
    val currentIndex = pages.indexOf(uiState.selectedPage)
    return when (event.key) {
        Key.DirectionLeft -> {
            val nextPage = pages[(currentIndex + pages.lastIndex) % pages.size]
            onStateChanged(
                uiState.copy(
                    selectedPage = nextPage,
                    lastInput = "Keyboard moved selection to ${nextPage.title}.",
                ),
            )
            true
        }

        Key.DirectionRight -> {
            val nextPage = pages[(currentIndex + 1) % pages.size]
            onStateChanged(
                uiState.copy(
                    selectedPage = nextPage,
                    lastInput = "Keyboard moved selection to ${nextPage.title}.",
                ),
            )
            true
        }

        Key.Spacebar,
        Key.Enter,
        -> {
            onStateChanged(
                uiState.copy(
                    activationCount = uiState.activationCount + 1,
                    lastInput = "Activated ${uiState.selectedPage.title} from the keyboard.",
                ),
            )
            true
        }

        else -> false
    }
}

private fun clickedPageState(
    uiState: SampleUiState,
    page: SamplePage,
): SampleUiState =
    uiState.copy(
        selectedPage = page,
        activationCount = uiState.activationCount + 1,
        lastInput = "Clicked ${page.title}.",
    )

@Composable
private fun SamplePageSelector(
    selectedPage: SamplePage,
    onPageClick: (SamplePage) -> Unit,
) {
    Row(
        modifier = Modifier.padding(top = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SamplePage.entries.forEach { page ->
            SamplePageButton(
                title = page.title,
                selected = selectedPage == page,
                onClick = { onPageClick(page) },
            )
        }
    }
}

@Composable
private fun SamplePageButton(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (selected) Color(0xFF1D4ED8) else Color(0xFFE7DDD0),
        shape = PageChipShape,
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            color = if (selected) Color.White else Color(0xFF44403C),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SamplePageSurface(
    windowInfo: WindowInfo,
    uiState: SampleUiState,
    resizeHint: String,
    onInteraction: (String) -> Unit,
) {
    Surface(
        modifier =
            Modifier.padding(top = 24.dp)
                .fillMaxWidth()
                .height(460.dp),
        color = Color(0xFFF9F5EE),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 2.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
        ) {
            AnimatedContent(
                targetState = uiState.selectedPage,
                transitionSpec = {
                    val movingForward = targetState.ordinal > initialState.ordinal
                    val enterOffset = if (movingForward) { fullWidth: Int -> fullWidth / 6 } else { fullWidth: Int -> -fullWidth / 6 }
                    val exitOffset = if (movingForward) { fullWidth: Int -> -fullWidth / 8 } else { fullWidth: Int -> fullWidth / 8 }
                    (slideInHorizontally(animationSpec = tween(260), initialOffsetX = enterOffset) + fadeIn(animationSpec = tween(220)))
                        .togetherWith(
                            slideOutHorizontally(animationSpec = tween(220), targetOffsetX = exitOffset) + fadeOut(animationSpec = tween(180)),
                        ).using(SizeTransform(clip = false))
                },
                label = "sample-page",
            ) { page ->
                when (page) {
                    SamplePage.Overview -> OverviewPage(
                        windowInfo = windowInfo,
                        resizeHint = resizeHint,
                    )

                    SamplePage.Events -> EventsPage(onInteraction = onInteraction)
                    SamplePage.Windows -> WindowsPage(onInteraction = onInteraction)
                    SamplePage.Animations -> AnimationsPage(onInteraction = onInteraction)
                    SamplePage.Scroll -> ScrollPage(onInteraction = onInteraction)
                    SamplePage.DragDrop -> DragDropPage(onInteraction = onInteraction)
                }
            }
        }
    }
}
