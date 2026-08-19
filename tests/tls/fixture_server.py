"""Localhost TLS fixture server for the mino-tests TLS E2E battery.

One python3 process per mode, spawned (and killed) by the mino test
files via sh!. mino's vendored BearSSL engine is client-only, so a
real TLS peer has to live outside the runtime; this file is that
peer. Loopback only, kernel-chosen port, deterministic payloads.
The serving child detaches its stdio, starts its own session, and
arms a 300 s alarm so a crashed test run never leaks it; SIGTERM
(the kill in the test's finally block) or the alarm shuts it down.
POSIX-only (os.fork). Certificates live in tests/tls/fixtures.

This is a 1:1 port of the python fixtures mino's test suite ran
before the TLS-server-dependent tests moved to this repo (the
manifest is ~/.agentic-sdk/mino/runs/http-client/moved-tls-tests.md):
the raw modes are tls_test.clj's inline server, the routes mode is
tests/fixtures/http/server.py's TLS listener, hold is pool_test.clj's
"tls" mode (renamed: pool_test's python "tls" holds the connection
open while tls_test's answers HTTP, and this server keeps both).

Modes (argv[1]):
  tls        server.pem: read one HTTP request, answer 200 with the
             request path as the body (Connection: close)
  blob       server.pem: 10240 deterministic bytes ((i*7+13)&0xFF)
             straight after the handshake, then close
  wronghost  wrong-host.pem: handshake, drain, close (SNI mismatch)
  expired    expired.pem: handshake, drain, close (notAfter in the
             past)
  silent     server.pem: handshake, drain, then 5 s silence (read
             timeout fixture)
  hold       server.pem: wrap and hold the connection open until the
             peer closes (pool fixture)
  routes     server.pem: mino's HTTP route table over TLS with HTTP/1.1
             keep-alive: /hello, /echo, /echo-headers, /echo-json,
             /items (two pages), /r1 -> /r2 -> /final, /r307 -> /echo,
             /chunked, /gzip, /slow (3 s delayed "late"), /conncount,
             else 404 "not here"

Protocol: prints "PORT PID" (the serving child's pid, no trailing
newline) once the listener is bound, then forks and the parent exits,
so the spawning sh! returns with the server ready and no startup
wait is needed.

Usage:
  python3 tests/tls/fixture_server.py <mode>
  python3 tests/tls/fixture_server.py <mode> --foreground   # debug
"""

import gzip
import json
import os
import signal
import socket
import ssl
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(HERE, "fixtures")

# mode -> (cert, key) in tests/tls/fixtures
CERTS = {
    "tls": ("server.pem", "server.key"),
    "blob": ("server.pem", "server.key"),
    "wronghost": ("wrong-host.pem", "wrong-host.key"),
    "expired": ("expired.pem", "expired.key"),
    "silent": ("server.pem", "server.key"),
    "hold": ("server.pem", "server.key"),
    "routes": ("server.pem", "server.key"),
}


def ctx_for(mode):
    cert, key = CERTS[mode]
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(os.path.join(FIX, cert), os.path.join(FIX, key))
    return ctx


# ---- raw socket modes (tls / blob / wronghost / expired / silent /
# ---- hold), verbatim from tls_test.clj and pool_test.clj ----

def finish(conn, wrapped):
    # A TLS peer closes with close_notify; unwrap sends ours and
    # completes the shutdown handshake. unwrap blocks until the
    # client answers, so it stays inside the per-connection daemon
    # thread. Plain TCP modes just close.
    if wrapped:
        try:
            conn.unwrap()
            return
        except (OSError, ssl.SSLError):
            pass
    try:
        conn.close()
    except OSError:
        pass


def wrap(conn, mode):
    return ctx_for(mode).wrap_socket(conn, server_side=True)


def serve(conn, mode):
    wrapped = mode in ("tls", "blob", "wronghost", "expired",
                       "silent", "hold")
    try:
        if mode == "tls":
            conn = wrap(conn, mode)
            req = b""
            while b"\r\n\r\n" not in req and len(req) < 65536:
                chunk = conn.recv(4096)
                if not chunk:
                    break
                req += chunk
            line = req.split(b"\r\n", 1)[0].decode("latin-1")
            parts = line.split(" ")
            path = parts[1] if len(parts) > 1 else "/"
            body = path.encode("latin-1")
            head = ("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n"
                    "Content-Length: " + str(len(body))
                    + "\r\nConnection: close\r\n\r\n")
            conn.sendall(head.encode("latin-1") + body)
        elif mode == "blob":
            conn = wrap(conn, mode)
            blob = bytes((i * 7 + 13) & 0xFF for i in range(10240))
            conn.sendall(blob)
        elif mode in ("wronghost", "expired"):
            conn = wrap(conn, mode)
            conn.recv(4096)
        elif mode == "silent":
            conn = wrap(conn, mode)
            conn.recv(65536)
            time.sleep(5)
        elif mode == "hold":
            conn = wrap(conn, mode)
            while True:
                if not conn.recv(65536):
                    break
    except (OSError, ssl.SSLError):
        pass
    finish(conn, wrapped)


def raw_loop(srv, mode):
    # Daemon thread per connection so a stalling client never blocks
    # later ones.
    while True:
        try:
            conn, _ = srv.accept()
        except OSError:
            break
        threading.Thread(target=serve, args=(conn, mode),
                         daemon=True).start()


# ---- routes mode: server.py's TLS listener and route table ----

