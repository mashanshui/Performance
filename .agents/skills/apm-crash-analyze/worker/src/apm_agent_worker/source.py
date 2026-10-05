"""从当前 Git 工作区采集有界快照，保护未提交内容和暂存区。"""

import os
import hashlib
import stat
import subprocess
import threading
import time
import unicodedata
from pathlib import Path, PurePosixPath
from apm_agent_worker.redaction import redact


class SourceBlocked(ValueError):
    """源码前提不满足，错误仅包含稳定码。"""


def git(repository: Path, arguments: list[str], limit: int = 4 * 1024 * 1024, deadline: float | None = None) -> bytes:
    """仅运行可信只读 Git 子命令，禁用用户全局配置、钩子和交互凭据。"""
    remaining = 30 if deadline is None else min(30, deadline - time.monotonic())  # 每条命令复用整体剩余预算。
    if remaining <= 0:
        raise SourceBlocked("SOURCE_READ_TIMEOUT")
    # Git 不获得模型和平台凭据，不通过 shell 拼接命令。
    environment = {key: value for key, value in os.environ.items()
                   if key in {"PATH", "SYSTEMROOT", "TMPDIR", "LANG"}}
    environment.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1",
                       GIT_TERMINAL_PROMPT="0", GIT_OPTIONAL_LOCKS="0")
    # 所有 caller 参数在本模块固定；不执行项目脚本、过滤器或网络 fetch。
    with subprocess.Popen(["git", "--no-optional-locks", "-c", "core.hooksPath=" + os.devnull,
                           "-c", "core.fsmonitor=false", "-C", str(repository), *arguments],
                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=environment) as process:
        # 读取期间也实施期限，不能只为读取结束后的 wait 设置超时。
        expired = threading.Event()
        # 子进程超时后关闭管道，防止阻塞在 stdout.read。
        def stop() -> None:
            """独立终止可信 Git 读取，不留下后台进程。"""
            expired.set()
            if process.poll() is None:
                process.kill()
        # 每条只读命令最多三十秒。
        watchdog = threading.Timer(remaining, stop)
        watchdog.start()
        try:
            assert process.stdout is not None
            # 只缓存最大 limit+1 个字节，不使用无界 communicate。
            output = process.stdout.read(limit + 1)
            if len(output) > limit:
                raise SourceBlocked("SOURCE_TOO_LARGE")
            # watchdog 覆盖读取和退出两个阶段。
            code = process.wait()
            if expired.is_set():
                raise SourceBlocked("SOURCE_READ_TIMEOUT")
            if code:
                raise SourceBlocked("SOURCE_MISSING")
            return output
        finally:
            watchdog.cancel()
            if process.poll() is None:
                process.kill()
                process.wait()


# 材料边界沿用原读取上限，修复入口复用同一排除规则。
MAX_FILES = 50000
MAX_FILE_BYTES = 16 * 1024 * 1024
MAX_TOTAL_BYTES = 256 * 1024 * 1024
# 显式排除工具自身、构建环境和秘密；不能依赖用户恰好写了忽略规则。
EXCLUDED_PARTS = {".git", ".agents", ".codex", ".venv", "venv", "__pycache__", "node_modules", "build", "dist", "target", ".gradle", ".idea", "agent-skills", "agent-worker"}
# 不允许修改或外发签名与配置秘密文件。
SECRET_SUFFIXES = {".pem", ".key", ".p12", ".pfx", ".jks", ".keystore"}


def relative_path(value: str) -> PurePosixPath:
    """相对路径白名单，不解引用链接或规范化危险输入。"""
    if not value or value.startswith("/") or "\\" in value or ":" in value or "\0" in value \
            or any(part in {"", ".", ".."} for part in value.split("/")):
        raise SourceBlocked("SOURCE_PATH_INVALID")
    return PurePosixPath(value)


def excluded_path(value: str) -> bool:
    """源码读写共享排除规则，不能借新建文件绕过秘密边界。"""
    path = relative_path(value)  # 已核验的普通相对路径。
    return any(part in EXCLUDED_PARTS for part in path.parts) or path.suffix.lower() in SECRET_SUFFIXES \
        or path.name.startswith(".env") or path.name.lower() in {"local.properties", "credentials.json", "repositories.json"}


