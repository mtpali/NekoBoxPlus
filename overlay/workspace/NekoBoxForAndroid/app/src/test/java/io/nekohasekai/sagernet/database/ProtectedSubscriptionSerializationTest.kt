package io.nekohasekai.sagernet.database

import com.esotericsoftware.kryo.io.ByteBufferInput
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import com.esotericsoftware.kryo.io.ByteBufferOutput
import org.junit.Assert.*
import org.junit.Test

class ProtectedSubscriptionSerializationTest {
    @Test fun protectionSurvivesStoredSubscriptionRoundTrip() {
        val bean = SubscriptionBean().applyDefaultValues().apply {
            link = "https://example.com/sub"
            protectedContent = true
        }
        val restored = KryoConverters.deserialize(SubscriptionBean(), KryoConverters.serialize(bean))
        assertEquals(true, restored.protectedContent)
        assertEquals(bean.link, restored.link)
    }

    @Test fun legacyVersionEightRecordIsUnprotected() {
        val bean = SubscriptionBean().applyDefaultValues().apply { link = "https://example.com/normal" }
        val bytes = KryoConverters.serialize(bean)
        val old = bytes.copyOf(bytes.size - 1)
        ByteBufferOutput(4).apply { writeInt(8) }.toBytes().copyInto(old, 0)
        val restored = SubscriptionBean().also {
            it.deserializeFromBuffer(ByteBufferInput(old))
            it.initializeDefaultValues()
        }
        assertEquals(false, restored.protectedContent)
        assertEquals(bean.link, restored.link)
    }
}
