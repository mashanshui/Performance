"""保守本地租约，暂停、恢复或时钟异常时停止当前执行。"""

import time
from datetime import datetime
from typing import Any
from collections.abc import Callable

from apm_agent_worker.platform import WorkerError


class LeaseGuard:
    """以请求开始单调时间计算截止，预留十五秒停止与网络余量。"""

    def __init__(self, clock: Callable[[], float] = time.monotonic,
                 wall_clock: Callable[[], float] = time.time) -> None:
        """测试可注入时钟；生产从不依赖本地墙钟判断服务器租约。"""
        # 单调时间判断截止。
        self.clock = clock
        # 墙钟仅检测 macOS 系统睡眠等时钟分歧，不作为续租依据。
        self.wall_clock = wall_clock
        # 没有有效回执时禁止模型操作。
        self.deadline = 0.0
        # 最近可信回执的双时钟锚点。
        self.anchor = clock()
        # 最近墙钟锚点。
        self.wall_anchor = wall_clock()

    def update(self, run: dict[str, Any], sent_at: float) -> float:
        """验证回执状态，并向出口提供保守剩余秒数。"""
        if run.get("state") != "RUNNING" or run.get("taskState") != "RUNNING":
            raise WorkerError("CANCELLED" if run.get("taskState") == "CANCELLING" else "LEASE_EXPIRED")
        try:
            # 三个时间均由同一数据库时钟产生。
            now = datetime.fromisoformat(run["serverNow"].replace("Z", "+00:00"))
            expires = datetime.fromisoformat(run["leaseExpiresAt"].replace("Z", "+00:00"))
            deadline = datetime.fromisoformat(run["deadlineAt"].replace("Z", "+00:00"))
            # 不超过九十秒，不因通信延迟而把截止向后移动。
            seconds = min(90.0, (expires-now).total_seconds(), (deadline-now).total_seconds()) - 15
        except (ValueError, KeyError, TypeError):
            raise WorkerError("LEASE_EXPIRED") from None
        if self.deadline > 0 and self.clock() >= self.deadline:
            raise WorkerError("LEASE_EXPIRED")
        self.deadline = sent_at + seconds
        # 更新前先检测距离上一次回执的系统暂停，不能借新回执复活旧执行。
        self.check_clock()
        self.anchor = self.clock()
        self.wall_anchor = self.wall_clock()
        return self.remaining()

    def check_clock(self) -> None:
        """墙钟与单调钟分歧超过五秒，当前执行必须停止后重新分配。"""
        if abs((self.wall_clock()-self.wall_anchor)-(self.clock()-self.anchor)) > 5:
            raise WorkerError("LEASE_EXPIRED")

    def remaining(self) -> float:
        """每次派发前验证，没有当前租约不允许发模型或工具操作。"""
        self.check_clock()
        # 使用保守时间，不把过期负数压成可执行的最小正值。
        seconds = self.deadline - self.clock()
        if seconds <= 0:
            raise WorkerError("LEASE_EXPIRED")
        return seconds
