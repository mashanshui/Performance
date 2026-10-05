package com.shanshui.performance;

/** 专用当前代码修复验收样本；不参与 Android 界面或 SDK 运行。 */
public final class CurrentCodeRepairSample {
    /** 保留验收前用户已有的未提交内容，补丁不得覆盖。 */
    public static final String USER_NOTE = "已有未提交标记，必须保留";

    /** 损坏数量应按零处理；初始缺陷供受控宿主修复验收。 */
    public static int parseCount(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            // 损坏或缺失的数量统一按零处理，正常数量保留解析结果。
            return 0;
        }
    }
}
