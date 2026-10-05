"""随 Skill 启动锁定的 Python 工具，保留当前目录和命令返回码。"""

import os
import shutil
import subprocess
import sys
from pathlib import Path


def main():
    """定位本 Skill 的运行时，首次调用由 uv 准备 Python 与生产依赖。"""
    if os.name != "posix":
        print("当前运行时支持 macOS/Linux；Windows 请在 WSL 中使用。", file=sys.stderr)
        return 2
    # 工具位置只依据脚本自身，安装目录可包含空格和中文。
    worker = Path(__file__).resolve().parents[1] / "worker"
    if not (worker / "uv.lock").is_file() or not (worker / "pyproject.toml").is_file():
        print("Skill 运行时不完整，请重新安装完整的 apm-crash-analyze 目录。", file=sys.stderr)
        return 2
    # 仅从宿主可信 PATH 寻找 uv，不自动执行安装脚本。
    executable = shutil.which("uv")
    if executable is None:
        print("未找到 uv。请安装 uv 并加入宿主 Agent 的 PATH 后重试。", file=sys.stderr)
        return 127
    # 环境内凭据只传给工具，不打印；不加载调用目录中的 .env 文件。
    environment = os.environ.copy()
    environment["UV_PROJECT_ENVIRONMENT"] = str(worker / ".venv")
    # 参数逐项传递，不经过 Shell；--project 保留调用者的相对结果文件语义。
    command = [executable, "run", "--project", str(worker), "--locked", "--no-dev",
               "--no-env-file", "--no-config", "--python", "3.12", "apm-analysis", *sys.argv[1:]]
    try:
        return subprocess.run(command, env=environment, check=False).returncode
    except OSError:
        print("无法启动 uv，请检查安装及执行权限。", file=sys.stderr)
        return 126


if __name__ == "__main__":
    raise SystemExit(main())
