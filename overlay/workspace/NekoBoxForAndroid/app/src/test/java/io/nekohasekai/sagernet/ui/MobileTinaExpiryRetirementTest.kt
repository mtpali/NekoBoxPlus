package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import java.lang.reflect.Proxy
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class MobileTinaExpiryRetirementTest {
    private class Store : AutoCloseable {
        val groups = linkedMapOf<Long, ProxyGroup>()
        val profiles = linkedMapOf<Long, ProxyEntity>()
        private var nextId = 100L
        private inline fun <reified T> fake(crossinline call: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                call(method.name, args ?: emptyArray())
            } as T
        private val installed = AppData.installForTest(AppDataSources(
            profiles = fake<ProfileDataSource> { method, args ->
                when (method) {
                    "getByGroup" -> profiles.values.filter { it.groupId == args[0] }
                    "deleteByGroup" -> { profiles.entries.removeAll { it.value.groupId == args[0] }; Unit }
                    "addProxy" -> { val item = args[0] as ProxyEntity; (++nextId).also { profiles[it] = item } }
                    else -> error(method)
                }
            },
            groups = fake<GroupDataSource> { method, args ->
                when (method) {
                    "getById" -> groups[args[0] as Long]
                    "updateGroup" -> { (args[0] as ProxyGroup).let { groups[it.id] = it }; Unit }
                    else -> error(method)
                }
            },
            rules = fake<RuleDataSource> { method, _ -> error(method) },
            transactions = object : DatabaseTransactionRunner { override fun <T> run(block: () -> T): T = block() },
        ))
        override fun close() = installed.close()
        fun group(id: Long, link: String = "https://example.test/sub") {
            groups[id] = ProxyGroup(type = GroupType.SUBSCRIPTION).apply {
                this.id = id
                subscription = SubscriptionBean().applyDefaultValues().apply { this.link = link; lastUpdated = 42 }
            }
            profiles[id] = ProxyEntity(groupId = id).apply { this.id = id; putBean(SOCKSBean().applyDefaultValues()) }
        }
    }
    private fun digest(link: String) = MessageDigest.getInstance("SHA-256").digest(link.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun marker() = SOCKSBean().applyDefaultValues().apply { serverAddress = "1"; serverPort = 1; username = ""; password = "" }

    @Test fun aDueGroupRetiresOnceAndReplayedCallbacksNeverRecreateProfiles() {
        Store().use { store ->
            store.group(1); store.group(2)
            val result = MobileTinaExpiryRetirement.retire(1, digest("https://example.test/sub"), 42, marker())!!
            repeat(200) { assertNull(MobileTinaExpiryRetirement.retire(1, digest("https://example.test/sub"), 42, marker())) }
            assertEquals(2, store.profiles.size)
            assertSame(result, store.profiles[result.id])
            assertTrue(MobileTinaExpiryRetirement.isMarker(result))
            assertEquals(GroupType.BASIC, store.groups[1]!!.type)
            assertNull(store.groups[1]!!.subscription)
            assertEquals(GroupType.SUBSCRIPTION, store.groups[2]!!.type)
            assertEquals(2L, store.profiles[2]!!.groupId)
        }
    }
    @Test fun aRenewedSubscriptionCannotBeDeletedByAnOldWorker() {
        Store().use { store ->
            store.group(1)
            store.groups[1]!!.subscription!!.lastUpdated = 43
            assertNull(MobileTinaExpiryRetirement.retire(1, digest("https://example.test/sub"), 42, marker()))
            assertEquals(1, store.profiles.size)
            assertEquals(GroupType.SUBSCRIPTION, store.groups[1]!!.type)
            store.groups[1]!!.subscription!!.lastUpdated = 42
            store.groups[1]!!.subscription!!.link = "https://example.test/renewed"
            assertNull(MobileTinaExpiryRetirement.retire(1, digest("https://example.test/sub"), 42, marker()))
            assertEquals(1, store.profiles.size)
        }
    }
    @Test fun retirementKeepsRawGithubContentProtectedAndDisablesAutomaticRefresh() {
        Store().use { store ->
            val link = "https://raw.githubusercontent.com/a/b/main/sub"
            store.group(1, link)
            assertNotNull(MobileTinaExpiryRetirement.retire(1, digest(link), 42, marker()))
            assertTrue(store.groups[1]!!.subscription!!.protectedContent)
            assertFalse(store.groups[1]!!.subscription!!.autoUpdate)
            assertEquals("", store.groups[1]!!.subscription!!.link)
        }
    }
}
