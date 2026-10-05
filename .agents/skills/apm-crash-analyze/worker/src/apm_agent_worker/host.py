"""宿主只读材料与任务控制；不调用模型，不把工具关闭当宿主停止。"""

import fnmatch
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from uuid import uuid4

from pydantic import ValidationError

from apm_agent_worker.analysis import ModelAnalysis, validated
from apm_agent_worker.lease import LeaseGuard
from apm_agent_worker.platform import PlatformClient, WorkerError, base_url
from apm_agent_worker.source import project_root, snapshot
from apm_agent_worker.state import RunState
from apm_agent_worker.material import remove_source
from apm_agent_worker import repair

# 只约束本任务材料交付，不能冒充宿主模型输入预算。
MATERIAL_LIMIT = 512 * 1024


HostAnalysis = ModelAnalysis  # 唯一当前语义协议，不接受旧 commitSha 输入。


def delivered(state: RunState, value: dict) -> dict:
    """材料实际交付前计量，不静默截断过大数据。"""
    # JSON 编码是宿主实际接收的文本边界。
    size = len(json.dumps(value, ensure_ascii=False).encode())
    # 累计量包含证据、搜索与读取返回。
    total = state.data.get("deliveredBytes", 0) + size
    if total > MATERIAL_LIMIT:
        raise WorkerError("INPUT_LIMIT")
    state.save(deliveredBytes=total)
    return value


def active(platform: PlatformClient, state: RunState, watch: bool = True, guard: LeaseGuard | None = None) -> dict:
    """所有读写材料动作重新核验租约，后台看护消失也不能离线继续。"""
    if state.data.get("phase") != "HOST_RUNNING" or not state.data.get("lease"):
        raise WorkerError("ANALYSIS_NOT_READY")
    # 前台材料动作不能代替已失联的后台看护继续运行。
    if watch:
        try:
            # 双时钟分歧防止系统暂停后继续使用原材料身份。
            elapsed = time.monotonic() - state.data["guardianSeen"]
            wall_elapsed = time.time() - state.data["guardianWall"]
            if not state.data.get("guardianStarted") or elapsed < 0 or elapsed > 45 or abs(wall_elapsed - elapsed) > 5:
                raise ProcessLookupError()
            os.kill(state.data["guardianPid"], 0)
        except (OSError, KeyError):
            raise WorkerError("LEASE_EXPIRED") from None
    # 请求开始时刻用于扣除网络延迟。
    sent = time.monotonic()
    # 每次续租都由服务端给出状态与数据库时间。
    run = platform.heartbeat(state.data["runId"], state.data["lease"])
    # 保守校验取消、到期以及本次请求耗时。
    guard = guard if guard is not None else LeaseGuard()
    guard.update(run, sent)
    if run.get("snapshotId") != state.data.get("snapshotId") or run.get("runId") != state.data.get("runId"):
        raise WorkerError("REFERENCE_INVALID")
    state.save(lastRun=run)
    return run


def source_file(state: RunState, relative: str, original: bool = False) -> Path:
    """拒绝目录穿越和链接，宿主只指定本次快照相对文件。"""
    if not relative or relative.startswith("/") or "\\" in relative or ":" in relative or "\0" in relative \
            or any(part in {"", ".", "..", ".git"} for part in relative.split("/")):
        raise WorkerError("REFERENCE_INVALID")
    # 临时根来自受保护状态，不能接受宿主路径覆盖。
    root = Path(state.data["sourceDirectory"]) / ("original" if original else "source")
    # 每个路径组件均不能变成链接，即使目标仍位于目录内。
    path = root
    for part in relative.split("/"):
        path = path / part
        if path.is_symlink():
            raise WorkerError("REFERENCE_INVALID")
    try:
        if not path.is_file() or not path.resolve().is_relative_to(root.resolve()) or path.stat().st_size > 16 * 1024 * 1024:
            raise ValueError()
    except (OSError, ValueError):
        raise WorkerError("REFERENCE_INVALID") from None
    return path


