"""当前与历史流程共用的受限临时材料清理。"""

import shutil
import tempfile
from pathlib import Path

from apm_agent_worker.platform import WorkerError
from apm_agent_worker.state import RunState


def remove_source(state: RunState) -> None:
    """只删除本工具记录的直接临时目录；调用方先阻断材料，旧容器流程另须确认环境停止。"""
    if not state.data.get("sourceDirectory"):
        return
    # 恢复记录不能将任意宿主路径作为删除目标。
    path = Path(state.data["sourceDirectory"]).resolve()
    if path.parent != Path(tempfile.gettempdir()).resolve() or not path.name.startswith("apm-analysis-source-"):
        raise WorkerError("STATE_UNSAFE")
    if path.exists():
        shutil.rmtree(path, ignore_errors=False)
    # 配置也只来自本工具直接临时目录，停止未知时调用方不会进入此清理步骤。
    if state.data.get("runtimeDirectory"):
        config = Path(state.data["runtimeDirectory"]).resolve()
        if config.parent != Path(tempfile.gettempdir()).resolve() or not config.name.startswith("apm-run-"):
            raise WorkerError("STATE_UNSAFE")
        if config.exists():
            shutil.rmtree(config)