def project_root(project: Path | None = None) -> Path:
    """默认当前目录，显式项目必须是绝对路径；不读取提交或远端。"""
    selected = Path.cwd() if project is None else project  # 宿主明确选定的起点。
    if not selected.is_absolute():
        raise SourceBlocked("PROJECT_PATH_INVALID")
    try:
        if not selected.is_dir():
            raise SourceBlocked("PROJECT_NOT_FOUND")
        root = Path(git(selected, ["rev-parse", "--show-toplevel"]).decode("utf-8").strip())  # Git 仅定位根目录。
        if root.is_symlink() or not root.is_dir():
            raise SourceBlocked("SOURCE_PATH_INVALID")
        return root
    except (OSError, UnicodeError, SourceBlocked) as failure:
        if isinstance(failure, SourceBlocked) and str(failure) != "SOURCE_MISSING":
            raise
        raise SourceBlocked("PROJECT_NOT_FOUND") from None


def file_paths(project: Path, private: Path | None = None, deadline: float | None = None) -> tuple[list[str], list[str]]:
    """列举磁盘当前材料，过滤忽略文件，不访问 HEAD 或运行过滤器。"""
    entries = git(project, ["ls-files", "--stage", "-z"], deadline=deadline).split(b"\0")  # 索引仅用于拒绝未支持的子模块。
    for entry in entries:
        if entry and entry.split(b" ", 1)[0] == b"160000":
            raise SourceBlocked("SUBMODULE_UNSUPPORTED")
    listed = git(project, ["ls-files", "--cached", "--others", "--exclude-standard", "-z"], deadline=deadline).split(b"\0")  # 包括未提交及未忽略新文件。
    ignored = set(git(project, ["ls-files", "--cached", "--ignored", "--exclude-standard", "-z"], deadline=deadline).split(b"\0"))  # 也尊重已跟踪文件的忽略规则。
    names: set[str] = set()  # 检查大小写或 Unicode 名称碰撞。
    files: set[str] = set()  # Git 合并列表可能重复，不能重复采集。
    exclusions: set[str] = set()  # 仅返回相对路径，不泄露私有根。
    for raw in listed:
        if not raw:
            continue
        try:
            name = raw.decode("utf-8")  # 不可解码的路径拒绝，避免错位引用。
        except UnicodeError:
            raise SourceBlocked("SOURCE_PATH_INVALID") from None
        relative_path(name)
        if raw in ignored or excluded_path(name) or (private is not None and (project / name).is_relative_to(private)):
            exclusions.add(name)
            continue
        if name in files:
            continue
        normalized = unicodedata.normalize("NFC", name).casefold()  # macOS 常见大小写不敏感文件系统。
        if normalized in names:
            raise SourceBlocked("SOURCE_PATH_COLLISION")
        names.add(normalized)
        files.add(name)
        if len(files) > MAX_FILES:
            raise SourceBlocked("SOURCE_TOO_LARGE")
    ordered = sorted(files)  # 分批检查属性，既拒绝已展开 LFS，也不执行过滤器。
    for offset in range(0, len(ordered), 100):
        attributes = git(project, ["check-attr", "-z", "filter", "--", *ordered[offset:offset+100]], deadline=deadline).split(b"\0")
        if any(attributes[index] == b"lfs" for index in range(2, len(attributes), 3)):
            raise SourceBlocked("LFS_UNSUPPORTED")
    return ordered, sorted(exclusions)


