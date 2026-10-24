package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.utils.MobileTinaAboutContent

/** The owner's Exclave About content adapted to the current Compose/insets architecture. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileTinaAboutScreen(onBack: () -> Unit, onOpenDrawer: () -> Unit, onOpenLink: (String) -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.mobiletina_about)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.baseline_arrow_back_24), stringResource(R.string.mobiletina_back_home))
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(painterResource(R.drawable.ic_navigation_menu), stringResource(R.string.mobiletina_menu))
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 520.dp).fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(MobileTinaAboutContent.aboutTitle(), style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(MobileTinaAboutContent.aboutSubtitle(), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    AboutContact(MobileTinaAboutContent.branchOne(), R.drawable.ic_mobiletina_instagram,
                        R.color.mobiletina_instagram) { onOpenLink(MobileTinaAboutContent.instagramOne()) }
                    AboutContact(MobileTinaAboutContent.branchTwo(), R.drawable.ic_mobiletina_instagram,
                        R.color.mobiletina_instagram) { onOpenLink(MobileTinaAboutContent.instagramTwo()) }
                    AboutContact(MobileTinaAboutContent.pageThree(), R.drawable.ic_mobiletina_instagram,
                        R.color.mobiletina_instagram) { onOpenLink(MobileTinaAboutContent.instagramThree()) }
                    AboutContact(MobileTinaAboutContent.developer(), R.drawable.ic_mobiletina_telegram,
                        R.color.mobiletina_telegram) { onOpenLink(MobileTinaAboutContent.telegram()) }
                    Text(MobileTinaAboutContent.storesTitle(), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    listOf(MobileTinaAboutContent.storeOne(), MobileTinaAboutContent.storeTwo()).forEach { address ->
                        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Text(address, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(18.dp), textAlign = TextAlign.Start)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun AboutContact(title: String, icon: Int, accent: Int, onClick: () -> Unit) {
    val color = colorResource(accent)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(color))
            Icon(painterResource(icon), null, Modifier.padding(start = 16.dp).size(24.dp), tint = color)
            Text(title, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(16.dp))
        }
    }
}
