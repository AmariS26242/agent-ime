"""
Agent IME backend proxy.

Standard library only. Holds the upstream API key (from env), authenticates
clients with a shared bearer token (from env), enforces a daily quota, applies
an upstream timeout, and forwards chat requests to deepseek-flash.

It DOES NOT log request/response bodies. Only metrics (counts, tokens,
latency, error classes) are kept in memory.

The client token is a SHARED secret. Quota is therefore shared across every
caller of that token: X-Client-Id is ignored for quota identity so it cannot be
used to bypass the daily quota. If you need per-user isolation, deploy separate
backends with separate tokens.
"""
from __future__ import annotations

import json
import os
import socket
import threading
import time
import urllib.error
import urllib.request
from collections import defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UPSTREAM_URL = os.environ.get(
    "UPSTREAM_URL", "https://api.deepseek.com/v1/chat/completions"
)
# Verified available model. Do not switch to deepseek-chat unless you have confirmed it.
UPSTREAM_MODEL = os.environ.get("UPSTREAM_MODEL", "deepseek-flash")
UPSTREAM_API_KEY = os.environ.get("UPSTREAM_API_KEY", "")
CLIENT_TOKEN = os.environ.get("CLIENT_TOKEN", "")
DAILY_QUOTA = int(os.environ.get("DAILY_QUOTA", "50"))
TIMEOUT_SECONDS = float(os.environ.get("TIMEOUT_SECONDS", "35"))
HOST = os.environ.get("HOST", "0.0.0.0")
PORT = int(os.environ.get("PORT", "8787"))

# Upper bound on request bodies; also bounds worst-case read time.
MAX_BODY_BYTES = 200_000
# Per-connection socket read timeout for reading the request body. Independent
# of the upstream timeout, so a client that declares a Content-Length but never
# sends bytes cannot hang a worker thread indefinitely.
CLIENT_READ_TIMEOUT = float(os.environ.get("CLIENT_READ_TIMEOUT", "10"))

# Single shared quota bucket: the token is shared, so the quota is shared.
SHARED_QUOTA_ID = "shared"

_lock = threading.Lock()
# metrics: counters only, no content
METRICS: dict[str, float] = defaultdict(float)
DAILY_USAGE: dict[str, int] = defaultdict(int)
DAILY_KEY: list[str] = [time.strftime("%Y-%m-%d")]


def _today() -> str:
    return time.strftime("%Y-%m-%d")


def _roll_daily_if_needed() -> None:
    today = _today()
    if DAILY_KEY[0] != today:
        DAILY_KEY[0] = today
        DAILY_USAGE.clear()


def reset_state() -> None:
    """Test helper: clear quota usage and metrics for isolation."""
    with _lock:
        DAILY_USAGE.clear()
        METRICS.clear()
        DAILY_KEY[0] = time.strftime("%Y-%m-%d")


def _quota_key(client_id: str) -> str:
    return f"{_today()}:{client_id}"


def _check_and_bump_quota(client_id: str) -> tuple[bool, int]:
    with _lock:
        _roll_daily_if_needed()
        k = _quota_key(client_id)
        used = DAILY_USAGE[k]
        if used >= DAILY_QUOTA:
            return False, used
        DAILY_USAGE[k] = used + 1
        return True, used + 1


def _metrics_inc(name: str, delta: float = 1.0) -> None:
    with _lock:
        METRICS[name] += delta


def _sanitize_messages(messages):
    """Return only role/content strings; never echoed in logs."""
    out = []
    if not isinstance(messages, list):
        return out
    for m in messages[:20]:
        if not isinstance(m, dict):
            continue
        role = m.get("role")
        content = m.get("content")
        if not isinstance(role, str) or not isinstance(content, str):
            continue
        if role not in ("system", "user", "assistant"):
            continue
        out.append({"role": role, "content": content[:8000]})
    return out


def _parse_content_length(value) -> int | None:
    """Parse a Content-Length header safely. Returns None if invalid."""
    if value is None:
        return 0
    s = str(value).strip()
    if not s:
        return 0
    # Reject signs/whitespace/symbols outright; only plain non-negative digits.
    if not s.isdigit():
        return None
    try:
        n = int(s)
    except (ValueError, OverflowError):
        return None
    if n < 0:
        return None
    return n