def read_current(project: Path, name: str, missing_ok: bool = False) -> tuple[bytes, int] | None:
    """逐组件 openat 禁止链接，读取期间核验身份与内容变动。"""
    relative = relative_path(name)  # 调用方不得提交绝对路径。
    descriptor = None  # 当前目录描述符，始终关闭。
    file_descriptor = None  # 只读文件描述符，始终关闭。
    try:
        descriptor = os.open(project, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        for part in relative.parts[:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor)  # 不受路径链接替换影响。
            os.close(descriptor)
            descriptor = child
        file_descriptor = os.open(relative.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=descriptor)
        before = os.fstat(file_descriptor)  # 同一打开文件的读取前身份。
        if not stat.S_ISREG(before.st_mode):
            raise SourceBlocked("SOURCE_ENTRY_UNSUPPORTED")
        if before.st_size > MAX_FILE_BYTES:
            raise SourceBlocked("SOURCE_TOO_LARGE")
        with os.fdopen(file_descriptor, "rb") as stream:
            file_descriptor = None
            content = stream.read(MAX_FILE_BYTES + 1)  # 有界读取，即使文件不断增长。
            after = os.fstat(stream.fileno())  # 读取后的真实文件状态。
        if len(content) > MAX_FILE_BYTES:
            raise SourceBlocked("SOURCE_TOO_LARGE")
        if (before.st_ino,before.st_size,before.st_mtime_ns,before.st_ctime_ns) != (after.st_ino,after.st_size,after.st_mtime_ns,after.st_ctime_ns):
            raise SourceBlocked("WORKSPACE_CHANGED")
        return content, stat.S_IMODE(after.st_mode)
    except FileNotFoundError:
        if missing_ok:
            return None
        raise SourceBlocked("WORKSPACE_CHANGED") from None
    except OSError:
        raise SourceBlocked("SOURCE_PATH_INVALID") from None
    finally:
        if file_descriptor is not None:
            os.close(file_descriptor)
        if descriptor is not None:
            os.close(descriptor)


def secret_content(content: bytes) -> bool:
    """识别签名与私钥，即使扩展名被改变也不得送出。"""
    return content.startswith((b"\xfe\xed\xfe\xed", b"\xce\xce\xce\xce")) or b"PRIVATE KEY-----" in content \
        or (content.startswith(b"\x30") and bytes.fromhex("06092a864886f70d010701") in content)


def snapshot(project: Path, destination: Path, original: Path, private: Path | None = None) -> dict:
    """复制当前磁盘内容并二次核验清单和字节；失败不发布部分快照。"""
    deadline = time.monotonic() + 120  # 从准备开始覆盖全部 Git 查询和文件采集。
    for directory in (destination, original):
        if directory.is_symlink() or (directory.exists() and any(directory.iterdir())):
            raise SourceBlocked("SNAPSHOT_NOT_EMPTY")
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    initial, exclusions = file_paths(project, private, deadline)  # 初次材料清单，已排除运行与秘密目录。
    total = 0  # 累计真实字节，不仅统计输出视图。
    hashes: dict[str, str | None] = {}  # 包含删除或排除内容状态，供采集一致性核验。
    manifest: dict[str, dict] = {}  # 私有可信摘要、原权限，宿主不能伪造。
    for name in initial:
        if time.monotonic() > deadline:
            raise SourceBlocked("SOURCE_READ_TIMEOUT")
        entry = read_current(project, name, missing_ok=True)  # 未提交删除不重新导出旧文件。
        if entry is None:
            hashes[name] = None
            continue
        content, mode = entry  # 当前工作区真实字节与权限。
        digest = hashlib.sha256(content).hexdigest()  # 不依赖 Git 对象摘要。
        hashes[name] = digest
        total += len(content)
        if total > MAX_TOTAL_BYTES:
            raise SourceBlocked("SOURCE_TOO_LARGE")
        if content.startswith(b"version https://git-lfs.github.com/spec/v1\n"):
            raise SourceBlocked("LFS_UNSUPPORTED")
        if secret_content(content):
            exclusions.append(name)
            continue
        try:
            text = content.decode("utf-8")  # 只把普通文本交付模型，二进制不修复。
        except UnicodeError:
            exclusions.append(name)
            continue
        if "\0" in text:
            exclusions.append(name)
            continue
        for directory, data in ((original, content), (destination, redact(text).encode("utf-8"))):
            target = directory / name  # 全新私有目录内部普通文件。
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            target.write_bytes(data)
            target.chmod(0o600)
        manifest[name] = {"sha256": digest, "mode": mode, "bytes": len(content)}
    final, final_exclusions = file_paths(project, private, deadline)  # 清单变化也必须拒绝。
    if final != initial or sorted(final_exclusions) != sorted(set(exclusions) - set(hashes)):
        raise SourceBlocked("WORKSPACE_CHANGED")
    for name, digest in hashes.items():
        if time.monotonic() > deadline:
            raise SourceBlocked("SOURCE_READ_TIMEOUT")
        entry = read_current(project, name, missing_ok=True)  # 第二次核验每个候选的实际字节。
        if (None if entry is None else hashlib.sha256(entry[0]).hexdigest()) != digest:
            raise SourceBlocked("WORKSPACE_CHANGED")
    return {"manifest": manifest, "sourceExclusions": sorted(set(exclusions))}
