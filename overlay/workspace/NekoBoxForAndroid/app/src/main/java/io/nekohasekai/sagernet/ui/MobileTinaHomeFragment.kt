package io.nekohasekai.sagernet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.AppData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.utils.MobileTinaAssets
import io.nekohasekai.sagernet.widget.QRCodeDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Exclave's native Auto layout connected to NekoBox's existing state and service lifecycle. */
class MobileTinaHomeFragment : ToolbarFragment(R.layout.mobiletina_home) {
    private val model: MobileTinaViewModel by activityViewModels()
    private val access = MobileTinaAccessCounter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        MobileTinaHomeChrome.bind(view, requireActivity() as MainActivity, auto = true)
        view.findViewById<View>(R.id.fab_auto).setOnClickListener { model.toggle() }
        view.findViewById<View>(R.id.tv_auto_ping).setOnClickListener { refreshPing() }
        view.findViewById<View>(R.id.subscription_card).setOnClickListener {
            val state = model.state.value
            if (access.tap(state.groupId, SystemClock.elapsedRealtime())) showSubscription(state)
        }
        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(R.id.auto_panel)) { panel, insets ->
            panel.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collectLatest { render(view, it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refresh(foreground = true)
    }

    private suspend fun render(root: View, state: MobileTinaHomeState) {
        val active = state.service.connected
        val busy = state.pending || state.service == io.nekohasekai.sagernet.bg.BaseService.State.Connecting ||
            state.service == io.nekohasekai.sagernet.bg.BaseService.State.Stopping
        val status = when {
            state.expired -> R.string.mobiletina_expired
            state.testing -> R.string.mobiletina_testing
            active -> R.string.mobiletina_status_connected
            busy -> R.string.mobiletina_status_connecting
            state.failed -> R.string.mobiletina_status_failed
            else -> R.string.mobiletina_status_disconnected
        }
        root.findViewById<TextView>(R.id.tv_auto_status).apply {
            setText(status)
            setTextColor(ContextCompat.getColor(context,
                if (state.expired || state.failed) R.color.material_red_500 else R.color.mobiletina_primary_content))
        }
        root.findViewById<TextView>(R.id.tv_auto_message).apply { text = state.message; isVisible = state.message.isNotBlank() }
        root.findViewById<TextView>(R.id.tv_auto_server).text = if (active || busy) state.profileName else ""
        root.findViewById<TextView>(R.id.tv_auto_ping).apply {
            isVisible = active && state.ping > 0
            text = if (state.ping > 0) getString(R.string.mobiletina_ping_value, state.ping) else ""
        }
        root.findViewById<View>(R.id.subscription_card).isVisible = state.usage.visible && !state.expired
        root.findViewById<TextView>(R.id.tv_subscription_name).text =
            state.groupName.ifBlank { io.nekohasekai.sagernet.utils.MobileTinaAboutContent.brandTitle() }
        root.findViewById<ProgressBar>(R.id.subscription_progress).apply {
            isVisible = (state.usage.total ?: 0) > 0 && state.usage.used != null
            progress = (state.usage.fraction * 100).toInt().coerceIn(0, 100)
        }
        root.findViewById<TextView>(R.id.tv_subscription_days).apply {
            isVisible = state.usage.expiresAt != null
            state.usage.expiresAt?.let { expiry ->
                val days = ((expiry - MobileTinaSubscriptionExpiry.nowMillis() / 1000L) / 86400L).coerceAtLeast(0L)
                text = getString(R.string.mobiletina_days_remaining, days)
            }
        }
        root.findViewById<TextView>(R.id.tv_subscription_traffic).apply {
            isVisible = state.usage.used != null
            state.usage.used?.let { used ->
                val total = state.usage.total
                val gb = 1024.0 * 1024.0 * 1024.0
                text = when {
                    total == 0L -> getString(R.string.mobiletina_unlimited)
                    total != null -> getString(R.string.mobiletina_traffic_summary, used / gb, total / gb)
                    else -> formatBytes(used)
                }
            }
        }
        val artwork = when {
            state.expired -> MobileTinaAssets.State.RED
            active -> MobileTinaAssets.State.BLUE
            busy -> MobileTinaAssets.State.YELLOW
            state.failed -> MobileTinaAssets.State.RED
            else -> MobileTinaAssets.State.WHITE
        }
        val bitmap = withContext(Dispatchers.IO) { MobileTinaAssets.bitmap(artwork) }
        root.findViewById<ImageView>(R.id.fab_auto).apply { setImageBitmap(bitmap); contentDescription = getString(status) }
    }

    private fun refreshPing() {
        val main = activity as? MainActivity ?: return
        if (!model.state.value.service.connected) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val ping = main.urlTest()
                    AppData.profiles.getById(DataStore.selectedProxy)?.let {
                        it.ping = ping; it.status = 1
                        ProfileManager.updateProfile(it)
                    }
                }
                model.refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (isAdded) main.snackbar(R.string.mobiletina_status_failed).show() }
        }
    }

    private fun showSubscription(state: MobileTinaHomeState) {
        viewLifecycleOwner.lifecycleScope.launch {
            val link = withContext(Dispatchers.IO) {
                AppData.groups.getById(state.groupId)?.subscription?.takeIf { it.protectedContent != true }?.link
            }
            if (!isAdded || link.isNullOrBlank()) return@launch
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.mobiletina_subscription)
                .setMessage(R.string.mobiletina_share_hint)
                .setPositiveButton(R.string.mobiletina_qr) { _, _ ->
                    if (!parentFragmentManager.isStateSaved) QRCodeDialog(link, state.groupName).show(parentFragmentManager, "mobiletina.qr")
                }.setNegativeButton(android.R.string.copy) { _, _ ->
                    (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText(getString(R.string.mobiletina_subscription), link))
                }.show()
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
