package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.ProfileDataSource
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class MobileTinaHomeProfilesTest {
    private fun source(total: Long, selected: ProxyEntity?, socks: List<ProxyEntity>, members: List<ProxyEntity>? = null): ProfileDataSource =
        Proxy.newProxyInstance(ProfileDataSource::class.java.classLoader, arrayOf(ProfileDataSource::class.java)) { _, method, args ->
            when (method.name) {
                "countByGroup" -> total
                "getById" -> selected
                "getByGroupAndType" -> { assertEquals(ProxyEntity.TYPE_SOCKS, args!![1]); socks }
                "getByGroup" -> members ?: error("Home metadata must not deserialize all profiles")
                else -> error(method.name)
            }
        } as ProfileDataSource
    private fun profile(id: Long, group: Long = 7, expired: Boolean = false) = ProxyEntity(groupId = group).apply {
        this.id = id
        putBean(SOCKSBean().applyDefaultValues().apply {
            serverAddress = if (expired) "1" else "example.org"
            serverPort = if (expired) 1 else 1080
            username = ""; password = ""
        })
    }
    @Test fun aLargeGroupCanPaintMetadataWithoutLoadingItsFullConfigList() {
        val selected = profile(10)
        val result = MobileTinaHomeProfiles.read(source(5000, selected, listOf(selected)), 7, 10)
        assertSame(selected, result.selected)
        assertEquals(5000, result.count)
        assertTrue(result.profiles.isEmpty()); assertFalse(result.expired)
    }
    @Test fun aMixedGroupStillDetectsEveryMarkerWithoutCountingItAsAWorkingConfig() {
        val selected = profile(10)
        val result = MobileTinaHomeProfiles.read(source(5000, selected, listOf(selected, profile(11, expired = true))), 7, 10)
        assertEquals(4999, result.count); assertTrue(result.expired)
        assertSame(selected, result.selected)
    }
    @Test fun smartConnectExplicitlyLoadsTheWholeGroup() {
        val selected = profile(10)
        val members = listOf(selected, profile(11), profile(12, expired = true))
        val result = MobileTinaHomeProfiles.read(source(3, null, emptyList(), members), 7, 10, includeProfiles = true)
        assertSame(members, result.profiles); assertSame(selected, result.selected)
        assertEquals(2, result.count); assertTrue(result.expired)
    }
    @Test fun aSelectedConfigFromAnotherGroupCannotAppearOnThisGroupCard() {
        val result = MobileTinaHomeProfiles.read(source(3, profile(10, group = 8), emptyList()), 7, 10)
        assertNull(result.selected); assertEquals(3, result.count)
    }
    @Test fun emptyOrMissingGroupsHaveNoSelectionOrWorkingProfiles() {
        for (id in listOf(0L, 7L)) {
            val result = MobileTinaHomeProfiles.read(source(0, profile(10), emptyList()), id, 10)
            assertNull(result.selected); assertEquals(0, result.count); assertFalse(result.expired)
        }
    }
}
