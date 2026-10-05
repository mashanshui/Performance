"""明确凭据的行号保留脱敏，不声称能识别任意自然语言或拆分秘密。"""

import re


# 项目凭据和常见模型 Key 有可识别前缀，避免模型读取源码中的完整值。
PREFIXED = re.compile(r"(?:apm_(?:ak|qt|aw)_[A-Za-z0-9_-]+|sk-[A-Za-z0-9_-]{12,})")
# Kotlin/Java/JSON 等源码中的明确命名字符串凭据，不跨行合并代码。
QUOTED = re.compile(r'''(?i)(["']?(?:api[_-]?key|app[_-]?key|access[_-]?token|client[_-]?secret|password|secret|authorization|token)["']?\s*[:=]\s*)(["'])([^\r\n]*?)\2''')
# 环境配置等行中无引号明确赋值，保持一行，不扫描一般代码表达式。
ENV_VALUE = re.compile(r"(?im)^([A-Z0-9_]*(?:API_KEY|APP_KEY|TOKEN|SECRET|PASSWORD)\s*=\s*)([^\r\n]+)$")


def redact(text: str) -> str:
    """仅替换明确字符串凭据，保持换行数量和源码位置，不执行项目内容。"""
    # 命名凭据保持左右引号及赋值语法，不把原值写入诊断。
    result = QUOTED.sub(lambda match: match[1] + match[2] + "[REDACTED]" + match[2], text)
    result = ENV_VALUE.sub(lambda match: match[1] + "[REDACTED]", result)
    return PREFIXED.sub("[REDACTED]", result)
