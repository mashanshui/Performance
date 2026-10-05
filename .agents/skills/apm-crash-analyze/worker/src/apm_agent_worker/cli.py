"""宿主分析的可信任务命令；秘密仅由环境注入，不启动独立模型。"""

import argparse
import json
import os
from pathlib import Path
from uuid import UUID
from collections.abc import Sequence

from apm_agent_worker.platform import PlatformClient, WorkerError, base_url
from apm_agent_worker.source import SourceBlocked
from apm_agent_worker.state import RunState
from apm_agent_worker import host, repair


def prerequisites() -> dict[str, bool]:
    """只报告必要平台配置是否存在，不读取或打印秘密。"""
    return {"worker_credential_configured": bool(os.environ.get("APM_WORKER_CREDENTIAL", "").strip()),
            "platform_url_configured": bool(os.environ.get("APM_ANALYSIS_URL", "").strip())}


def main(argv: Sequence[str] | None = None) -> int:
    """准备、读取和提交均绑定同任务与私有恢复目录。"""
    # 不提供旧独立模型入口，旧报告保留历史展示。
    parser = argparse.ArgumentParser(description="宿主 Agent 当前代码分析和明确授权修复工具")
    # 动作必须由宿主明确选择。
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("doctor", help="只检查平台环境配置存在，不调用模型")
    # 各动作共用唯一规范任务 UUID 和状态身份。
    for name in ("prepare", "read", "search", "submit", "status", "stop", "apply", "revert"):
        command = commands.add_parser(name)
        command.add_argument("task_id", type=UUID)
        command.add_argument("--state-dir", type=Path, default=Path.home() / ".local/state/apm-analysis")
        if name == "prepare":
            command.add_argument("--project", type=Path, help="明确选择绝对项目路径；默认从宿主当前目录定位")
            command.add_argument("--repair", action="store_true", help="宿主已取得用户对本任务的明确修复授权")
        elif name == "apply":
            command.add_argument("--patch", type=Path, required=True, help="有限文本补丁 JSON，不包含凭据")
        elif name == "read":
            command.add_argument("--path", required=True)
            command.add_argument("--start", type=int, required=True)
            command.add_argument("--end", type=int, required=True)
        elif name == "search":
            command.add_argument("--query", default="")
            command.add_argument("--glob", default="*")
            command.add_argument("--limit", type=int, default=20)
        elif name == "submit":
            command.add_argument("--result", type=Path, help="结构化语义结果文件，恢复上传时无需提供")
            command.add_argument("--host", help="非敏感宿主名称，仅作自报标记")
    # argparse 不接收平台凭据或模型密钥参数。
    arguments = parser.parse_args(argv)
    if arguments.command == "doctor":
        checks = prerequisites()
        print(json.dumps(checks, ensure_ascii=False))
        return 0 if all(checks.values()) else 1
    # 生命周期资源必须在异常时释放，但保留恢复记录。
    platform = None
    state = None
    try:
        url = base_url(os.environ.get("APM_ANALYSIS_URL", ""))
        platform = PlatformClient(url, os.environ.get("APM_WORKER_CREDENTIAL", ""))
        task_id = str(arguments.task_id)
        state = RunState(arguments.state_dir, url, task_id)
        if arguments.command == "prepare":
            result = host.prepare(platform, state, task_id, arguments.project, arguments.repair)
            host.start_guardian(state, url, task_id)
        elif arguments.command == "apply":
            if arguments.patch.stat().st_size > repair.MAX_PATCH_BYTES:
                raise WorkerError("PATCH_TOO_LARGE")
            result = repair.apply(platform, state, arguments.patch.read_text(encoding="utf-8"))
        elif arguments.command == "revert":
            result = repair.revert(platform, state)
        elif arguments.command == "read":
            result = host.read(platform, state, arguments.path, arguments.start, arguments.end)
        elif arguments.command == "search":
            result = host.search(platform, state, arguments.query, arguments.glob, arguments.limit)
        elif arguments.command == "submit":
            if state.data.get("phase") == "HOST_RESULT_PENDING":
                result = host.upload(platform, state)
            else:
                if not arguments.result or arguments.result.stat().st_size > 1024 * 1024:
                    raise WorkerError("FORMAT_INVALID")
                result = host.submit(platform, state, arguments.result.read_text(encoding="utf-8"), arguments.host)
        elif arguments.command == "stop":
            result = host.stop(platform, state)
        else:
            if not state.data.get("runId"):
                raise WorkerError("ANALYSIS_NOT_READY")
            result = {"status": "observed", "run": platform.status(state.data["runId"]),
                      "repair": repair.facts(state) if state.data.get("projectRoot") else None}
        print(json.dumps(result, ensure_ascii=False))
        return 1 if result.get("status") in {"failed", "FAILED", "PARTIAL", "CONFLICT"} else 0
    except Exception as failure:
        # 确定失联或拒绝后关闭本任务材料，停止回执失败也保留待核验事实。
        if state is not None and state.data.get("phase") == "HOST_RUNNING" and (
                str(failure) in {"LEASE_EXPIRED", "CANCELLED", "NETWORK_ERROR", "WORKER_CREDENTIAL_INVALID"}
                or (arguments.command == "prepare" and not state.data.get("guardianStarted"))):
            try:
                host.stop(platform, state, str(failure) if str(failure) in {"LEASE_EXPIRED", "CANCELLED", "NETWORK_ERROR"} else "EXECUTOR_FAILED")
            except Exception:
                pass
        # 不回显原始异常、路径、响应正文或敏感配置。
        code = str(failure) if isinstance(failure, (WorkerError, SourceBlocked)) else "WORKER_EXECUTION_FAILED"
        failure_result = {"status": "failed", "code": code}  # 即使取消/中断，也分别反馈已发生的写入。
        if state is not None and state.data.get("projectRoot") and arguments.command in {"apply", "revert", "submit"}:
            try:
                failure_result["repair"] = repair.facts(state)
            except Exception:
                pass  # 日志未知时不补造成功或回显私有异常。
        print(json.dumps(failure_result, ensure_ascii=False))
        return 1
    finally:
        if state is not None:
            state.close()
        if platform is not None:
            platform.close()
