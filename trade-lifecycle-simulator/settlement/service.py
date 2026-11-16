"""Settlement service: polls the matching engine for new trades, books them at
the simulated custodian, reconciles, and serves the results over HTTP.

Standard library only, so the container stays tiny.

    GET /breaks    every open break plus a summary
    GET /health    liveness check
"""

from __future__ import annotations

import json
import logging
import os
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from custodian import book_trades
from reconcile import reconcile, summarise

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("settlement")

ENGINE_URL = os.environ.get("ENGINE_URL", "http://localhost:8080")
POLL_SECONDS = float(os.environ.get("POLL_SECONDS", "2"))
ERROR_RATE = float(os.environ.get("ERROR_RATE", "0.05"))
PORT = int(os.environ.get("PORT", "8001"))


class SettlementState:
    """Holds every trade seen so far and the latest reconciliation result."""

    def __init__(self, error_rate: float, seed: int | None = None):
        self.error_rate = error_rate
        self.seed = seed
        self.trades: list[dict] = []
        self.custodian = []
        self.breaks = []
        self.last_trade_id = 0
        self.last_run = None
        self.lock = threading.Lock()

    def ingest(self, new_trades: list[dict]) -> None:
        if not new_trades:
            return
        # Custodian books only the new trades, so earlier records (and their errors) stay stable.
        seed = None if self.seed is None else self.seed + self.last_trade_id
        booked = book_trades(new_trades, self.error_rate, seed)
        with self.lock:
            self.trades.extend(new_trades)
            self.custodian.extend(booked)
            self.last_trade_id = max(t["tradeId"] for t in self.trades)
            self.breaks = reconcile(self.trades, self.custodian)
            self.last_run = time.time()
        log.info("Reconciled %d trades, %d breaks open", len(self.trades), len(self.breaks))

    def snapshot(self) -> dict:
        with self.lock:
            return {
                "summary": summarise(len(self.trades), self.breaks),
                "breaks": [b.to_dict() for b in self.breaks[-200:]],
                "lastRun": self.last_run,
            }


def fetch_trades(since: int) -> list[dict]:
    with urllib.request.urlopen(f"{ENGINE_URL}/api/trades?since={since}", timeout=5) as resp:
        return json.loads(resp.read())


def poll_forever(state: SettlementState) -> None:
    while True:
        try:
            state.ingest(fetch_trades(state.last_trade_id))
        except OSError as e:
            log.warning("Could not reach matching engine at %s: %s", ENGINE_URL, e)
        time.sleep(POLL_SECONDS)


def make_handler(state: SettlementState):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == "/breaks":
                self._send(200, state.snapshot())
            elif self.path == "/health":
                self._send(200, {"status": "UP", "tradesSeen": len(state.trades)})
            else:
                self._send(404, {"error": "Not found"})

        def _send(self, status: int, body: dict) -> None:
            data = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, fmt, *args):  # keep request logs quiet
            pass

    return Handler


def main() -> None:
    state = SettlementState(ERROR_RATE)
    threading.Thread(target=poll_forever, args=(state,), daemon=True).start()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), make_handler(state))
    log.info("Settlement service on port %d, polling %s every %ss", PORT, ENGINE_URL, POLL_SECONDS)
    server.serve_forever()


if __name__ == "__main__":
    main()
