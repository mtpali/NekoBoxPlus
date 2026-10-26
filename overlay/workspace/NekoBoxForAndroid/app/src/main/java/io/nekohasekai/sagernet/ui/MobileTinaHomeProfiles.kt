package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.ProfileDataSource
import io.nekohasekai.sagernet.database.ProxyEntity

/** Paint metadata without deserializing every config. Smart Connect still requests all members. */
internal object MobileTinaHomeProfiles {
    data class Summary(val profiles: List<ProxyEntity>, val selected: ProxyEntity?, val count: Int, val expired: Boolean)

    fun read(source: ProfileDataSource, groupId: Long, selectedId: Long, includeProfiles: Boolean = false): Summary {
        if (groupId <= 0) return Summary(emptyList(), null, 0, false)
        val members = if (includeProfiles) source.getByGroup(groupId) else emptyList()
        val total = if (includeProfiles) members.size.toLong() else source.countByGroup(groupId)
        if (total == 0L) return Summary(members, null, 0, false)
        val candidates = if (includeProfiles) members else source.getByGroupAndType(groupId, ProxyEntity.TYPE_SOCKS)
        val markers = candidates.count(MobileTinaExpiryRetirement::isMarker)
        val selected = if (includeProfiles) members.firstOrNull { it.id == selectedId }
            else source.getById(selectedId)?.takeIf { it.groupId == groupId }
        return Summary(members, selected, (total - markers).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), markers > 0)
    }
}
