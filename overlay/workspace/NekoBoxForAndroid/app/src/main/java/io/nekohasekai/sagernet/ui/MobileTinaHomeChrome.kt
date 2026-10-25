package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.AppData
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared native chrome; submenu parents never dispatch navigation actions. */
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
        activity.lifecycleScope.launch {
            val order = withContext(Dispatchers.IO) {
                AppData.groups.getById(DataStore.currentGroupId())?.order ?: GroupOrder.BY_DELAY
            }
            if (!anchor.isAttachedToWindow || !anchor.isShown || activity.isFinishing) return@launch
            val configuration = activity.supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment
            configuration?.refreshToolbarMenuState()
            configuration?.getCurrentGroupFragment()?.checkOrderMenu()
            val source = configuration?.toolbarOrNull()?.menu ?: PopupMenu(activity, anchor).apply {
                menuInflater.inflate(R.menu.add_profile_menu, menu)
            }.menu
            val popup = PopupMenu(activity, anchor, Gravity.END)
            if (add) {
                source.findItem(R.id.action_add)?.subMenu?.let { copyMenu(it, popup.menu) }
            } else {
                popup.menu.add(0, R.id.action_update_subscription, 0, R.string.mobiletina_more_update)
                popup.menu.add(0, R.id.action_connection_url_test, 1, R.string.mobiletina_more_speed_test)
                popup.menu.add(0, R.id.action_mobiletina_clear_configs, 2, R.string.mobiletina_delete_configs)
                popup.menu.add(0, R.id.action_mobiletina_group, 3, R.string.mobiletina_group)
                popup.menu.add(0, R.id.action_mobiletina_search, 4, R.string.mobiletina_search)
                source.findItem(R.id.action_misc)?.subMenu?.let {
                    copyMenu(it, popup.menu, setOf(R.id.action_update_subscription, R.id.action_connection_url_test, R.id.action_show_active, R.id.action_global_mode, R.id.nav_tools))
                }

            }
            val orderItem = when (order) {
                GroupOrder.BY_NAME -> R.id.action_order_by_name
                GroupOrder.BY_DELAY -> R.id.action_order_by_delay
                GroupOrder.MANUAL -> R.id.action_order_manual
                else -> R.id.action_order_origin
            }
            popup.menu.findItem(orderItem)?.isChecked = true
            val layoutItem = when (DataStore.groupLayoutMode) {
                1 -> R.id.action_layout_double
                2 -> R.id.action_layout_compact
                3 -> R.id.action_layout_alternate
                else -> R.id.action_layout_single
            }
            popup.menu.findItem(layoutItem)?.isChecked = true
            popup.setForceShowIcon(add)
            popup.setOnMenuItemClickListener { item ->
                if (item.hasSubMenu() || item.itemId == 0) return@setOnMenuItemClickListener false
                when (item.itemId) {
                    R.id.action_mobiletina_group -> activity.chooseMobileTinaSubscription()
                    R.id.action_update_subscription -> activity.refreshMobileTinaSubscription()
                    R.id.nav_route, R.id.nav_adblock, R.id.nav_tools, R.id.nav_logcat, R.id.nav_traffic ->
                        activity.displayFragmentWithId(item.itemId)
                    else -> activity.dispatchMobileTinaConfigurationAction(item.itemId)
                }
                true
            }
            popup.show()
        }
    }

    private fun copyMenu(source: Menu, target: Menu, excluded: Set<Int> = emptySet()) {
        for (index in 0 until source.size()) {
            val item = source.getItem(index)
            if (!item.isVisible || item.itemId in excluded) continue
            val copy: MenuItem = if (item.hasSubMenu()) {
                target.addSubMenu(item.groupId, item.itemId, target.size(), item.title).also {
                    copyMenu(item.subMenu!!, it, excluded)
                }.item
            } else target.add(item.groupId, item.itemId, target.size(), item.title)
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
