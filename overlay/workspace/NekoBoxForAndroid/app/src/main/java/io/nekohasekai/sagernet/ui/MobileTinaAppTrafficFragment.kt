package io.nekohasekai.sagernet.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MobileTinaAppTrafficFragment : ToolbarFragment() {
    private data class DisplayRow(val usage: MobileTinaAppTrafficRow, val name: String)
    private var snapshot by mutableStateOf(MobileTinaAppTrafficSnapshot())
    private var rows by mutableStateOf(emptyList<DisplayRow>())
    private var enabled by mutableStateOf(false)
    private var requestId = ""
    private var receiverContext: Context? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getStringExtra("request") ?: return
            if (id == requestId) showSnapshot(id, intent.getStringExtra("snapshot").orEmpty())
        }
    }

    override fun onStart() {
        super.onStart()
        val context = requireContext().applicationContext
        ContextCompat.registerReceiver(context, receiver, IntentFilter(MobileTinaAppTraffic.ACTION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverContext = context
        refresh()
    }
    override fun onStop() {
        receiverContext?.unregisterReceiver(receiver)
        receiverContext = null
        requestId = ""
        super.onStop()
    }
    override fun onBackPressed(): Boolean {
        (activity as? MainActivity)?.displayFragment(SettingsFragment())
        return true
    }
    private fun refresh(clear: Boolean = false) {
        val context = context?.applicationContext ?: return
        val id = UUID.randomUUID().toString()
        requestId = id
        lifecycleScope.launch {
            val loggingEnabled = withContext(Dispatchers.IO) { MobileTinaAppTraffic.enabled }
            enabled = loggingEnabled
            val active = DataStore.serviceState.started
            if (id != requestId) return@launch
            if (active || clear) {
                context.sendBroadcast(Intent(context, MobileTinaAppTrafficReceiver::class.java)
                    .putExtra("request", id).putExtra("clear", clear))
            } else {
                val json = withContext(Dispatchers.IO) {
                    MobileTinaAppTrafficModel.encode(MobileTinaAppTraffic.readSaved())
                }
                showSnapshot(id, json)
            }
        }
    }
    private fun showSnapshot(id: String, json: String) {
        val app = context?.applicationContext ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val value = MobileTinaAppTrafficModel.decode(json)
                val pm = app.packageManager
                value to value.rows.map { usage ->
                    val packages = if (usage.uid < 0) emptyList() else
                        pm.getPackagesForUid(usage.uid)?.sorted().orEmpty()
                    val names = packages.map { name ->
                        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString() }.getOrDefault(name)
                    }.distinct()
                    DisplayRow(usage, if (names.isEmpty()) app.getString(R.string.mobiletina_log_unknown)
                        else names.joinToString(" / "))
                }
            }
            if (id == requestId) { snapshot = result.first; rows = result.second }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                NekoComposeTheme {
                    BackHandler { onBackPressed() }
                    Scaffold(
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        topBar = {
                            TopAppBar(title = { Text(stringResource(R.string.mobiletina_log)) },
                                navigationIcon = {
                                    IconButton(onClick = { onBackPressed() }) {
                                        Icon(painterResource(R.drawable.baseline_arrow_back_24),
                                            stringResource(R.string.abc_action_bar_up_description))
                                    }
                                }, actions = {
                                    IconButton(onClick = { refresh() }) {
                                        Icon(painterResource(R.drawable.ic_baseline_refresh_24),
                                            stringResource(R.string.mobiletina_log_refresh))
                                    }
                                    IconButton(onClick = { refresh(clear = true) }) {
                                        Icon(painterResource(R.drawable.ic_action_delete),
                                            stringResource(R.string.mobiletina_log_clear))
                                    }
                                })
                        },
                    ) { padding ->
                        LazyColumn(Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
                            item {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.mobiletina_log_enable), style = MaterialTheme.typography.bodyMedium)
                                        Text(stringResource(R.string.mobiletina_log_next_connection),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = enabled, onCheckedChange = { checked ->
                                        enabled = checked
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            DataStore.configurationStore.putBoolean(MobileTinaAppTraffic.KEY, checked)
                                        }
                                    })
                                }
                                Text(stringResource(if (snapshot.live) R.string.mobiletina_log_current else R.string.mobiletina_log_last),
                                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (rows.isEmpty()) item {
                                Text(stringResource(R.string.mobiletina_log_empty), Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            items(rows, key = { it.usage.uid }) { row ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium)
                                        Text(stringResource(R.string.mobiletina_log_details,
                                            MobileTinaAppTrafficModel.volume(row.usage.download),
                                            MobileTinaAppTrafficModel.volume(row.usage.upload)),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(MobileTinaAppTrafficModel.volume(row.usage.total), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            item {
                                Text(stringResource(R.string.mobiletina_log_hint), Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
}
