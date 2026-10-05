"""本地私有恢复记录：稳定请求与结果对账，模型环境不得挂载此目录。"""

import fcntl
import hashlib
import json
import os
import uuid
from pathlib import Path
from typing import Any

from apm_agent_worker.platform import WorkerError


class RunState:
    """单任务进程锁及原子恢复文件，不保存模型密钥或 Worker 凭据。"""

    def __init__(self, directory: Path, url: str, task_id: str) -> None:
        """要求当前用户独占目录，拒绝链接和其他用户记录。"""
        if directory.is_symlink():
            raise WorkerError("STATE_UNSAFE")
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        # 私有目录和模型源码快照完全分离。
        if directory.stat().st_uid != os.getuid():
            raise WorkerError("STATE_UNSAFE")
        directory.chmod(0o700)
        # 文件身份绑定平台入口与规范化任务 UUID，不能用任意路径输入。
        name = hashlib.sha256((url + ":" + str(uuid.UUID(task_id))).encode()).hexdigest()
        # 本任务恢复路径，不存储平台身份秘密。
        self.path = directory / (name + ".json")
        # 同一任务只有一个当前本地流程；并发命令在模型调用前拒绝。
        self.lock = os.open(directory / (name + ".lock"), os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        try:
            fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            os.close(self.lock)
            raise WorkerError("LOCAL_TASK_BUSY") from None
        # 只有用户保护的本地记录可以包含短期租约；模型密钥永不保存。
        self.data: dict[str, Any] = {}
        try:
            if self.path.is_symlink():
                raise WorkerError("STATE_UNSAFE")
            if self.path.exists():
                if self.path.is_symlink() or self.path.stat().st_uid != os.getuid() or self.path.stat().st_mode & 0o077:
                    raise WorkerError("STATE_UNSAFE")
                if self.path.stat().st_size > 2 * 1024 * 1024:
                    raise WorkerError("STATE_INVALID")
                self.data = json.loads(self.path.read_text(encoding="utf-8"))
                if not isinstance(self.data, dict):
                    raise WorkerError("STATE_INVALID")
        except (OSError, ValueError):
            self.close()
            raise WorkerError("STATE_INVALID") from None
        except WorkerError:
            self.close()
            raise

    def save(self, **changes: Any) -> None:
        """先原子落盘，再执行外部动作，网络未知结果不得触发重复模型调用。"""
        # 新材料清单另存；状态正文仍有硬上限，超限不能留下不可恢复记录。
        updated = {**self.data, **changes}
        payload = json.dumps(updated, ensure_ascii=False)
        if len(payload.encode("utf-8")) > 2 * 1024 * 1024:
            raise WorkerError("STATE_TOO_LARGE")
        # 临时文件只在当前私有目录创建，随机名不覆盖未知文件。
        temporary = self.path.with_suffix("." + uuid.uuid4().hex + ".tmp")
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as output:
                output.write(payload)
                output.flush()
                os.fsync(output.fileno())
            temporary.replace(self.path)
            self.data = updated
            # 同步父目录，减少响应已保存但重启丢失记录的窗口。
            directory = os.open(self.path.parent, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            temporary.unlink(missing_ok=True)

    def close(self) -> None:
        """退出当前本地进程锁；保留完成摘要以免同命令重新分析。"""
        fcntl.flock(self.lock, fcntl.LOCK_UN)
        os.close(self.lock)
