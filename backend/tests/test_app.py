import http.client
import importlib
import json
import os
import socket
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer


class BackendTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        os.environ.update({
            "CLIENT_TOKEN": "test-token",
            "UPSTREAM_API_KEY": "upstream-secret",
            "DAILY_QUOTA": "2",
            "TIMEOUT_SECONDS": "2",
            "CLIENT_READ_TIMEOUT": "2",
            # Deterministic stub upstream: local closed port -> URLError, no network.
            "UPSTREAM_URL": "http://127.0.0.1:1/__unused__",
        })
        import app as appmod
        importlib.reload(appmod)
        cls.mod = appmod
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), appmod.Handler)
        cls.port = cls.httpd.server_address[1]
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()

    def setUp(self):
        # Isolate quota and metrics between tests.
        self.mod.reset_state()

    def _post(self, path, body, headers=None):
        data = json.dumps(body).encode("utf-8") if body is not None else b""
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}{path}", data=data, method="POST"
        )
        req.add_header("Content-Type", "application/json")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read().decode("utf-8"))

    def _post_raw(self, path, raw, headers=None):
        """Send raw bytes with an explicit Content-Length via http.client."""
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            conn.putrequest("POST", path)
            conn.putheader("Content-Type", "application/json")
            for k, v in (headers or {}).items():
                conn.putheader(k, v)
            conn.putheader("Content-Length", str(len(raw)))
            conn.endheaders()
            if raw:
                conn.send(raw)
            resp = conn.getresponse()
            body = resp.read()
            try:
                parsed = json.loads(body.decode("utf-8"))
            except Exception:
                parsed = {}
            return resp.status, parsed
        finally:
            conn.close()

    def _get(self, path, headers=None):
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", method="GET")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read().decode("utf-8"))

    def test_health(self):
        s, b = self._get("/healthz")
        self.assertEqual(s, 200)
        self.assertTrue(b["ok"])

    def test_metrics_requires_auth(self):
        s, _ = self._get("/metrics")
        self.assertEqual(s, 401)

    def test_metrics_with_auth(self):
        s, b = self._get("/metrics", {"Authorization": "Bearer test-token"})
        self.assertEqual(s, 200)
        self.assertIn("metrics", b)
        self.assertNotIn("prompt", json.dumps(b).lower())

    def test_chat_requires_auth(self):
        s, b = self._post("/v1/chat", {"messages": [{"role": "user", "content": "hi"}]})
        self.assertEqual(s, 401)
        self.assertEqual(b["error"], "unauthorized")

    def test_chat_rejects_bad_json(self):
        # Send exactly b'{bad' (4 bytes) matching Content-Length; expect 400 bad_json.
        status, body = self._post_raw(
            "/v1/chat",
            b"{bad",
            {"Authorization": "Bearer test-token"},
        )
        self.assertEqual(status, 400)
        self.assertEqual(body.get("error"), "bad_json")

    def test_chat_rejects_empty_body(self):
        status, body = self._post_raw(
            "/v1/chat",
            b"",
            {"Authorization": "Bearer test-token"},
        )
        self.assertEqual(status, 400)
        self.assertEqual(body.get("error"), "bad_size")

    def test_chat_rejects_json_root_not_object(self):
        status, body = self._post_raw(
            "/v1/chat",
            b"[1,2,3]",
            {"Authorization": "Bearer test-token"},
        )
        self.assertEqual(status, 400)
        self.assertEqual(body.get("error"), "bad_json_root")

    def test_chat_rejects_negative_content_length(self):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            conn.putrequest("POST", "/v1/chat")
            conn.putheader("Authorization", "Bearer test-token")
            conn.putheader("Content-Type", "application/json")
            conn.putheader("Content-Length", "-5")
            conn.endheaders()
            resp = conn.getresponse()
            body = json.loads(resp.read().decode("utf-8"))
            self.assertEqual(resp.status, 400)
            self.assertIn(body.get("error"), ("bad_length", "bad_size"))
        finally:
            conn.close()

    def test_chat_rejects_nonnumeric_content_length(self):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            conn.putrequest("POST", "/v1/chat")
            conn.putheader("Authorization", "Bearer test-token")
            conn.putheader("Content-Type", "application/json")
            conn.putheader("Content-Length", "abc")
            conn.endheaders()
            resp = conn.getresponse()
            body = json.loads(resp.read().decode("utf-8"))
            self.assertEqual(resp.status, 400)
            self.assertEqual(body.get("error"), "bad_length")
        finally:
            conn.close()

    def test_quota_shared_across_spoofed_client_ids(self):
        # DAILY_QUOTA=2. Two successful (upstream-failing) calls consumed via
        # different X-Client-Id values must still share one bucket.
        body = {"messages": [{"role": "user", "content": "hi"}]}
        s1, _ = self._post(
            "/v1/chat", body,
            {"Authorization": "Bearer test-token", "X-Client-Id": "spoof-A"},
        )
        s2, _ = self._post(
            "/v1/chat", body,
            {"Authorization": "Bearer test-token", "X-Client-Id": "spoof-B"},
        )
        s3, b3 = self._post(
            "/v1/chat", body,
            {"Authorization": "Bearer test-token", "X-Client-Id": "spoof-C"},
        )
        # Upstream is a closed local port -> 504, but quota is consumed regardless.
        self.assertNotEqual(s1, 429)
        self.assertNotEqual(s2, 429)
        self.assertEqual(s3, 429)
        self.assertEqual(b3["error"], "quota_exceeded")

    def test_quota_enforced(self):
        headers = {"Authorization": "Bearer test-token", "X-Client-Id": "q1"}
        body = {"messages": [{"role": "user", "content": "hi"}]}
        s1, _ = self._post("/v1/chat", body, headers)
        s2, _ = self._post("/v1/chat", body, headers)
        s3, b3 = self._post("/v1/chat", body, headers)
        self.assertNotEqual(s1, 429)
        self.assertNotEqual(s2, 429)
        self.assertEqual(s3, 429)
        self.assertEqual(b3["error"], "quota_exceeded")

    def test_metrics_do_not_include_content(self):
        s, b = self._get("/metrics", {"Authorization": "Bearer test-token"})
        self.assertEqual(s, 200)
        m = b["metrics"]
        for k in m.keys():
            self.assertFalse(k.startswith("prompt") or k.startswith("content"))


if __name__ == "__main__":
    unittest.main()
