package com.shanshui.performance

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.ParameterizedType

/** 在实际 R8 Release 中检查 FPS 持久化 DTO 泛型，避免 Debug 假通过。 */
@RunWith(AndroidJUnit4::class)
class FpsPersistenceObfuscationTest {
    /** Application 初始化及页面加载使用真实宿主代码。 */
    @get:Rule
    val activityScenarioRule = ActivityScenarioRule(MainActivity::class.java)

    /** records 泛型必须指向真实聚合类型，Gson 才不会生成 LinkedTreeMap。 */
    @Test
    fun releaseKeepsPersistentRecordTypes() {
        // 必须从目标 APK 加载，不能用测试 APK 内未混淆的同名类替代。
        val targetLoader = MainActivity::class.java.classLoader
        // 当前快照与聚合项由模块消费者规则保留。
        val currentType = Class.forName("com.shanshui.performance.fps.FpsCurrentFile", false, targetLoader)
        val aggregateType = Class.forName("com.shanshui.performance.fps.FpsAggregateRecord", false, targetLoader)
        // 泛型声明在 R8 full mode 中也必须保留。
        val recordsType = currentType.getDeclaredField("records").genericType
        assertTrue(recordsType is ParameterizedType)
        assertEquals(aggregateType, (recordsType as ParameterizedType).actualTypeArguments.single())
        // 同时核验本地磁盘 JSON 依赖的字段名不会随构建变化。
        assertNotNull(aggregateType.getDeclaredField("eventId"))
        assertNotNull(aggregateType.getDeclaredField("processId"))
        val queuedType = Class.forName("com.shanshui.performance.fps.FpsQueuedRecord", false, targetLoader)
        assertNotNull(queuedType.getDeclaredField("event"))
        assertNotNull(queuedType.getDeclaredField("nextAttemptAtMillis"))
        // SDK 初始化与跨进程快照恢复另由设备重启及上报烟测核验。
    }
}
