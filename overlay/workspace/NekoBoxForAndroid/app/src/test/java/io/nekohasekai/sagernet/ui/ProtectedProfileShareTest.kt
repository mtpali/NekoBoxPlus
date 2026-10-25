package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.ProxyEntity
import org.junit.Assert.*
import org.junit.Test

class ProtectedProfileShareTest {
    @Test fun protectedCardHasNoExportCapabilities() {
        val capabilities = ProfileShareCapabilities.from(ProxyEntity(), protectedContent = true)
        assertFalse(capabilities.links)
        assertFalse(capabilities.standardLinks)
        assertFalse(capabilities.configuration)
    }
}
