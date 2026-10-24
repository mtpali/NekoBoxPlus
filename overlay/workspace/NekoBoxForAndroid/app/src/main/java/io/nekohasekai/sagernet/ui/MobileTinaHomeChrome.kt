package io.nekohasekai.sagernet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.text.InputFilter
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.utils.MobileTinaImportNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native header and menus shared by Auto and Manual, using the owner's Exclave layout. */
internal object MobileTinaHomeChrome {
    fun bind(root: View, activity: MainActivity, auto: Boolean) {
        val header = root.findViewById<View>(R.id.mobiletina_home_header) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(header) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
            view.setPadding(view.paddingLeft, top, view.paddingRight, view.paddingBottom)
            view.layoutParams = view.layoutParams.apply { height = dp(view.context, 150) + top }
            insets
        }
        ViewCompat.requestApplyInsets(header)
        header.findViewById<View>(R.id.mobiletina_menu).setOnClickListener { activity.openDrawer() }
        header.findViewById<View>(R.id.mobiletina_scan).setOnClickListener {
            activity.startActivity(Intent(activity, ScannerActivity::class.java))
        }
        header.findViewById<View>(R.id.mobiletina_add).setOnClickListener { showMenu(activity, it, add = true) }
        header.findViewById<View>(R.id.mobiletina_more).setOnClickListener { showMenu(activity, it, add = false) }
        header.findViewById<View>(R.id.btn_mode_auto).setOnClickListener { activity.displayFragmentWithId(R.id.nav_mobiletina) }
        header.findViewById<View>(R.id.btn_mode_manual).setOnClickListener { activity.displayFragmentWithId(R.id.nav_configuration) }
        for ((id, selected) in listOf(R.id.btn_mode_auto to auto, R.id.btn_mode_manual to !auto)) {
            header.findViewById<MaterialButton>(id).apply {
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context,
                    if (selected) R.color.mobiletina_mode_selected else R.color.mobiletina_mode_unselected))
                setTextColor(ContextCompat.getColor(context,
                    if (selected) R.color.mobiletina_mode_text_selected else R.color.mobiletina_mode_text_unselected))
                isSelected = selected
            }
        }
    }

    private fun showMenu(activity: MainActivity, anchor: View, add: Boolean) {
        val configuration = activity.supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment
        val source = configuration?.toolbarOrNull()?.menu ?: PopupMenu(activity, anchor).apply {
            menuInflater.inflate(R.menu.add_profile_menu, menu)
        }.menu
        val popup = PopupMenu(activity, anchor, Gravity.END)
        if (add) {
            popup.menu.add(0, R.id.action_mobiletina_import, 0, R.string.mobiletina_import)
            source.findItem(R.id.action_add)?.subMenu?.let { copyMenu(it, popup.menu) }
        } else {
            popup.menu.add(0, R.id.action_mobiletina_group, 0, R.string.mobiletina_group)
            popup.menu.add(0, R.id.action_mobiletina_search, 1, R.string.mobiletina_search)
            // Use the complete native menu, including tests, layout, sorting and batch actions.
            source.findItem(R.id.action_misc)?.subMenu?.let { copyMenu(it, popup.menu) }
            popup.menu.findItem(R.id.action_update_subscription)?.setTitle(R.string.mobiletina_more_update)
            popup.menu.findItem(R.id.action_connection_url_test)?.setTitle(R.string.mobiletina_more_speed_test)
        }
        popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_mobiletina_import -> activity.showMobileTinaImport()
                R.id.action_mobiletina_group -> activity.displayFragmentWithId(R.id.nav_group)
                R.id.action_update_subscription -> activity.refreshMobileTinaSubscription()
                else -> {
                    var target = activity.supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment
                    if (target == null) {
                        activity.displayFragmentWithId(R.id.nav_configuration)
                        activity.supportFragmentManager.executePendingTransactions()
                        target = activity.supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment
                    }
                    target?.dispatchMobileTinaAction(item.itemId)
                }
            }
            true
        }
        popup.show()
    }

    private fun copyMenu(source: Menu, target: Menu) {
        for (index in 0 until source.size()) {
            val item = source.getItem(index)
            if (!item.isVisible) continue
            val copy = if (item.hasSubMenu()) {
                target.addSubMenu(item.groupId, item.itemId, item.order, item.title).also {
                    copyMenu(item.subMenu!!, it)
                }.item
            } else target.add(item.groupId, item.itemId, item.order, item.title)
            copy.icon = item.icon
            copy.isEnabled = item.isEnabled
            copy.isCheckable = item.isCheckable
            copy.isChecked = item.isChecked
        }
    }

    fun renderDock(root: View, state: MobileTinaHomeState) {
        root.findViewById<ImageView>(R.id.fab_manual)?.apply {
            setImageResource(if (state.service.connected) R.drawable.mt_manual_fab else R.drawable.mt_manual_stop)
            contentDescription = context.getString(if (state.service.connected) R.string.mobiletina_disconnect else R.string.mobiletina_connect)
            alpha = if (state.pending || state.testing) 0.7f else 1f
        }
        root.findViewById<TextView>(R.id.tv_manual_selected)?.text =
            if (state.expired) root.context.getString(R.string.mobiletina_expired) else if (state.service.connected || state.pending) state.profileName else ""
    }

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}

internal object MobileTinaImportDialog {
    fun show(activity: MainActivity, model: MobileTinaViewModel) {
        val input = TextInputEditText(activity).apply {
            minLines = 2; maxLines = 5
            filters = arrayOf(InputFilter.LengthFilter(4_194_304))
        }
        val layout = TextInputLayout(activity).apply {
            hint = activity.getString(R.string.mobiletina_import_hint)
            val padding = MobileTinaHomeChrome.dp(activity, 20)
            setPadding(padding, padding, padding, 0)
            addView(input, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.mobiletina_import).setView(layout)
            .setPositiveButton(R.string.mobiletina_import) { _, _ ->
                val text = input.text?.toString().orEmpty()
                activity.lifecycleScope.launch {
                    try {
                        val subscription = MobileTinaImportNormalizer.subscriptionUrl(text)
                        if (subscription != null) activity.importSubscription(Uri.parse(subscription))
                        else {
                            val count = withContext(Dispatchers.IO) {
                                val profiles = MobileTinaImportNormalizer.payloadCandidates(text).firstNotNullOfOrNull {
                                    RawUpdater.parseRaw(it)?.takeIf { profiles -> profiles.isNotEmpty() }
                                } ?: error("Unsupported configuration")
                                ProfileManager.createProfiles(DataStore.selectedGroupForImport(), profiles).size
                            }
                            model.refresh()
                            activity.snackbar(activity.resources.getQuantityString(R.plurals.added, count, count)).show()
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { activity.snackbar(R.string.no_proxies_found).show() }
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.mobiletina_paste, null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = !input.text.isNullOrBlank()
            input.addTextChangedListener { dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = !it.isNullOrBlank() }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                input.setText(clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty().take(4_194_304))
            }
        }
        dialog.show()
    }
}
