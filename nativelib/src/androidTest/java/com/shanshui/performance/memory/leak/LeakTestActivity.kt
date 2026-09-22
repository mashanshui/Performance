package com.shanshui.performance.memory.leak

import android.app.Activity

/** 仅供仪器测试启动和 finish，验证 Application 的真实 Activity 生命周期分发。 */
class LeakTestActivity : Activity()