def read(platform: PlatformClient, state: RunState, path: str, start: int, end: int) -> dict:
    """交付有界源码行并保存实际读到的范围，搜索不代替此证明。"""
    active(platform, state)
    if start < 1 or end < start or end - start >= 200:
        raise WorkerError("REFERENCE_INVALID")
    try:
        # 只读脱敏视图，不把私有原文返回宿主。
        lines = source_file(state, path).read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError):
        raise WorkerError("SOURCE_MISSING") from None
    if end > len(lines):
        raise WorkerError("REFERENCE_INVALID")
    # 返回真实范围，不能把未返回行计为读取。
    snippet = "\n".join(lines[start - 1:end])
    if len(snippet.encode()) > 8192:
        raise WorkerError("INPUT_LIMIT")
    active(platform, state)
    # 交付前再次检查，失败不新增读取事实。
    result = delivered(state, {"path": path, "startLine": start, "endLine": end, "snippet": snippet})
    # 已完成读取范围用于后续严格引用核验。
    reads = state.data.get("reads", [])
    state.save(reads=[*reads, {"path": path, "startLine": start, "endLine": end}])
    return result


def search(platform: PlatformClient, state: RunState, query: str, pattern: str, limit: int) -> dict:
    """字面量搜索和文件名匹配，不执行正则或项目命令。"""
    active(platform, state)
    if len(query) > 200 or not pattern or len(pattern) > 200 or pattern.startswith("/") or ".." in pattern or "\\" in pattern \
            or limit < 1 or limit > 100:
        raise WorkerError("REFERENCE_INVALID")
    # 搜索根永远是脱敏快照。
    root = Path(state.data["sourceDirectory"]) / "source"
    # 单次扫描最多五秒，输出命中数有界。
    deadline = time.monotonic() + 5
    # 超过数量明确返回 more，搜索结果本身不是完整定位证明。
    matches = []
    # 文件树总数在快照阶段已经限制；不保存整个结果集合。
    for file in root.rglob("*"):
        if time.monotonic() > deadline:
            raise WorkerError("TIME_LIMIT")
        if not file.is_file():
            continue
        # 使用快照相对路径匹配，便于按堆栈文件名定位。
        relative = file.relative_to(root).as_posix()
        if not fnmatch.fnmatchcase(relative, pattern):
            continue
        source_file(state, relative)
        try:
            # 空查询只列文件，便于找堆栈中的源码名称。
            lines = file.read_text(encoding="utf-8").splitlines() if query else [""]
        except (UnicodeError, OSError):
            continue
        for number, line in enumerate(lines, 1):
            if time.monotonic() > deadline:
                raise WorkerError("TIME_LIMIT")
            if not query or query in line:
                if len(matches) == limit:
                    active(platform, state)
                    return delivered(state, {"matches": matches, "more": True})
                matches.append({"path": relative, "line": number, "text": line[:500]})
    active(platform, state)
    return delivered(state, {"matches": matches, "more": False})


def close_material(state: RunState) -> None:
    """只关闭本工具材料访问，不宣称已停止宿主模型请求。"""
    remove_source(state)
    # 清理成功之后才能声明材料关闭；失败保留原状态阻断操作。
    state.save(localToolsStopped=True)


def upload(platform: PlatformClient, state: RunState) -> dict:
    """使用原结果原摘要对账，绝不要求宿主再次分析。"""
    # 恢复时仍先完成材料清理，不能把曾失败的清理当成成功。
    close_material(state)
    # 最多两次传输尝试，不创建新的 Run。
    for _ in range(2):
        try:
            # 模型停止未知，Worker 无权限自行确认。
            run = platform.request("POST", f'/api/worker/v1/tasks/runs/{state.data["runId"]}/complete', body={
                "lease": state.data["lease"], "resultJson": state.data["resultJson"],
                "resultSha256": state.data["resultSha256"], "stopConfirmed": False, "localToolsStopped": True}, limit=65536)
            state.save(phase="HOST_DONE", lease=None, resultJson=None, lastRun=run)
            return {"status": "succeeded", "taskId": state.data["taskId"], "runId": run["runId"],
                    "conclusion": state.data["conclusion"], "usage": state.data["usage"],
                    "repair": state.data.get("repairFacts"), "verification": state.data.get("verificationFacts"),
                    "localToolsStopped": True, "hostStopState": run["hostStopState"], "stopConfirmed": run["stopConfirmed"]}
        except WorkerError as failure:
            if str(failure) != "NETWORK_ERROR":
                # 明确拒绝不是未知完成，失败恢复仍不新推理。
                try:
                    stop(platform, state, "LEASE_EXPIRED" if str(failure) in {"LEASE_EXPIRED", "ANALYSIS_LEASE_INVALID"} else "EXECUTOR_FAILED")
                except Exception:
                    pass
                raise
    raise WorkerError("RESULT_PENDING")


