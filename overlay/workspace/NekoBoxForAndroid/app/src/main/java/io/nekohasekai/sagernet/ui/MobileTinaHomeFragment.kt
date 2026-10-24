package io.nekohasekai.sagernet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.AppData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.utils.MobileTinaAssets
import io.nekohasekai.sagernet.utils.MobileTinaImportNormalizer
import io.nekohasekai.sagernet.widget.QRCodeDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

class MobileTinaHomeFragment : ToolbarFragment() {
    private val model: MobileTinaViewModel by activityViewModels()
    private val access = MobileTinaAccessCounter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state by model.state.collectAsStateWithLifecycle()
                val preferences = context.getSharedPreferences("mobiletina.ui", Context.MODE_PRIVATE)
                var welcome by remember { mutableStateOf(!preferences.getBoolean("welcomeSeen", false)) }
                var importDialog by remember { mutableStateOf(false) }
                var input by remember { mutableStateOf("") }
                var shareLink by remember { mutableStateOf<String?>(null) }
                NekoComposeTheme {
                    MobileTinaHomeScreen(
                        state = state,
                        onConnect = model::toggle,
                        onDrawer = { (requireActivity() as MainActivity).openDrawer() },
                        onManual = { navigate(R.id.nav_configuration) },
                        onGroups = { navigate(R.id.nav_group) },
                        onImport = { importDialog = true },
                        onRefresh = model::refreshNow,
                        onSubscriptionTap = {
                            if (access.tap(state.groupId, SystemClock.elapsedRealtime())) {
                                viewLifecycleOwner.lifecycleScope.launch {
                                    shareLink = withContext(Dispatchers.IO) {
                                        AppData.groups.getById(state.groupId)?.subscription?.link?.takeIf { it.isNotBlank() }
                                    }
                                }
                            }
                        },
                    )
                    if (welcome) {
                        AlertDialog(
                            onDismissRequest = { preferences.edit().putBoolean("welcomeSeen", true).apply(); welcome = false },
                            title = { Text(stringResource(R.string.mobiletina_welcome_title)) },
                            text = { Text(stringResource(R.string.mobiletina_welcome_body)) },
                            confirmButton = { TextButton(onClick = { preferences.edit().putBoolean("welcomeSeen", true).apply(); welcome = false }) {
                                Text(stringResource(android.R.string.ok))
                            } },
                        )
                    }
                    if (importDialog) {
                        AlertDialog(
                            onDismissRequest = { importDialog = false },
                            title = { Text(stringResource(R.string.mobiletina_import)) },
                            text = {
                                Column {
                                    OutlinedTextField(
                                        value = input,
                                        onValueChange = { if (it.length <= 4_194_304) input = it },
                                        label = { Text(stringResource(R.string.mobiletina_import_hint)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2, maxLines = 5,
                                    )
                                    TextButton(onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        input = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().take(4_194_304)
                                    }) { Text(stringResource(R.string.mobiletina_paste)) }
                                }
                            },
                            confirmButton = {
                                TextButton(enabled = input.isNotBlank(), onClick = {
                                    val text = input
                                    input = ""
                                    importDialog = false
                                    importText(text)
                                }) { Text(stringResource(R.string.mobiletina_import)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { importDialog = false }) { Text(stringResource(android.R.string.cancel)) }
                            },
                        )
                    }
                    shareLink?.let { link ->
                        AlertDialog(
                            onDismissRequest = { shareLink = null },
                            title = { Text(stringResource(R.string.mobiletina_subscription)) },
                            text = { Text(stringResource(R.string.mobiletina_share_hint)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    shareLink = null
                                    if (!parentFragmentManager.isStateSaved) {
                                        QRCodeDialog(link, state.groupName).show(parentFragmentManager, "mobiletina.qr")
                                    }
                                }) { Text(stringResource(R.string.mobiletina_qr)) }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                        .setPrimaryClip(ClipData.newPlainText(getString(R.string.mobiletina_subscription), link))
                                    shareLink = null
                                }) { Text(stringResource(android.R.string.copy)) }
                            },
                        )
                    }
                }
            }
        }

    override fun onResume() {
        super.onResume()
        model.refresh(foreground = true)
    }

    private fun navigate(id: Int) { (activity as? MainActivity)?.displayFragmentWithId(id) }

    private fun importText(text: String) {
        val main = activity as? MainActivity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val subscription = MobileTinaImportNormalizer.subscriptionUrl(text)
                if (subscription != null) {
                    main.importSubscription(Uri.parse(subscription))
                    return@launch
                }
                val count = withContext(Dispatchers.IO) {
                    val profiles = MobileTinaImportNormalizer.payloadCandidates(text).firstNotNullOfOrNull { candidate ->
                        RawUpdater.parseRaw(candidate)?.takeIf { it.isNotEmpty() }
                    } ?: error("Unsupported configuration")
                    ProfileManager.createProfiles(DataStore.selectedGroupForImport(), profiles).size
                }
                if (isAdded) {
                    model.refresh()
                    main.snackbar(resources.getQuantityString(R.plurals.added, count, count)).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isAdded) main.snackbar(getString(R.string.no_proxies_found)).show()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileTinaHomeScreen(
    state: MobileTinaHomeState,
    onConnect: () -> Unit,
    onDrawer: () -> Unit,
    onManual: () -> Unit,
    onGroups: () -> Unit,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
    onSubscriptionTap: () -> Unit,
) {
    val active = state.service.connected
    val busy = state.pending || state.service == io.nekohasekai.sagernet.bg.BaseService.State.Connecting ||
        state.service == io.nekohasekai.sagernet.bg.BaseService.State.Stopping
    val imageState = when {
        state.expired -> MobileTinaAssets.State.RED
        active -> MobileTinaAssets.State.BLUE
        busy -> MobileTinaAssets.State.YELLOW
        state.failed -> MobileTinaAssets.State.RED
        else -> MobileTinaAssets.State.WHITE
    }
    val bitmap by produceState<android.graphics.Bitmap?>(null, imageState) {
        value = withContext(Dispatchers.IO) { MobileTinaAssets.bitmap(imageState) }
    }
    val status = when {
        state.expired -> R.string.mobiletina_expired
        state.testing -> R.string.mobiletina_testing
        active -> R.string.mobiletina_status_connected
        busy -> R.string.mobiletina_status_connecting
        state.failed -> R.string.mobiletina_status_failed
        else -> R.string.mobiletina_status_disconnected
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                navigationIcon = { IconButton(onClick = onDrawer) {
                    Icon(painterResource(R.drawable.ic_navigation_menu), stringResource(R.string.mobiletina_menu))
                } },
                actions = { IconButton(onClick = onGroups) {
                    Icon(painterResource(R.drawable.ic_baseline_view_list_24), stringResource(R.string.menu_group))
                } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Surface(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = {},
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { selected = true },
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(stringResource(R.string.mobiletina_auto)) }
                    TextButton(onClick = onManual, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.mobiletina_manual))
                    }
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 440.dp).fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onGroups, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Icon(painterResource(R.drawable.ic_baseline_view_list_24), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(state.groupName.ifBlank { stringResource(R.string.mobiletina_add_subscription) },
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.sizeIn(maxWidth = 196.dp, maxHeight = 196.dp).fillMaxWidth().aspectRatio(1f)
                        .clip(CircleShape).clickable(role = Role.Button, onClickLabel = stringResource(
                            if (active || busy) R.string.mobiletina_disconnect else R.string.mobiletina_connect,
                        ), onClick = onConnect),
                    contentAlignment = Alignment.Center,
                ) {
                    bitmap?.let { Image(it.asImageBitmap(), stringResource(status), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                        ?: CircularProgressIndicator()
                }
                Text(stringResource(status), style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    color = if (state.expired || state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                if (state.message.isNotBlank()) Text(state.message, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                if (state.profileName.isNotBlank()) {
                    Text(state.profileName, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
                if (state.ping > 0) Text(stringResource(R.string.mobiletina_ping_value, state.ping),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (state.usage.visible) {
                    Card(onClick = onSubscriptionTap, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.mobiletina_subscription), style = MaterialTheme.typography.titleMedium)
                            state.usage.used?.let { used ->
                                val total = state.usage.total?.takeIf { it > 0 }?.let(::formatBytes)
                                    ?: stringResource(R.string.mobiletina_unlimited)
                                Text(stringResource(R.string.mobiletina_usage, formatBytes(used), total))
                            }
                            if ((state.usage.total ?: 0) > 0 && state.usage.used != null) {
                                LinearProgressIndicator(progress = { state.usage.fraction }, modifier = Modifier.fillMaxWidth())
                            }
                            state.usage.expiresAt?.let { expiry ->
                                val days = ceil((expiry - MobileTinaSubscriptionExpiry.nowMillis() / 1000L) / 86400.0).toInt().coerceAtLeast(0)
                                Text(stringResource(R.string.mobiletina_days_left, days))
                            }
                        }
                    }
                }
                Button(onClick = onImport, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    shape = RoundedCornerShape(16.dp)) {
                    Icon(painterResource(R.drawable.ic_action_note_add), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.mobiletina_import))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)) {
                        Icon(painterResource(R.drawable.ic_baseline_refresh_24), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.mobiletina_refresh), textAlign = TextAlign.Center)
                    }
                    OutlinedButton(onClick = onManual, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)) {
                        Icon(painterResource(R.drawable.ic_action_description), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.mobiletina_manual))
                    }
                }
                Text(stringResource(R.string.mobiletina_profile_count, state.count), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
            }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
    var number = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    while (number >= 1024 && unit < units.lastIndex) { number /= 1024; unit++ }
    return String.format(java.util.Locale.getDefault(), "%.1f %s", number, units[unit])
}
