package com.shanshui.performance.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 运行身份生成、主子进程隔离和 UUID v4 校验测试。 */
class RuntimeIdentityTest {
    /** 验证主进程沿用 sessionId 作为 processId。 */
    @Test
    fun mainProcessUsesSessionIdAsProcessId() {
        // 主进程测试使用的固定身份。
        val identity = RuntimeIdentityProvider.createForTesting(
            isMainProcess = true,
            uuidGenerator = {
                "11111111-1111-4111-8111-111111111111"
            },
        )

        assertEquals(identity.sessionId, identity.processId)
    }

    /** 验证子进程使用独立 processId，同时保留独立 sessionId。 */
    @Test
    fun childProcessUsesIndependentProcessId() {
        // 测试生成器的调用序号。
        var nextId = 0
        // 子进程依次生成的 sessionId 和 processId。
        val ids = listOf(
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
        )
        // 使用固定序列创建子进程身份。
        val identity = RuntimeIdentityProvider.createForTesting(
            isMainProcess = false,
            uuidGenerator = { ids[nextId++] },
        )

        assertNotEquals(identity.sessionId, identity.processId)
        assertEquals(ids[0], identity.sessionId)
        assertEquals(ids[1], identity.processId)
    }

    /** 验证运行身份拒绝非规范 UUID v4。 */
    @Test
    fun rejectsNonCanonicalUuidV4() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeIdentity(
                sessionId = "11111111-1111-4111-8111-111111111111",
                processId = "11111111-1111-4111-8111-11111111111z",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeIdentity(
                sessionId = "11111111-1111-4111-8111-111111111111",
                processId = "11111111-1111-1111-8111-111111111111",
            )
        }
    }
}