def submit(platform: PlatformClient, state: RunState, text: str, host: str | None = None) -> dict:
    """严格结果与实际读取范围核验，执行来源只声明可观察事实。"""
    if state.data.get("phase") == "HOST_RESULT_PENDING":
        return upload(platform, state)
    active(platform, state)
    if len(text.encode()) > 1024 * 1024 or (host is not None and (not host.strip() or len(host) > 100)):
        raise WorkerError("FORMAT_INVALID")
    try:
        # 输出对象不能夹带执行信息、用量或租约。
        result = HostAnalysis.model_validate_json(text)
    except ValidationError:
        raise WorkerError("FORMAT_INVALID") from None
    if result.schemaVersion != 3:
        raise WorkerError("FORMAT_INVALID")
    # 每个引用必须完全被本 Run 的实际返回范围覆盖。
    for ref in result.sourceRefs:
        if not any(item["path"] == ref.path and item["startLine"] <= ref.startLine and item["endLine"] >= ref.endLine
                   for item in state.data.get("reads", [])):
            raise WorkerError("REFERENCE_INVALID")
        source_file(state, ref.path, original=True)
    # 只核对本 Run 的实际读取和当前快照，宿主不能伪造片段与日志。
    output = validated(text, state.data["evidence"], Path(state.data["sourceDirectory"]) / "original",
                       state.data.get("reads", []), state.data["snapshotId"])
    output["execution"] = {"mode": "HOST_AGENT", "host": host, "hostVersion": None, "toolVersion": "0.3.0",
                           "providerId": None, "modelId": None, "metadataSource": "HOST_REPORTED" if host else "UNKNOWN"}
    # 修改事实来自工具写前日志；宿主只能报告已执行的有限验证命令。
    output["repair"] = repair.facts(state)
    output["verification"] = repair.verification(state, result.verification)
    # 无可信宿主计量通道，首版计数全部未知。
    output["usage"] = dict.fromkeys(("inputTokens", "outputTokens", "cacheReadTokens", "cacheWriteTokens", "cost"))
    active(platform, state)
    # 完整结果先落盘，响应丢失时复用相同字节。
    raw = json.dumps(output, ensure_ascii=False, separators=(",", ":"))
    state.save(phase="HOST_RESULT_PENDING", resultJson=raw, resultSha256=hashlib.sha256(raw.encode()).hexdigest(),
               conclusion=output["conclusion"], usage=output["usage"], repairFacts=output["repair"], verificationFacts=output["verification"])
    close_material(state)
    return upload(platform, state)


def stop(platform: PlatformClient, state: RunState, code: str = "CANCELLED") -> dict:
    """关闭本地材料并报告宿主未知，不能解锁重试门禁。"""
    if not state.data.get("runId"):
        raise WorkerError("ANALYSIS_NOT_READY")
    if state.data.get("phase") == "HOST_DONE":
        return {"status": "succeeded", "run": platform.status(state.data["runId"])}
    # 先落盘阻止材料继续交付，再尝试失败回执。
    state.save(phase="HOST_STOP_PENDING", failureCode=code)
    close_material(state)
    if not state.data.get("lease"):
        # 初次领取回执丢失且服务端已结束时无法补造原租约。
        raise WorkerError("STOP_UNCONFIRMED")
    # 到期仍可以用原身份确认本地工具停止；整体仍未知。
    run = platform.request("POST", f'/api/worker/v1/tasks/runs/{state.data["runId"]}/stopped',
                           body={"lease": state.data["lease"], "errorCode": code}, limit=65536)
    state.save(phase="HOST_STOPPED", lastRun=run)
    return {"status": "failed", "code": code, "taskId": state.data["taskId"], "runId": run["runId"],
            "localToolsStopped": True, "hostStopState": run["hostStopState"], "stopConfirmed": run["stopConfirmed"]}


