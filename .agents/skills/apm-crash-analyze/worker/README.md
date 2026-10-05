# 当前工作区分析与有限修复工具

宿主模型负责分析及必要终端验证，Python 负责当前源码快照、读取证明、租约、有限编辑、事实恢复和回传。入口为 [Skill](../SKILL.md)，安装见 [使用说明](../README.md)。

## 配置与开发

只需要可信环境 `APM_ANALYSIS_URL`、`APM_WORKER_CREDENTIAL` 和目标 Git 项目。默认当前目录，项目外用 prepare 的 `--project <绝对路径>`。与 Skill 安装目录无关；无需 HEAD、origin、仓库 JSON 或模型密钥。环境不自动加载 .env，凭据不能输出、入参或提交。

通过 Skill 的 scripts/apm_analysis.py 使用锁定生产依赖。仓库开发可在本目录执行 `uv sync --locked --python 3.12`，再 `uv run --locked pytest`；命令需从目标项目定位时使用启动器或显式 --project。

## 动作

所有任务动作使用唯一 UUID 与同一平台/私有状态目录，默认 `~/.local/state/apm-analysis`（目录 700、文件 600）：

| 动作 | 必要输入与用途 |
|---|---|
| doctor | 仅检查平台变量存在，缺少返回 1 |
| prepare taskId | 当前项目快照；可 --project，首次明确修复才 --repair |
| search taskId | --glob 文件模式，--query 字面量，--limit 最多 100 |
| read taskId | --path 相对文件、--start/--end 行范围 |
| apply taskId | --patch 有限 JSON，只接受实际读过的精确替换或新源码 |
| revert taskId | 活动租约下撤回本工具未被后续改变的内容 |
| submit taskId | --result 语义 schemaVersion=3；恢复上传不带 --result；可 --host 自报名称 |
| status taskId | 平台状态和私有日志重建的实际修改事实 |
| stop taskId | 关闭本地材料，宿主停止保持未知 |

成功返回 0；失败/部分完成/冲突返回 1，参数错误返回 2；启动器缺少 uv 为 127、无法执行为 126。不提供旧 run/probe、固定提交或独立执行器入口。

## 材料范围

只读 Git 定位与列举，不 fetch、不读取 HEAD、不修改索引。采集已跟踪当前内容及未忽略新文件，尊重忽略规则（包含已跟踪忽略文件）；明确排除 .git/.agents/.codex、Skill/Worker、.venv、依赖与构建目录、.env、local.properties、秘密及签名文件。普通 UTF-8 文本脱敏保留行号；二进制、签名内容仅记相对排除路径。拒绝链接、特殊文件、子模块、LFS（含已展开文件）、路径碰撞及采集期间变化。

整个采集最多 120 秒，50,000 文件、256 MiB、单文件 16 MiB。私有原文和清单不交付宿主，返回 sourceExclusions 仅相对路径。read 最多 200 行/8192 字节，search 最多 100 条/五秒，累计交付 JSON 512 KiB；超限失败，不绕过。

snapshotId 在领取前生成并绑定 Run；引用须匹配该快照与同 Run 实际 read，search 不算读取。片段和摘要由修改前原文重建。当前代码不能证明历史 APK 版本；符号不足或输入未知必须列出局限。

## 写入与恢复

--repair 仅表示可信宿主已有本任务的人类明确授权，不是服务端鉴证。现有只读 Run 不能升级。一次补丁最多 20 文件/一 MiB，新文件仅源码/测试且父目录已存在；无删除、移动、目录创建、任意 Shell 或秘密/二进制编辑。

写入前核验目标及实际读过的相关文件；固定用户私有项目锁跨任务和 state-dir 协调本工具。每文件原子替换，保留原权限、未读内容和暂存区；文件锁不能约束任意编辑器，也不承诺多文件事务。写前私有日志记录原补丁、前后摘要及逐文件事实。相同补丁只对账，不自动续写；不同补丁拒绝。中断/确认丢失后按实际字节报告 APPLIED/PARTIAL/CONFLICT/FAILED。revert 只撤回工具确切持有的写后内容；后来用户修改拒绝覆盖。

取消、撤销、期限或网络未知拒绝新的操作，已修改内容和日志保留。stop/清理不撤销代码，不执行 git reset/clean。补丁与平台确认相互独立，RESULT_PENDING 只重传原结果字节；不得重新分析或应用。

## 验证与生命周期

宿主按项目规则执行必要编译/测试，Python 不执行任意命令。verification 仅 HOST_REPORTED，最多十条真实命令/退出码/有限摘要，绝对路径与秘密脱敏；提交前核对已读和已修改文件。验证期间材料改变，即使命令退出零，也不能声称当前工作区通过。repair 来自日志，宿主不能补造；没有修改或未验证明确标记。

看护 20 秒、租约 90 秒、总期限十分钟。取消、到期、撤销或失联关闭材料；Python 不统一控制宿主模型或测试进程。UNKNOWN 停止仍阻止新尝试/新领取，管理员实际核验本次分析结束后确认。报告保存 SUCCEEDED 与停止未知可同时存在。

旧终态报告仅历史只读。代码与 API 以当前协议为准，开发验收见仓库 docs/analysis-validation/current-code-validation.md；安装包不含历史执行器与验收材料。安装可运行不代表模型质量或生产通过。