class Handler(BaseHTTPRequestHandler):
    server_version = "AgentImeBackend/0.2"
    # Socket-level timeout applied before we read the request body.
    timeout = CLIENT_READ_TIMEOUT

    def log_message(self, fmt, *args):
        # Suppress default logger; avoid logging paths/query which could contain hints.
        pass

    def setup(self):
        super().setup()
        try:
            self.connection.settimeout(CLIENT_READ_TIMEOUT)
        except Exception:
            pass

    def _send_json(self, status: int, obj: dict) -> None:
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError, socket.timeout, OSError):
            # Client hung up; nothing more we can do, don't crash the worker.
            pass

    def _require_auth(self) -> bool:
        if not CLIENT_TOKEN:
            self._send_json(500, {"error": "server_misconfigured"})
            return False
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Bearer ") or auth[7:] != CLIENT_TOKEN:
            _metrics_inc("auth_fail")
            self._send_json(401, {"error": "unauthorized"})
            return False
        return True

    def do_GET(self):
        try:
            if self.path == "/healthz":
                self._send_json(200, {"ok": True})
                return
            if self.path == "/metrics":
                if not self._require_auth():
                    return
                with _lock:
                    snapshot = dict(METRICS)
                self._send_json(200, {"metrics": snapshot, "quota_daily": DAILY_QUOTA})
                return
            self._send_json(404, {"error": "not_found"})
        except Exception:
            self._send_json(500, {"error": "unexpected"})

    def do_POST(self):
        try:
            self._handle_post()
        except (BrokenPipeError, ConnectionResetError, socket.timeout):
            # Malformed/abandoned client; drop quietly.
            pass
        except Exception:
            try:
                self._send_json(500, {"error": "unexpected"})
            except Exception:
                pass

    def _handle_post(self):
        if self.path != "/v1/chat":
            self._send_json(404, {"error": "not_found"})
            return
        if not self._require_auth():
            return

        # Validate Content-Length BEFORE reading, so a bogus/negative/non-numeric
        # header cannot lead to an unbounded read or an exception.
        length = _parse_content_length(self.headers.get("Content-Length"))
        if length is None:
            _metrics_inc("bad_length")
            self._send_json(400, {"error": "bad_length"})
            return
        if length == 0:
            _metrics_inc("bad_size")
            self._send_json(400, {"error": "bad_size"})
            return
        if length > MAX_BODY_BYTES:
            _metrics_inc("bad_size")
            self._send_json(400, {"error": "bad_size"})
            return

        # Read the exact declared length with a socket timeout so a lying
        # Content-Length cannot hang the worker thread.
        try:
            self.connection.settimeout(CLIENT_READ_TIMEOUT)
            raw = self._read_exact(length)
        except (socket.timeout, TimeoutError):
            _metrics_inc("body_timeout")
            self._send_json(408, {"error": "body_timeout"})
            return
        except (ConnectionResetError, BrokenPipeError):
            return

        if len(raw) != length:
            _metrics_inc("short_body")
            self._send_json(400, {"error": "bad_size"})
            return

        try:
            req = json.loads(raw.decode("utf-8"))
        except Exception:
            _metrics_inc("bad_json")
            self._send_json(400, {"error": "bad_json"})
            return

        if not isinstance(req, dict):
            _metrics_inc("bad_json_root")
            self._send_json(400, {"error": "bad_json_root"})
            return

        messages = _sanitize_messages(req.get("messages"))
        if not messages:
            _metrics_inc("no_messages")
            self._send_json(400, {"error": "no_messages"})
            return

        if not UPSTREAM_API_KEY:
            self._send_json(500, {"error": "server_misconfigured"})
            return

        # Shared token => shared quota. X-Client-Id is deliberately ignored so
        # arbitrary client IDs cannot be used to reset/bypass the daily quota.
        allowed, used = _check_and_bump_quota(SHARED_QUOTA_ID)
        if not allowed:
            _metrics_inc("quota_exceeded")
            self._send_json(429, {"error": "quota_exceeded", "used": used})
            return

        upstream_body = {
            "model": UPSTREAM_MODEL,
            "messages": messages,
            "temperature": 0.4,
            "stream": False,
            # Upstream thinking disabled: keep first-token latency bounded and
            # avoid reasoning traces leaking through toolchains.
            "thinking": {"type": "disabled"},
        }
        started = time.monotonic()
        try:
            r = urllib.request.Request(
                UPSTREAM_URL,
                data=json.dumps(upstream_body).encode("utf-8"),
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {UPSTREAM_API_KEY}",
                },
                method="POST",
            )
            with urllib.request.urlopen(r, timeout=TIMEOUT_SECONDS) as resp:
                text = resp.read().decode("utf-8")
            obj = json.loads(text)
            answer = ""
            try:
                answer = obj["choices"][0]["message"]["content"]
            except Exception:
                answer = ""
            usage = obj.get("usage") or {}
            in_tokens = int(usage.get("prompt_tokens") or 0)
            out_tokens = int(usage.get("completion_tokens") or 0)
            latency = time.monotonic() - started
            _metrics_inc("requests_ok")
            _metrics_inc("tokens_in", in_tokens)
            _metrics_inc("tokens_out", out_tokens)
            _metrics_inc("latency_ms_total", latency * 1000.0)
            self._send_json(200, {"answer": answer, "usage": {"in": in_tokens, "out": out_tokens}})
        except urllib.error.HTTPError as e:
            latency = time.monotonic() - started
            _metrics_inc("requests_upstream_http_error")
            _metrics_inc("latency_ms_total", latency * 1000.0)
            self._send_json(502, {"error": "upstream_error", "status": e.code})
        except (TimeoutError, urllib.error.URLError, socket.timeout):
            latency = time.monotonic() - started
            _metrics_inc("requests_timeout_or_net")
            _metrics_inc("latency_ms_total", latency * 1000.0)
            self._send_json(504, {"error": "upstream_timeout"})
        except Exception:
            _metrics_inc("requests_unexpected_error")
            self._send_json(500, {"error": "unexpected"})

    def _read_exact(self, n: int) -> bytes:
        """Read exactly n bytes or fewer on EOF. Uses non-blocking-ish reads
        bounded by the socket timeout applied in setup()/_handle_post()."""
        chunks = []
        remaining = n
        while remaining > 0:
            chunk = self.rfile.read(min(remaining, 65536))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        return b"".join(chunks)


def main() -> None:
    if not UPSTREAM_API_KEY:
        print("[agentime-backend] WARNING: UPSTREAM_API_KEY not set; POST /v1/chat will return 500.")
    if not CLIENT_TOKEN:
        print("[agentime-backend] WARNING: CLIENT_TOKEN not set; all client requests will fail with 500.")
    print(f"[agentime-backend] listening on {HOST}:{PORT}, model={UPSTREAM_MODEL}")
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