def prepare(platform: PlatformClient, state: RunState, task_id: str, project: Path | None = None, repair: bool = False) -> dict:
    """准备固定输入并领取，重复命令不会重复分配或再次模型推理。"""
    # 已有恢复记录必须属于本工具协议，不能执行旧容器恢复代码。
    phase = state.data.get("phase")
    if phase is not None and (repair != state.data.get("repairAuthorized", False) or (project is not None and str(project_root(project)) != state.data.get("projectRoot"))):
        raise WorkerError("CONFIG_MISMATCH")
    if phase == "HOST_RESULT_PENDING":
        return upload(platform, state)
    if phase == "HOST_DONE":
        # 管理员可能已更新停止审计，不能返回过期的本地事实。
        state.save(lastRun=platform.status(state.data["runId"]))
        return {"status": "succeeded", "taskId": task_id, "runId": state.data["runId"], "conclusion": state.data["conclusion"],
                "usage": state.data["usage"], "recovered": True, "hostStopState": state.data["lastRun"]["hostStopState"]}
    if phase == "HOST_RUNNING":
        active(platform, state)
        return delivered(state, {"status": "prepared", "taskId": task_id, "runId": state.data["runId"], "evidence": state.data["evidence"],
                                 "sourceExclusions": state.data["sourceExclusions"], "snapshotId": state.data["snapshotId"],
                                 "repairAuthorized": state.data["repairAuthorized"]})
    if phase not in {None, "HOST_CLAIM_REQUEST"}:
        raise WorkerError("STOP_UNCONFIRMED")
    # 平台只提供应用任务，不能给宿主指定本机项目或修改授权。
    inspection = platform.inspect(task_id)
    if phase is None:
        if inspection["task"]["state"] != "READY":
            raise WorkerError("ANALYSIS_NOT_READY")
        # 项目从宿主当前目录或用户明确位置定位，不从平台证据推测。
        repo = project_root(project)
        # 原始视图只留在私有临时根，宿主工具不返回此位置。
        directory = Path(tempfile.mkdtemp(prefix="apm-analysis-source-"))
        try:
            materials = snapshot(repo, directory / "source", directory / "original", state.path.parent.resolve())
            # 文件清单单独保存在私有材料目录，五万文件不挤入有界 RunState。
            manifest_file = directory / "manifest.json"
            manifest_file.write_text(json.dumps(materials["manifest"], ensure_ascii=False), encoding="utf-8")
            manifest_file.chmod(0o600)
            state.save(phase="HOST_CLAIM_REQUEST", taskId=task_id, requestId=str(uuid4()), sourceDirectory=str(directory),
                       sourceExclusions=materials["sourceExclusions"],
                       projectRoot=str(repo), snapshotId=str(uuid4()), repairAuthorized=repair, deliveredBytes=0, reads=[])
        except Exception:
            state.save(sourceDirectory=str(directory))
            remove_source(state)
            raise
    # 未知领取响应继续使用原请求 ID，不能另建分配。
    claimed = platform.claim(task_id, state.data["requestId"], state.data["snapshotId"])
    if claimed["run"]["taskId"] != task_id or claimed["run"].get("snapshotId") != state.data["snapshotId"]:
        state.save(phase="HOST_STOP_PENDING")
        close_material(state)
        raise WorkerError("REFERENCE_INVALID")
    if not claimed.get("leaseToken"):
        # 领取响应未知期间 Run 已结束：不能再推理，材料清理后交管理员核验。
        state.save(phase="HOST_STOP_PENDING", runId=claimed["run"]["runId"], lease=None, lastRun=claimed["run"])
        close_material(state)
        raise WorkerError("STOP_UNCONFIRMED")
    # 活动分配的短期秘密仅写入私有记录。
    state.save(runId=claimed["run"]["runId"], lease={"generation": claimed["run"]["leaseGeneration"], "token": claimed["leaseToken"]},
               phase="HOST_RUNNING")
    try:
        active(platform, state, watch=False)
        # 冻结原始字节摘要在解析之前校验。
        raw = platform.evidence(state.data["runId"], state.data["lease"])
        if hashlib.sha256(raw).hexdigest() != inspection["task"]["evidenceSha256"]:
            raise WorkerError("REFERENCE_INVALID")
        evidence = json.loads(raw)
        if evidence.get("schemaVersion") != 2 or evidence["evidenceId"] != inspection["task"]["evidenceId"] \
                or evidence["event"]["eventId"] != inspection["task"]["eventId"] or evidence["event"]["appId"] != inspection["task"]["appId"]:
            raise WorkerError("REFERENCE_INVALID")
        state.save(evidence=evidence)
        # 获取证据之后也核验取消及期限，不将过期网络回执交给宿主。
        active(platform, state, watch=False)
        return delivered(state, {"status": "prepared", "taskId": task_id, "runId": state.data["runId"],
                                 "evidence": evidence, "sourceExclusions": state.data["sourceExclusions"], "snapshotId": state.data["snapshotId"],
                                 "repairAuthorized": state.data["repairAuthorized"]})
    except Exception as failure:
        try:
            stop(platform, state, str(failure) if str(failure) in {"INPUT_LIMIT", "REFERENCE_INVALID", "LEASE_EXPIRED", "CANCELLED"} else "EXECUTOR_FAILED")
        except Exception:
            pass
        raise


