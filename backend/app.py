"""
Agent IME backend proxy.

Standard library only. Holds the upstream API key (from env), authenticates
clients with a shared bearer token (from env), enforces a daily quota, applies
an upstream timeout, and forwards chat requests to DeepSeek.

Endpoints:
  GET  /healthz                 -> {"ok": true}
  GET  /metrics                 -> counters (auth required)
  POST /v1/chat                 -> {"answer": "...", "usage": {...}}   (non-streaming)
  POST /v1/chat/stream          -> text/event-stream of OpenAI-style delta frames
                                   data: {"choices":[{"delta":{"content":"..."}}]}
                                   data: [DONE]
                                   data: {"error":"...","status":...}      (on failure)

Streaming requests can be cancelled by the client: when the app closes the
socket, the write fails and we close the upstream connection, which stops token
generation immediately.

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
# Verified available model name (deepseek-chat). Override with UPSTREAM_MODEL.
UPSTREAM_MODEL = os.environ.get("UPSTREAM_MODEL", "deepseek-chat")
# Either UPSTREAM_API_KEY or the plain DEEPSEEK_API_KEY env var works.
UPSTREAM_API_KEY = os.environ.get("UPSTREAM_API_KEY") or os.environ.get(
    "DEEPSEEK_API_KEY", ""
)
CLIENT_TOKEN = os.environ.get("CLIENT_TOKEN", "")
DAILY_QUOTA = int(os.environ.get("DAILY_QUOTA", "50"))
TIMEOUT_SECONDS = float(os.environ.get("TIMEOUT_SECONDS", "35"))
HOST = os.environ.get("HOST", "127.0.0.1")
PORT = int(os.environ.get("PORT", "8787"))
# Offline mode: emit synthetic streamed tokens instead of calling upstream.
MOCK_UPSTREAM = os.environ.get("MOCK_UPSTREAM", "") == "1"
# Upstream proxy is opt-in. The environment often has HTTP(S)_PROXY pointing at
# a local proxy that may not be running (or may not route api.deepseek.com);
# urllib honours those variables by default, so we install our own opener that
# ignores them unless UPSTREAM_PROXY is explicitly set.
UPSTREAM_PROXY = os.environ.get("UPSTREAM_PROXY", "").strip()
if UPSTREAM_PROXY:
    _PROXY_HANDLER = urllib.request.ProxyHandler(
        {"http": UPSTREAM_PROXY, "https": UPSTREAM_PROXY}
    )
else:
    _PROXY_HANDLER = urllib.request.ProxyHandler({})
UPSTREAM_OPENER = urllib.request.build_opener(_PROXY_HANDLER)

# Upper bound on request bodies; also bounds worst-case read time.
MAX_BODY_BYTES = 200_000
# Per-connection socket read timeout for reading the request body. Independent
# of the upstream timeout, so a client that declares a Content-Length but never
# sends bytes cannot hang a worker thread indefinitely.
CLIENT_READ_TIMEOUT = float(os.environ.get("CLIENT_READ_TIMEOUT", "10"))

SYSTEM_PROMPT = os.environ.get(
    "SYSTEM_PROMPT",
    "你是手机输入法里的写作助手。用简体中文回答，简洁直接，"
    "不要使用 Markdown、列表或代码块，回答控制在 120 字以内。",
)

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
    server_version = "AgentImeBackend/0.3"
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
        want_stream = False
        if self.path == "/v1/chat":
            want_stream = False
        elif self.path == "/v1/chat/stream":
            want_stream = True
        else:
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

        if not MOCK_UPSTREAM and not UPSTREAM_API_KEY:
            self._send_json(500, {"error": "server_misconfigured"})
            return

        # Shared token => shared quota. X-Client-Id is deliberately ignored so
        # arbitrary client IDs cannot be used to reset/bypass the daily quota.
        allowed, used = _check_and_bump_quota(SHARED_QUOTA_ID)
        if not allowed:
            _metrics_inc("quota_exceeded")
            self._send_json(429, {"error": "quota_exceeded", "used": used})
            return

        if messages[0].get("role") != "system":
            messages = [{"role": "system", "content": SYSTEM_PROMPT}] + messages

        if want_stream:
            self._respond_stream(messages)
        else:
            self._respond_chat(messages)

    # ------------------------------------------------------------------ chat

    def _upstream_body(self, messages, stream: bool) -> bytes:
        body = {
            "model": UPSTREAM_MODEL,
            "messages": messages,
            "temperature": 0.4,
            "stream": stream,
        }
        return json.dumps(body).encode("utf-8")

    def _open_upstream(self, messages, stream: bool):
        r = urllib.request.Request(
            UPSTREAM_URL,
            data=self._upstream_body(messages, stream),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {UPSTREAM_API_KEY}",
            },
            method="POST",
        )
        return UPSTREAM_OPENER.open(r, timeout=TIMEOUT_SECONDS)

    def _respond_chat(self, messages):
        started = time.monotonic()
        try:
            with self._open_upstream(messages, False) as resp:
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
            self._send_json(
                200, {"answer": answer, "usage": {"in": in_tokens, "out": out_tokens}}
            )
        except urllib.error.HTTPError as e:
            _metrics_inc("requests_upstream_http_error")
            self._send_json(502, {"error": "upstream_error", "status": e.code})
        except (TimeoutError, urllib.error.URLError, socket.timeout):
            _metrics_inc("requests_timeout_or_net")
            self._send_json(504, {"error": "upstream_timeout"})
        except Exception:
            _metrics_inc("requests_unexpected_error")
            self._send_json(500, {"error": "unexpected"})

    # -------------------------------------------------------------- streaming

    def _sse_start(self) -> bool:
        try:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("X-Accel-Buffering", "no")
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True
            self.wfile.flush()
            return True
        except (BrokenPipeError, ConnectionResetError, socket.timeout, OSError):
            return False

    def _sse_write(self, payload) -> bool:
        """Writes one SSE frame. Returns False if the client went away."""
        try:
            line = "data: " + json.dumps(payload, ensure_ascii=False) + "\n\n"
            self.wfile.write(line.encode("utf-8"))
            self.wfile.flush()
            return True
        except (BrokenPipeError, ConnectionResetError, socket.timeout, OSError):
            return False

    def _respond_stream(self, messages):
        started = time.monotonic()
        if not self._sse_start():
            _metrics_inc("stream_client_gone_before_start")
            return
        if MOCK_UPSTREAM:
            self._mock_stream(messages, started)
            return
        out_tokens = 0
        resp = None
        try:
            resp = self._open_upstream(messages, True)
            # Follow upstream frames: keep only the delta.content text so the
            # client never has to know about provider-specific framing.
            while True:
                raw_line = resp.readline()
                if not raw_line:
                    break
                line = raw_line.decode("utf-8", "replace").strip()
                if not line or not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    break
                try:
                    obj = json.loads(data)
                except Exception:
                    continue
                try:
                    delta = obj["choices"][0].get("delta") or {}
                    piece = delta.get("content")
                except Exception:
                    piece = None
                if piece:
                    if not self._sse_write(
                        {"choices": [{"delta": {"content": piece}}]}
                    ):
                        _metrics_inc("stream_client_cancelled")
                        return
                    out_tokens += 1
            if not self._sse_write(
                {"usage": {"out_chunks": out_tokens}, "done": True}
            ):
                _metrics_inc("stream_client_cancelled")
                return
            self._sse_write("[DONE]")
            _metrics_inc("stream_ok")
            _metrics_inc("latency_ms_total", (time.monotonic() - started) * 1000.0)
        except urllib.error.HTTPError as e:
            _metrics_inc("stream_upstream_http_error")
            self._sse_write({"error": "upstream_error", "status": e.code})
        except (TimeoutError, urllib.error.URLError, socket.timeout):
            _metrics_inc("stream_timeout_or_net")
            self._sse_write({"error": "upstream_timeout"})
        except Exception:
            _metrics_inc("stream_unexpected_error")
            self._sse_write({"error": "unexpected"})
        finally:
            if resp is not None:
                try:
                    resp.close()  # also aborts generation when cancelled
                except Exception:
                    pass

    def _mock_stream(self, messages, started):
        text = "这是离线模拟回答：后端未连接上游模型。你问的是「" + _last_user_text(
            messages
        ) + "」。"
        for ch in text:
            if not self._sse_write({"choices": [{"delta": {"content": ch}}]}):
                _metrics_inc("stream_client_cancelled")
                return
            time.sleep(0.02)
        self._sse_write({"done": True})
        self._sse_write("[DONE]")
        _metrics_inc("stream_ok")
        _metrics_inc("latency_ms_total", (time.monotonic() - started) * 1000.0)

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


def _last_user_text(messages) -> str:
    for m in reversed(messages):
        if m.get("role") == "user":
            return str(m.get("content", ""))[:40]
    return ""


def main() -> None:
    if MOCK_UPSTREAM:
        print("[agentime-backend] MOCK_UPSTREAM=1: no upstream calls, synthetic stream.")
    elif not UPSTREAM_API_KEY:
        print(
            "[agentime-backend] WARNING: neither UPSTREAM_API_KEY nor DEEPSEEK_API_KEY is set; "
            "POST /v1/chat* will return 500."
        )
    if not CLIENT_TOKEN:
        print(
            "[agentime-backend] WARNING: CLIENT_TOKEN not set; all client requests will fail with 500."
        )
    print(
        f"[agentime-backend] listening on {HOST}:{PORT}, model={UPSTREAM_MODEL}, "
        f"stream={not MOCK_UPSTREAM}"
    )
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    httpd.daemon_threads = True
    httpd.serve_forever()


if __name__ == "__main__":
    main()
