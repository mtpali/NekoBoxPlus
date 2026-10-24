package io.nekohasekai.sagernet.ui.compose

import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.ProfileShareCapabilities

/** Exclave's compact native row, with every original action available in its overflow menu. */
@Composable
internal fun MobileTinaProfileCard(
    model: ProfileCardModel,
    onClick: () -> Unit,
    onStatusClick: (() -> Unit)?,
    onEdit: () -> Unit,
    onUrlTest: () -> Unit,
    onShare: (ProfileShareAction) -> Unit,
    onDelete: () -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        factory = { context -> LayoutInflater.from(context).inflate(R.layout.mobiletina_profile, null, false) },
        update = { root ->
            root.setOnClickListener { onClick() }
            (root as MaterialCardView).setCardBackgroundColor(ContextCompat.getColor(root.context, R.color.mobiletina_manual_dock_bg))
            root.findViewById<TextView>(R.id.profile_name).text = if (model.countryVisible) model.entity.displayName() else model.name
            root.findViewById<View>(R.id.selected_view).visibility = if (model.selected) View.VISIBLE else View.INVISIBLE
            root.findViewById<TextView>(R.id.profile_status).apply {
                text = model.status; setTextColor(model.statusColor)
                setOnClickListener(if (onStatusClick == null) null else View.OnClickListener { onStatusClick() })
            }
            root.findViewById<View>(R.id.mobiletina_profile_more).setOnClickListener { anchor ->
                PopupMenu(root.context, anchor).apply {
                    if (model.showEdit) menu.add(0, 1, 0, R.string.edit).isEnabled = model.editEnabled
                    if (model.showUrlTest) menu.add(0, 2, 1, R.string.connection_test_url_test).isEnabled = model.urlTestEnabled
                    if (model.showShare) {
                        val capabilities = ProfileShareCapabilities.from(model.entity)
                        if (capabilities.links) {
                            val qr = menu.addSubMenu(R.string.share_qr_nfc)
                            if (capabilities.standardLinks) qr.add(0, 10, 0, R.string.standard)
                            qr.add(0, 11, 1, R.string.sn_link)
                            val clipboard = menu.addSubMenu(R.string.action_export_clipboard)
                            if (capabilities.standardLinks) clipboard.add(0, 12, 0, R.string.standard)
                            clipboard.add(0, 13, 1, R.string.sn_link)
                        }
                        if (capabilities.configuration) {
                            val config = menu.addSubMenu(R.string.menu_configuration)
                            config.add(0, 14, 0, R.string.action_export_clipboard)
                            config.add(0, 15, 1, R.string.action_export_file)
                        }
                    }
                    if (model.showDelete) menu.add(0, 3, 100, R.string.delete)
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> onEdit()
                            2 -> onUrlTest()
                            3 -> onDelete()
                            10 -> onShare(ProfileShareAction.STANDARD_QR)
                            11 -> onShare(ProfileShareAction.UNIVERSAL_QR)
                            12 -> onShare(ProfileShareAction.STANDARD_CLIPBOARD)
                            13 -> onShare(ProfileShareAction.UNIVERSAL_CLIPBOARD)
                            14 -> onShare(ProfileShareAction.CONFIGURATION_CLIPBOARD)
                            15 -> onShare(ProfileShareAction.CONFIGURATION_FILE)
                        }
                        true
                    }
                    show()
                }
            }
        },
    )
}
