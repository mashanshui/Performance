# apm-crash-analyze 安装与使用

Skill 已包含 Python 工具、锁定依赖和启动器。当前宿主模型分析当前 Git 工作区（包含未提交内容）；用户明确要求修复时才启用有限本地编辑。网页提供单事件 taskId，不要求构建源码登记。

## 安装

解压完整 `apm-crash-analyze` 文件夹到宿主实际 Skill 目录。项目安装可放在目标项目 `.agents/skills/apm-crash-analyze`；其他宿主按其加载规则安装或明确加载 SKILL.md。仅复制 ZIP 而未解压不能使用。同名目录先核对，避免覆盖配置。

目录包含 SKILL.md、README.md、agents/openai.yaml、scripts/apm_analysis.py 与 worker/ 下的生产 Python 源码、pyproject.toml、uv.lock。无需另外检出服务端或 Worker。

支持 macOS/Linux，Windows 使用 WSL。需要 python3（3.9+）、uv 和 Git；首次调用由 uv 准备 Python 3.12 和锁定生产依赖，需要联网/缓存及安装目录可写。不要复制旧机器的 .venv。

```sh
# 替换实际安装路径；启动器保留宿主当前工作目录。
python3 /实际安装位置/apm-crash-analyze/scripts/apm_analysis.py --help
python3 /实际安装位置/apm-crash-analyze/scripts/apm_analysis.py doctor
```

## 最小配置

APM 后端需运行并显式启用分析功能（部署默认关闭）。管理员创建应用 Worker 凭据，OWNER/ADMIN/DEVELOPER 在 JVM fatal 事件详情创建任务，VIEWER 只读。凭据仅通过受保护可信配置注入 `APM_WORKER_CREDENTIAL`；桌面宿主可能不继承终端环境，按宿主可信机制注入。不输出秘密或把它放参数、源码和聊天。

非敏感平台地址用 `APM_ANALYSIS_URL=http://127.0.0.1:8080`。私有状态默认 `~/.local/state/apm-analysis`，更改时在所有动作使用同一 `--state-dir`。启动器不会自动加载项目 .env。无需仓库 JSON、origin、提交 SHA、混淆声明、模型选择、独立模型密钥、OpenCode 或 Docker。

项目从宿主当前目录定位，与 Skill 放在哪无关。项目外或需要选择其他项目时明确指定绝对路径，不能根据历史记录猜测。

## 自然语言调用

```text
使用 apm-crash-analyze 分析任务 <网页 taskId>，只做分析。
```

```text
使用 apm-crash-analyze 分析并修复任务 <网页 taskId>。
项目是 /明确的/绝对项目路径，请完成相关编译和测试。
```

同一已领取只读任务不能升级为修复，需结束本次并完成停止核验后，在网页显式新尝试。工具流程见 [SKILL.md](SKILL.md) 和 [Worker 说明](worker/README.md)。

## 恢复与结果

报告基于当前代码，无法证明历史 APK 的完整源码。Python 固定 snapshotId、重建真实引用和修复日志；分析、代码修改、宿主测试和整体停止分别显示。SUCCEEDED 只表示报告保存，不代表问题已解决。验证为 HOST_REPORTED，未知模型与用量保持未知。

准备后十分钟期限，20 秒看护、90 秒租约；超时验证如实记录未完成。补丁最多 20 文件/一 MiB，保留原改动与暂存区。同补丁恢复只对账；部分完成或用户后续变化不强行继续/回滚。RESULT_PENDING 重传原结果字节。材料工具关闭不证明宿主停止，管理员仍须实际核验 UNKNOWN。

安装检查与真实宿主修复验收分别记录，其他宿主和生产质量需要单独证据。开发仓库的验收文档不进入安装包。
