#!/usr/bin/env python3
# ШТРАФОСТАН TOOLS — сервер-релей координат команды.
# Держит последние позиции игроков и раздаёт их всем своим.
# Без внешних зависимостей: только стандартная библиотека Python 3.
#
# Протокол:
#   POST /sync   тело JSON {token, name, x, y, z, dim}
#     -> 200 {"players":[{name,x,y,z,dim,age}, ...]}  (все, кроме отправителя, свежие)
#     -> 403 если токен не совпал
#   GET  /health -> 200 "ok"  (проверка живости)
#
# Настройки берутся из переменных окружения (см. start.bat):
#   RELAY_PORT   (по умолчанию 8080)
#   RELAY_TOKEN  (по умолчанию CHANGE_ME — ОБЯЗАТЕЛЬНО поменяй!)
#   RELAY_TTL    (сек, по умолчанию 15 — насколько позиция считается «свежей»)

import json
import os
import time
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("RELAY_PORT", "8080"))
TOKEN = os.environ.get("RELAY_TOKEN", "SHTRAFOSTAN-7fK92mLpQ4xZ")
TTL = float(os.environ.get("RELAY_TTL", "15"))

# name -> {"x","y","z","dim","ts"}
_players = {}
_lock = threading.Lock()


def _prune(now):
    dead = [n for n, p in _players.items() if now - p["ts"] > TTL]
    for n in dead:
        del _players[n]


class Handler(BaseHTTPRequestHandler):
    # Тихий лог (не спамим консоль на каждый запрос).
    def log_message(self, fmt, *args):
        pass

    def _send(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/health"):
            self.send_response(200)
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(b"ok")
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if not self.path.startswith("/sync"):
            self._send(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            data = json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:
            self._send(400, {"error": "bad json"})
            return

        if str(data.get("token", "")) != TOKEN:
            self._send(403, {"error": "bad token"})
            return

        name = str(data.get("name", "")).strip()
        if not name:
            self._send(400, {"error": "no name"})
            return

        now = time.time()
        with _lock:
            _players[name] = {
                "x": float(data.get("x", 0.0)),
                "y": float(data.get("y", 0.0)),
                "z": float(data.get("z", 0.0)),
                "dim": str(data.get("dim", "")),
                "ts": now,
            }
            _prune(now)
            out = []
            for n, p in _players.items():
                if n == name:
                    continue
                out.append({
                    "name": n, "x": p["x"], "y": p["y"], "z": p["z"],
                    "dim": p["dim"], "age": round(now - p["ts"], 1),
                })
        self._send(200, {"players": out})


def main():
    print(f"ШТРАФОСТАН relay слушает порт {PORT}, TTL={TTL}s")
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        srv.shutdown()


if __name__ == "__main__":
    main()
