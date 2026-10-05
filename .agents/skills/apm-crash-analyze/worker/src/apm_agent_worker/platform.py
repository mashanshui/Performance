"""独立 Worker HTTP 身份，提供稳定请求及有界响应，不输出敏感正文。"""

import json
import re
from typing import Any
from urllib.parse import urlsplit

import httpx


class WorkerError(RuntimeError):
    """只携带可公开的稳定错误码，不保存请求、凭据或原始服务错误。"""


def base_url(value: str) -> str:
    """平台地址来自本地配置；允许 HTTP 或经 Nginx 的 HTTPS。"""
    # 拒绝 URL 内秘密、查询参数或额外路由，避免认证头发到意外入口。
    parsed = urlsplit(value)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password \
            or parsed.query or parsed.fragment or parsed.path not in {"", "/"}:
        raise WorkerError("PLATFORM_URL_INVALID")
    return value.rstrip("/")


class PlatformClient:
    """Cookie、App Key、查询 Token 都不能作为独立执行凭据。"""

    def __init__(self, url: str, credential: str, transport: httpx.BaseTransport | None = None) -> None:
        """只从受保护进程配置接收秘密，调用者不将它作为 CLI 参数。"""
        if not re.fullmatch(r"apm_aw_[A-Za-z0-9_-]{43}", credential):
            raise WorkerError("WORKER_CREDENTIAL_MISSING")
        # 不加载环境代理，不接受自动重定向到其他认证目标。
        self.client = httpx.Client(base_url=base_url(url), headers={"Authorization": "Bearer " + credential},
                                   timeout=httpx.Timeout(10, connect=5), trust_env=False, follow_redirects=False,
                                   transport=transport)

    def close(self) -> None:
        """关闭连接，不持久化 Cookie 或认证信息。"""
        self.client.close()

    def request(self, method: str, path: str, *, body: dict[str, Any] | None = None,
                headers: dict[str, str] | None = None, limit: int = 1048576, raw: bool = False) -> Any:
        """读取有限响应；错误仅保留服务端稳定码，不暴露消息或完整响应。"""
        try:
            with self.client.stream(method, path, json=body, headers=headers) as response:
                # 最多读取 limit+1 字节，控制面错误也不能无限加载。
                content = bytearray()
                for chunk in response.iter_bytes(chunk_size=16384):
                    content.extend(chunk)
                    if len(content) > limit:
                        raise WorkerError("PLATFORM_RESPONSE_TOO_LARGE")
                # 重定向不是正常成功，拒绝继承认证头。
                if response.status_code >= 300:
                    try:
                        code = json.loads(content).get("code", "PLATFORM_REQUEST_REJECTED")
                    except (ValueError, AttributeError):
                        code = "PLATFORM_REQUEST_REJECTED"
                    raise WorkerError(code if isinstance(code, str) and re.fullmatch(r"[A-Z_]{1,64}", code)
                                      else "PLATFORM_REQUEST_REJECTED")
                return bytes(content) if raw else json.loads(content)
        except httpx.HTTPError:
            raise WorkerError("NETWORK_ERROR") from None
        except (ValueError, UnicodeError):
            raise WorkerError("PLATFORM_RESPONSE_INVALID") from None

    def inspect(self, task_id: str) -> dict[str, Any]:
        """领取前只读取指定应用任务元数据，不读取证据正文。"""
        return self.request("GET", f"/api/worker/v1/tasks/{task_id}", limit=65536)

    def claim(self, task_id: str, request_id: str, snapshot_id: str) -> dict[str, Any]:
        """稳定请求 ID 由本地恢复记录提供，超时不能换 ID 重复领取。"""
        return self.request("POST", f"/api/worker/v1/tasks/{task_id}/claim", body={"requestId": request_id, "snapshotId": snapshot_id}, limit=65536)

    def status(self, run_id: str) -> dict[str, Any]:
        """未知写回结果先对账，绝不重新调用模型。"""
        return self.request("GET", f"/api/worker/v1/tasks/runs/{run_id}", limit=65536)

    def heartbeat(self, run_id: str, lease: dict[str, Any]) -> dict[str, Any]:
        """只续当前 Run，不把网络错误当成功或停止。"""
        return self.request("POST", f"/api/worker/v1/tasks/runs/{run_id}/heartbeat", body=lease, limit=65536)

    def evidence(self, run_id: str, lease: dict[str, Any]) -> bytes:
        """取得原始字节供独立 SHA-256 校验。"""
        return self.request("GET", f"/api/worker/v1/tasks/runs/{run_id}/evidence", headers={
            "X-Analysis-Lease": lease["token"], "X-Analysis-Generation": str(lease["generation"])}, raw=True)
