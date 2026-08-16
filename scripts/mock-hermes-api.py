#!/usr/bin/env python3
"""Container-side mock of the Hermes SSE API for the manual overlay repro.

Endpoints (same contract as the app's HermesApi client):
  POST /v1/runs                    -> 202 {"run_id":"e2e-run","status":"queued"}
  GET  /v1/runs/e2e-run/events     -> SSE: two deltas + run.completed + done
  GET  /v1/health                  -> 200 {"status":"ok"}
Advertised via `adb reverse tcp:8631 tcp:8631` so the phone's 127.0.0.1:8631
reaches this listener.
"""
import socket
import threading

PORT = 8631
HOST = "0.0.0.0"


def handle(conn):
    try:
        with conn:
            data = b""
            while b"\r\n\r\n" not in data:
                chunk = conn.recv(4096)
                if not chunk:
                    return
                data += chunk
            head, _, body = data.partition(b"\r\n\r\n")
            lines = head.decode(errors="replace").split("\r\n")
            request_line = lines[0]
            method, path, _ = request_line.split(" ", 2)
            clen = 0
            for line in lines[1:]:
                if line.lower().startswith("content-length:"):
                    clen = int(line.split(":", 1)[1].strip())
            while len(body) < clen:
                body += conn.recv(4096)
            if method == "POST" and path == "/v1/runs":
                print(f"[mock] POST {path} body={body.decode(errors='replace')}", flush=True)
                resp = b'{"run_id":"e2e-run","status":"queued"}'
                conn.sendall(
                    (
                        "HTTP/1.1 202 Accepted\r\n"
                        "Content-Type: application/json\r\n"
                        f"Content-Length: {len(resp)}\r\n"
                        "Connection: close\r\n\r\n"
                    ).encode()
                    + resp
                )
            elif method == "GET" and path == "/v1/runs/e2e-run/events":
                print("[mock] GET events -> streaming SSE", flush=True)
                conn.sendall(
                    "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".encode()
                )
                for payload, delay in [
                    (b'data: {"event":"message.delta","delta":"Hello "}\n\n', 0.3),
                    (b'data: {"event":"message.delta","delta":"from e2e mock"}\n\n', 0.3),
                    (b'data: {"event":"run.completed","output":"Hello from e2e mock"}\n\n', 0.3),
                    (b'data: {"event":"done"}\n\n', 0.2),
                ]:
                    conn.sendall(payload)
                    conn.settimeout(delay)
                    import time
                    time.sleep(delay)
            elif method == "GET" and path == "/v1/health":
                resp = b'{"status":"ok"}'
                conn.sendall(
                    (
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                        f"Content-Length: {len(resp)}\r\n"
                        "Connection: close\r\n\r\n"
                    ).encode()
                    + resp
                )
            else:
                conn.sendall("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encode())
    except Exception as e:
        print(f"[mock] {e!r}", flush=True)


def main():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((HOST, PORT))
    srv.listen(16)
    print(f"[mock] listening on {HOST}:{PORT}", flush=True)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=handle, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    main()