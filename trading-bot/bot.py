"""Live trading bot: sends a stream of orders to the matching engine.

Two kinds of simulated trader share the market:
  * Noise traders place random orders around a drifting "fair" price, which
    keeps the order book busy (they stand in for ordinary investors).
  * A mean reversion trader watches real trade prices from the engine and
    buys or sells using strategy.mean_reversion_signal.

Configured with ENGINE_URL, API_KEY, ORDERS_PER_SECOND and SEED.
"""

from __future__ import annotations

import json
import logging
import os
import random
import time
import urllib.error
import urllib.request

from strategy import Signal, mean_reversion_signal

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("bot")

ENGINE_URL = os.environ.get("ENGINE_URL", "http://localhost:8080")
API_KEY = os.environ.get("API_KEY", "")
ORDERS_PER_SECOND = float(os.environ.get("ORDERS_PER_SECOND", "5"))
START_PRICES = {"AAPL": 15000, "MSFT": 32000, "BNY": 6500}  # pence
NOISE_TRADERS = [f"noise_{i}" for i in range(1, 9)]


def post_order(trader: str, symbol: str, side: str, price: int, qty: int) -> dict | None:
    body = json.dumps({"traderId": trader, "symbol": symbol, "side": side,
                       "pricePence": price, "quantity": qty}).encode()
    req = urllib.request.Request(f"{ENGINE_URL}/api/orders", data=body, method="POST",
                                 headers={"Content-Type": "application/json", "X-API-Key": API_KEY})
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        log.warning("Order rejected (%s): %s", e.code, e.read().decode(errors="replace"))
    except OSError as e:
        log.warning("Engine unreachable: %s", e)
    return None


def noise_order(rng: random.Random, fair: dict[str, float]) -> tuple[str, str, str, int, int]:
    symbol = rng.choice(list(fair))
    fair[symbol] *= 1 + rng.gauss(0, 0.0008)          # fair price drifts slowly
    side = rng.choice(["BUY", "SELL"])
    offset = rng.uniform(-0.004, 0.004)                 # bids and offers either side of fair
    price = max(1, round(fair[symbol] * (1 + offset)))
    return rng.choice(NOISE_TRADERS), symbol, side, price, rng.randint(1, 50)


def main() -> None:
    rng = random.Random(int(os.environ["SEED"]) if "SEED" in os.environ else None)
    fair = {s: float(p) for s, p in START_PRICES.items()}
    history: dict[str, list[float]] = {s: [] for s in START_PRICES}
    log.info("Bot sending ~%s orders/sec to %s", ORDERS_PER_SECOND, ENGINE_URL)

    while True:
        trader, symbol, side, price, qty = noise_order(rng, fair)
        result = post_order(trader, symbol, side, price, qty)
        if result:
            history[symbol].extend(t["pricePence"] for t in result["trades"])
            history[symbol] = history[symbol][-200:]

            signal = mean_reversion_signal(history[symbol], window=20, threshold=0.002)
            if signal is not Signal.HOLD:
                last = int(history[symbol][-1])
                post_order("mean_reversion", symbol, signal.value, last, 10)
        time.sleep(1 / ORDERS_PER_SECOND)


if __name__ == "__main__":
    main()
