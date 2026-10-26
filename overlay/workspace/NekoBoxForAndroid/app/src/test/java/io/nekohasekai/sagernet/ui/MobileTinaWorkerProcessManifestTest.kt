package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class MobileTinaWorkerProcessManifestTest {
    private fun elements(tag: String): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }
    @Test fun everyWorkManagerExecutionServiceRunsWithTheRealVpnService() {
        val services = elements("service").associateBy { it.getAttribute("android:name") }
        for (name in listOf("androidx.work.multiprocess.RemoteWorkManagerService",
            "androidx.work.impl.background.systemjob.SystemJobService",
            "androidx.work.impl.background.systemalarm.SystemAlarmService",
            "androidx.work.impl.foreground.SystemForegroundService")) {
            assertEquals(name, ":bg", services[name]?.getAttribute("android:process"))
        }
        assertEquals(":bg", services["io.nekohasekai.sagernet.bg.VpnService"]?.getAttribute("android:process"))
        assertTrue(File("src/main/java/io/nekohasekai/sagernet/SagerNet.kt").readText().contains("setDefaultProcessName(\"\${BuildConfig.APPLICATION_ID}:bg\")"))
    }
    @Test fun deadlineAlarmsDoNotStartTheUiProcessAndEagerWorkInitializationRemainsRemoved() {
        for (entry in elements("receiver").filter { it.getAttribute("android:name").startsWith("androidx.work.") }) {
            assertEquals(entry.getAttribute("android:name"), ":bg", entry.getAttribute("android:process"))
        }
        val receiver = elements("receiver").single { it.getAttribute("android:name").endsWith("MobileTinaExpiryReceiver") }
        assertEquals(":bg", receiver.getAttribute("android:process"))
        val provider = elements("provider").single { it.getAttribute("android:name") == "androidx.startup.InitializationProvider" }
        assertEquals("remove", provider.getAttribute("tools:node"))
    }
}