GZIP_BODY = ("gz-integration-" * 40).encode()
ITEM_PAGES = {1: ["alpha", "beta"], 2: ["gamma"]}


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, addr):
        self.nconn = 0
        self.ctx = ctx_for("routes")
        super().__init__(addr, Handler)

    def get_request(self):
        conn, addr = self.socket.accept()
        self.nconn += 1
        # A client aborting mid-handshake (a refused fixture cert)
        # raises SSLError out of get_request; socketserver's OSError
        # catch in _handle_request_noblock drops it and the accept
        # loop keeps serving later connections.
        return self.ctx.wrap_socket(conn, server_side=True), addr


def read_body(h):
    n = int(h.headers.get("Content-Length", 0))
    return h.rfile.read(n) if n > 0 else b""


def text(h, code, s, headers=()):
    body = s.encode("latin-1") if isinstance(s, str) else s
    h.send_response(code)
    for k, v in headers:
        h.send_header(k, v)
    h.send_header("Content-Type", "text/plain")
    h.send_header("Content-Length", str(len(body)))
    h.end_headers()
    h.wfile.write(body)


def json_route(h, code, obj):
    body = json.dumps(obj).encode()
    h.send_response(code)
    h.send_header("Content-Type", "application/json")
    h.send_header("Content-Length", str(len(body)))
    h.end_headers()
    h.wfile.write(body)


def echo_headers_route(h):
    q = urlparse(h.path).query
    json_route(h, 200, {
        "path": h.path,
        "query": {k: v for k, v in parse_qs(q).items()},
        "user-agent": h.headers.get("User-Agent", ""),
        "accept": h.headers.get("Accept", ""),
        "x-probe": h.headers.get("X-Probe", ""),
        "content-type": h.headers.get("Content-Type", ""),
    })


def echo_json_route(h):
    ctype = h.headers.get("Content-Type", "")
    if not ctype.startswith("application/json"):
        json_route(h, 400, {"error": "expected application/json, got "
                                    + ctype})
        return
    try:
        parsed = json.loads(read_body(h).decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        json_route(h, 400, {"error": "body is not valid JSON"})
        return
    json_route(h, 200, {"echo": parsed, "seen": ctype})


def items_route(h):
    q = parse_qs(urlparse(h.path).query)
    try:
        page = int(q.get("page", ["1"])[0])
    except ValueError:
        page = 1
    page = page if page in ITEM_PAGES else 1
    more = page < max(ITEM_PAGES)
    json_route(h, 200, {"page": page,
                        "items": ITEM_PAGES[page],
                        "next_page": (page + 1) if more else None})


def route(h):
    p = urlparse(h.path).path
    if p == "/hello":
        text(h, 200, "hello world")
    elif p == "/echo":
        text(h, 200, read_body(h))
    elif p == "/echo-headers":
        echo_headers_route(h)
    elif p == "/echo-json":
        echo_json_route(h)
    elif p == "/items":
        items_route(h)
    elif p == "/r1":
        text(h, 301, "moved", [("Location", "/r2")])
    elif p == "/r2":
        text(h, 301, "moved", [("Location", "/final")])
    elif p == "/final":
        text(h, 200, "final-landing")
    elif p == "/r307":
        # Drain the offered body: a redirect that keeps the connection
        # open must consume the request before the next one arrives.
        read_body(h)
        text(h, 307, "keep it", [("Location", "/echo")])
    elif p == "/chunked":
        h.send_response(200)
        h.send_header("Transfer-Encoding", "chunked")
        h.end_headers()
        h.wfile.write(b"6\r\nhello \r\n")
        h.wfile.write(b"5\r\nworld\r\n")
        h.wfile.write(b"0\r\n\r\n")
        h.wfile.flush()
    elif p == "/gzip":
        text(h, 200, gzip.compress(GZIP_BODY, mtime=0),
             [("Content-Encoding", "gzip")])
    elif p == "/slow":
        time.sleep(3)
        text(h, 200, "late")
    elif p == "/conncount":
        text(h, 200, str(h.server.nconn))
    else:
        text(h, 404, "not here")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        route(self)

    def do_POST(self):
        route(self)

    def log_message(self, fmt, *args):
        pass


def main():
    mode = sys.argv[1]
    if mode == "routes":
        httpd = Server(("127.0.0.1", 0))
        port = httpd.server_address[1]
    else:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("127.0.0.1", 0))
        s.listen(16)
        port = s.getsockname()[1]

    def run():
        if mode == "routes":
            httpd.serve_forever()
        else:
            raw_loop(s, mode)

    if "--foreground" in sys.argv[2:]:
        sys.stdout.write("%d %d" % (port, os.getpid()))
        sys.stdout.flush()

        def stop_on_stdin_close():
            sys.stdin.read()
            os.kill(os.getpid(), signal.SIGTERM)

        threading.Thread(target=stop_on_stdin_close, daemon=True).start()
        for sig in (signal.SIGTERM, signal.SIGINT):
            signal.signal(sig, lambda *_: os._exit(0))
        run()
        return

    pid = os.fork()
    if pid == 0:
        devnull = os.open(os.devnull, os.O_RDWR)
        os.dup2(devnull, 0)
        os.dup2(devnull, 1)
        os.dup2(devnull, 2)
        os.setsid()
        # Orphan guard: a crashed test run never leaks the server.
        signal.alarm(300)
        run()
        os._exit(0)
    sys.stdout.write("%d %d" % (port, pid))
    sys.exit(0)


if __name__ == "__main__":
    main()
