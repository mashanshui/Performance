package com.shanshui.performance.memory

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 内存原始值的单位转换和独立失败语义测试。 */
class MemorySamplerTest {
    @Test
    fun convertsPssFromKibAndKeepsZero() {
        val values = MemorySampler(
            pssReader = { 0L },
            vssReader = { 2L * 1024L },
            javaHeapReader = { 3L },
        ).sample()

        assertEquals(0L, values.pssBytes)
        assertEquals(2L * 1024L, values.vssBytes)
        assertEquals(3L, values.javaHeapUsedBytes)
    }

    @Test
    fun failedMetricDoesNotHideOtherMetrics() {
        val values = MemorySampler(
            pssReader = { error("pss unavailable") },
            vssReader = { null },
            javaHeapReader = { 42L },
        ).sample()

        assertNull(values.pssBytes)
        assertNull(values.vssBytes)
        assertEquals(42L, values.javaHeapUsedBytes)
    }

    @Test
    fun rejectsValuesOutsideProtocolRange() {
        val values = MemorySampler(
            pssReader = { MemorySampler.MAX_MEMORY_BYTES / MemorySampler.BYTES_PER_KIB + 1L },
            vssReader = { MemorySampler.MAX_MEMORY_BYTES + 1L },
            javaHeapReader = { MemorySampler.MAX_MEMORY_BYTES + 1L },
        ).sample()

        assertNull(values.pssBytes)
        assertNull(values.vssBytes)
        assertNull(values.javaHeapUsedBytes)
    }

    @Test
    fun parsesVmSizeInKiBAndRejectsMalformedInput() {
        val file = Files.createTempFile("memory-status", ".txt").toFile()
        try {
            file.writeText("Name:\ttest\nVmSize:\t1234 kB\nVmRSS: 10 kB\n")
            assertEquals(1_234L * 1024L, MemorySampler.readVssBytes(file))
            file.writeText("VmSize: bad kB\n")
            assertNull(MemorySampler.readVssBytes(file))
        } finally {
            file.delete()
        }
    }
}
