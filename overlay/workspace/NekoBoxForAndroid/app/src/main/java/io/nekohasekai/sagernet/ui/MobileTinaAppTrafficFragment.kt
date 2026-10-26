package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MobileTinaAppTrafficFragment : ToolbarFragment() {
    private data class DisplayRow(val uid: Int, val name: String, val volume: String)
    private var rows by mutableStateOf(emptyList<DisplayRow>())
    private var enabled by mutableStateOf(true)
    private var currentSession by mutableStateOf(false)
    private var loadFailed by mutableStateOf(false)
    private val labels = mutableMapOf<Int, String>()
    private val refreshMutex = Mutex()
    private val settingMutex = Mutex()
    private val messages = SnackbarHostState()
    private var queryJob: Job? = null
    private var settingRevision = 0L

    override fun onStart() { super.onStart(); refresh() }
    override fun onStop() { queryJob?.cancel(); super.onStop() }
    override fun onBackPressed(): Boolean {
        (activity as? MainActivity)?.displayFragment(SettingsFragment())
        return true
    }
    private fun refresh(clear: Boolean = false) {
        val app = context?.applicationContext ?: return
        val revision = settingRevision
        queryJob?.cancel()
        queryJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                refreshMutex.withLock {
                    val result = withContext(Dispatchers.IO) {
                        val loggingEnabled = MobileTinaAppTraffic.enabled
                        val value = MobileTinaAppTraffic.query(clear)
                        val pm = app.packageManager
                        val display = value.rows.map { usage ->
                            currentCoroutineContext().ensureActive()
                            val name = labels.getOrPut(usage.uid) {
                                val packages = if (usage.uid < 0) emptyList() else
                                    pm.getPackagesForUid(usage.uid)?.sorted().orEmpty()
                                val names = packages.map { name ->
                                    runCatching { pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString() }.getOrDefault(name)
                                }.distinct()
                                if (names.isEmpty()) app.getString(R.string.mobiletina_log_unknown) else names.joinToString(" / ")
                            }
                            DisplayRow(usage.uid, name, MobileTinaAppTrafficModel.volume(usage.total))
                        }
                        Triple(loggingEnabled, value.live, display)
                    }
                    if (revision == settingRevision) enabled = result.first
                    currentSession = result.second
                    rows = result.third
                    loadFailed = false
                }
            } catch (error: CancellationException) { throw error }
              catch (error: Exception) { Logs.w(error); loadFailed = true }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    NekoComposeTheme {
                        BackHandler { onBackPressed() }
                        Scaffold(
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                            snackbarHost = { SnackbarHost(messages) },
                            topBar = {
                                TopAppBar(title = { Text(stringResource(R.string.mobiletina_log)) },
                                    navigationIcon = {
                                        IconButton(onClick = { onBackPressed() }) {
                                            Icon(painterResource(R.drawable.baseline_arrow_back_24), stringResource(R.string.mobiletina_log_back))
                                        }
                                    }, actions = {
                                        IconButton(onClick = { refresh() }) {
                                            Icon(painterResource(R.drawable.ic_baseline_refresh_24), stringResource(R.string.mobiletina_log_refresh))
                                        }
                                        IconButton(onClick = { refresh(clear = true) }) {
                                            Icon(painterResource(R.drawable.ic_action_delete), stringResource(R.string.mobiletina_log_clear))
                                        }
                                    })
                            },
                        ) { padding ->
                            LazyColumn(Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
                                item {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.mobiletina_log_enable), style = MaterialTheme.typography.bodyMedium)
                                        Switch(checked = enabled, onCheckedChange = { checked ->
                                            enabled = checked
                                            settingRevision++
                                            val app = context.applicationContext
                                            viewLifecycleOwner.lifecycleScope.launch {
                                                settingMutex.withLock {
                                                    withContext(Dispatchers.IO) {
                                                        DataStore.configurationStore.putBoolean(MobileTinaAppTraffic.KEY, checked)
                                                    }
                                                }
                                                messages.showSnackbar(app.getString(R.string.mobiletina_log_next_connection))
                                            }
                                        })
                                        Spacer(Modifier.weight(1f))
                                        Text(stringResource(if (currentSession) R.string.mobiletina_log_current else R.string.mobiletina_log_last),
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (loadFailed || rows.isEmpty()) item {
                                    Text(stringResource(if (loadFailed) R.string.mobiletina_log_error else R.string.mobiletina_log_empty),
                                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                                }
                                items(rows, key = { it.uid }) { row ->
                                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(row.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium)
                                        Text(row.volume, style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
}