def start_guardian(state: RunState, url: str, task_id: str) -> None:
    """仅启动受信 Python 模块；不给看护 DeepSeek 或其他无关秘密。"""
    if state.data.get("guardianStarted") or state.data.get("phase") != "HOST_RUNNING":
        return
    # 环境白名单避免把宿主全部凭据复制给后台进程。
    environment = {key: value for key, value in os.environ.items() if key in {"PATH", "TMPDIR", "LANG", "APM_WORKER_CREDENTIAL"}}
    environment["APM_ANALYSIS_URL"] = url
    # 参数只有任务 UUID 和私有目录，不包含 Token 或租约。
    process = subprocess.Popen([sys.executable, "-m", "apm_agent_worker.host", task_id, str(state.path.parent)],
                               stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                               start_new_session=True, env=environment)
    state.save(guardianStarted=True, guardianPid=process.pid, guardianSeen=time.monotonic(), guardianWall=time.time())


def guardian(task_id: str, directory: Path) -> None:
    """有限寿命看护，独立于宿主工具调用维护取消和期限。"""
    # 地址和凭据只从受信启动环境接收。
    url = base_url(os.environ["APM_ANALYSIS_URL"])
    platform = PlatformClient(url, os.environ.get("APM_WORKER_CREDENTIAL", ""))
    # 无论配置或状态如何，进程总寿命都受限。
    deadline = time.monotonic() + 660
    # 正常心跳间隔二十秒，终态每两秒内观察到并退出。
    next_heartbeat = 0.0
    # 保留同一双时钟锚点，系统暂停不能通过新回执复活旧看护。
    lease_guard = LeaseGuard()
    try:
        while time.monotonic() < deadline:
            try:
                # 只短暂锁状态；遇到前台命令持锁不启动第二任务。
                state = RunState(directory, url, task_id)
            except WorkerError as failure:
                if str(failure) != "LOCAL_TASK_BUSY":
                    return
                time.sleep(1)
                continue
            try:
                if state.data.get("phase") != "HOST_RUNNING":
                    return
                if time.monotonic() >= next_heartbeat:
                    try:
                        active(platform, state, watch=False, guard=lease_guard)
                        state.save(guardianSeen=time.monotonic(), guardianWall=time.time())
                        next_heartbeat = time.monotonic() + 20
                    except Exception as failure:
                        try:
                            stop(platform, state, str(failure) if str(failure) in {"LEASE_EXPIRED", "CANCELLED", "TIME_LIMIT"} else "NETWORK_ERROR")
                        except Exception:
                            pass
                        return
            finally:
                state.close()
            time.sleep(2)
        # 看护达到自己的硬期限时仍尝试关闭材料，不能静默留下活动视图。
        try:
            state = RunState(directory, url, task_id)
        except WorkerError:
            # 前台持锁时，由每次材料动作的服务端到期校验继续阻断访问。
            return
        try:
            if state.data.get("phase") == "HOST_RUNNING":
                stop(platform, state, "TIME_LIMIT")
        finally:
            state.close()
    finally:
        platform.close()


if __name__ == "__main__":
    guardian(sys.argv[1], Path(sys.argv[2]))
