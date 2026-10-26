package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

class MobileTinaAppTrafficModelTest {
    private fun row(uid: Int, up: Long, down: Long) = MobileTinaAppTrafficRow(uid, up, down)
    @Test fun rankingUsesBothDirectionsAndKeepsUnknownTraffic() {
        val result = MobileTinaAppTrafficModel.rows("""[{"uid":2,"upload":500,"download":1},{"uid":1,"upload":1,"download":800},{"uid":-1,"upload":0,"download":1}]""")
        assertEquals(listOf(1, 2, -1), result.map { it.uid })
    }
    @Test fun clearResetsOpenConnectionsButCountsSubsequentBytes() {
        val period = MobileTinaAppTrafficPeriod(10)
        val before = listOf(row(10001, 1000, 8000), row(10002, 100, 100))
        period.clear(before, 20)
        assertTrue(period.snapshot(before).rows.isEmpty())
        val after = period.snapshot(listOf(row(10001, 1200, 8300), row(10002, 100, 100), row(10003, 1, 1)))
        assertEquals(20L, after.started)
        assertEquals(listOf(row(10001, 200, 300), row(10003, 1, 1)), after.rows)
    }
    @Test fun repeatedClearAndNormalStopCannotResurrectClearedTraffic() {
        val period = MobileTinaAppTrafficPeriod(1)
        period.clear(listOf(row(1, 5, 10)), 2)
        period.clear(listOf(row(1, 10, 15)), 3)
        val stopped = period.snapshot(listOf(row(1, 11, 15)), live = false)
        val saved = MobileTinaAppTrafficModel.decode(MobileTinaAppTrafficModel.encode(stopped))
        assertEquals(listOf(row(1, 1, 0)), saved.rows)
        assertFalse(saved.live)
        assertEquals(3L, saved.started)
    }
    @Test fun aNewConnectionHasNoPreviousConnectionsBaseline() {
        val old = MobileTinaAppTrafficPeriod(1)
        old.clear(listOf(row(1, 1000, 1000)), 2)
        assertEquals(listOf(row(1, 10, 20)), MobileTinaAppTrafficPeriod(3).snapshot(listOf(row(1, 10, 20))).rows)
    }
    @Test fun unitsAreOnlyDecimalMegabytesAndGigabytes() {
        assertEquals("0 MB", MobileTinaAppTrafficModel.volume(0))
        assertEquals("<0.001 MB", MobileTinaAppTrafficModel.volume(1))
        assertEquals("0.001 MB", MobileTinaAppTrafficModel.volume(1000))
        assertEquals("1.000 MB", MobileTinaAppTrafficModel.volume(1_000_000))
        assertEquals("1.000 GB", MobileTinaAppTrafficModel.volume(1_000_000_000))
    }
    @Test fun totalsDoNotOverflowAndAFullPeriodFitsBelowStorageLimit() {
        assertEquals(Long.MAX_VALUE, row(1, Long.MAX_VALUE, 1).total)
        val full = MobileTinaAppTrafficSnapshot(1, false, (0 until MobileTinaAppTrafficModel.MAX_ROWS).map {
            row(Int.MAX_VALUE - it, Long.MAX_VALUE, Long.MAX_VALUE)
        })
        val encoded = MobileTinaAppTrafficModel.encode(full)
        assertTrue(encoded.toByteArray().size < MobileTinaAppTrafficModel.MAX_BYTES)
        assertEquals(MobileTinaAppTrafficModel.MAX_ROWS, MobileTinaAppTrafficModel.decode(encoded).rows.size)
    }
    @Test fun aSavedPeriodCanBeReadWithTheBoundedStreamReader() {
        val saved = MobileTinaAppTrafficSnapshot(25, false, listOf(row(1, 100, 200)))
        val bytes = MobileTinaAppTrafficModel.encode(saved).toByteArray()
        assertEquals(saved, MobileTinaAppTrafficModel.read(bytes.inputStream()))
        assertTrue(MobileTinaAppTrafficModel.read(byteArrayOf().inputStream()).rows.isEmpty())
    }
    @Test fun anOversizedStreamIsRejectedAfterReadingOnlyTheBoundedPrefix() {
        var read = 0
        val stream = object : java.io.InputStream() {
            override fun read(): Int { read++; return 32 }
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                java.util.Arrays.fill(buffer, off, off + len, 32.toByte())
                read += len
                return len
            }
        }
        assertTrue(MobileTinaAppTrafficModel.read(stream).rows.isEmpty())
        assertEquals(MobileTinaAppTrafficModel.MAX_BYTES + 1, read)
    }
    @Test fun malformedDataAndZeroRowsAreHarmless() {
        assertTrue(MobileTinaAppTrafficModel.rows("broken").isEmpty())
        assertTrue(MobileTinaAppTrafficModel.decode("{}").rows.isEmpty())
        assertTrue(MobileTinaAppTrafficModel.rows("""[{"uid":1,"upload":-1,"download":0},{}]""").isEmpty())
        assertTrue(MobileTinaAppTrafficModel.rows(" ".repeat(MobileTinaAppTrafficModel.MAX_BYTES + 1)).isEmpty())
    }
}
