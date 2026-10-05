"""有限分析 Schema 和可信源码引用核验，不以模型自报代替文件检查。"""

import hashlib
import json
from pathlib import Path, PurePosixPath
from typing import Annotated, Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, ValidationError

from apm_agent_worker.platform import WorkerError
from apm_agent_worker.redaction import redact


# 所有未知项与建议均为有界普通文本，显示层仍需安全转义。
SmallText = Annotated[str, StringConstraints(min_length=1, max_length=1000)]


class StrictModel(BaseModel):
    """拒绝额外字段和隐式数字/布尔转换。"""
    model_config = ConfigDict(extra="forbid", strict=True)


class Candidate(StrictModel):
    """多个候选仍必须引用当前单事件证据。"""
    # 候选的有界标题。
    title: str = Field(min_length=1, max_length=500)
    # 推断和未知边界。
    reason: str = Field(min_length=1, max_length=4000)
    # 当前快照中的片段 ID。
    evidenceRefs: list[Annotated[str, StringConstraints(min_length=1, max_length=64)]] = Field(min_length=1, max_length=20)


class RequestedSource(StrictModel):
    """模型只能请求相对源码位置，不能自行填写已核验片段。"""
    # 固定快照内相对路径。
    path: str = Field(min_length=1, max_length=512)
    # 一起始行。
    startLine: int = Field(ge=1)
    # 包含的结束行，跨度另由文件核验校验。
    endLine: int = Field(ge=1)


class VerifiedCommand(StrictModel):
    """宿主报告的实际命令与退出码，工具负责限量脱敏。"""
    command: str = Field(min_length=1,max_length=1000)  # 已执行命令。
    exitCode: int  # 实际返回值，不能填 null 冒充执行。
    summary: str = Field(max_length=2000)  # 不接收完整原始日志。


class HostVerification(StrictModel):
    """输入仅含宿主命令事实，来源与材料一致性由工具生成。"""
    status: Literal['PASSED','FAILED','NOT_RUN']  # 命令总体状态。
    commands: list[VerifiedCommand] = Field(max_length=10)  # 有限命令。
    reason: str = Field(max_length=2000)  # 未执行或失败原因。


class ModelAnalysis(StrictModel):
    """宿主语义输出，实际材料、修改、执行事实由工具填充。"""
    # 固定 Schema 版本。
    schemaVersion: int = Field(strict=True, ge=3, le=3)
    # 必须属于当前任务的证据 UUID。
    evidenceId: UUID
    # 本次 prepare 自动生成的材料 UUID。
    snapshotId: UUID
    # 证据不足可以是正常业务结论。
    conclusion: Literal["ROOT_CAUSE_CANDIDATE", "INSUFFICIENT_EVIDENCE"]
    # 人可读摘要，不宣称修复成功。
    summary: str = Field(min_length=1, max_length=4000)
    # 根因候选最多五个。
    candidates: list[Candidate] = Field(max_length=5)
    # 待受信包装器核验的引用最多二十个。
    sourceRefs: list[RequestedSource] = Field(max_length=20)
    # 不能确定的事项。
    unknowns: list[SmallText] = Field(max_length=20)
    # 风险说明。
    risks: list[SmallText] = Field(max_length=20)
    # 未执行的修复建议。
    fixSuggestions: list[SmallText] = Field(max_length=20)
    # 未执行的验证步骤。
    validationSuggestions: list[SmallText] = Field(max_length=20)
    # 可选实际命令自报，不能提交 repair、execution 或计量事实。
    verification: HostVerification | None = None



def validated(text: str, evidence: dict[str, Any], source: Path, reads: list[dict], snapshot_id: str) -> dict[str, Any]:
    """严格核对当前证据、快照和实际读取范围，重建脱敏源码引用。"""
    try:
        result = ModelAnalysis.model_validate_json(text)  # 不剥 Markdown 或删非法字段。
    except ValidationError:
        raise WorkerError("FORMAT_INVALID") from None
    if str(result.evidenceId) != evidence["evidenceId"] or str(result.snapshotId) != snapshot_id:
        raise WorkerError("REFERENCE_INVALID")
    fragments = {item["id"] for item in evidence["fragments"]}  # 当前事件的固定片段。
    if any(not set(candidate.evidenceRefs) <= fragments for candidate in result.candidates):
        raise WorkerError("REFERENCE_INVALID")
    if (result.conclusion == "ROOT_CAUSE_CANDIDATE" and not result.candidates) or (
            result.conclusion == "INSUFFICIENT_EVIDENCE" and not result.unknowns):
        raise WorkerError("REFERENCE_INVALID")
    output = result.model_dump(mode="json")  # UUID 使用 JSON 字符串形式。
    references = []  # 工具重建的可信显示片段。
    for requested in result.sourceRefs:
        if not any(item["path"] == requested.path and item["startLine"] <= requested.startLine and item["endLine"] >= requested.endLine for item in reads):
            raise WorkerError("REFERENCE_INVALID")
        if requested.path.startswith("/") or "\\" in requested.path or ":" in requested.path or "\0" in requested.path \
                or any(part in {"", ".", "..", ".git"} for part in requested.path.split("/")):
            raise WorkerError("REFERENCE_INVALID")
        path = source / requested.path  # 路径只属于同一私有快照。
        try:
            if path.is_symlink() or not path.resolve(strict=True).is_relative_to(source.resolve()) or path.stat().st_size > 16 * 1024 * 1024:
                raise ValueError()
            lines = path.read_text(encoding="utf-8").splitlines()  # 保留行号，仅引用按 LF 拼接。
        except (OSError, ValueError, UnicodeError):
            raise WorkerError("REFERENCE_INVALID") from None
        if requested.endLine < requested.startLine or requested.endLine-requested.startLine >= 200 or requested.endLine > len(lines):
            raise WorkerError("REFERENCE_INVALID")
        snippet = redact("\n".join(lines[requested.startLine-1:requested.endLine]))  # 展示内容不能泄露已识别秘密。
        if len(snippet.encode()) > 8192:
            raise WorkerError("REFERENCE_INVALID")
        references.append({**requested.model_dump(), "snippet": snippet, "snippetSha256": hashlib.sha256(snippet.encode()).hexdigest()})
    output["sourceRefs"] = references
    return output
