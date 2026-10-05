package com.shanshui.performance;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 固定输入契约，只验证专用样本；不改变主动崩溃按钮原意。 */
public class CurrentCodeRepairSampleTest {
    /** 正常数值保留。 */
    @Test public void keepsValidCount() { assertEquals(7, CurrentCodeRepairSample.parseCount("7")); }
    /** 损坏输入安全归零，不再冒出 JVM 崩溃。 */
    @Test public void malformedCountBecomesZero() { assertEquals(0, CurrentCodeRepairSample.parseCount("bad-current")); }
    /** 缺失输入采用同一契约。 */
    @Test public void missingCountBecomesZero() { assertEquals(0, CurrentCodeRepairSample.parseCount(null)); }
}
