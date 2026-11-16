"""Trading strategies. Pure functions with no network code, so they're easy to test
and the same logic runs in the live bot and in the backtester."""

from __future__ import annotations

from enum import Enum


class Signal(str, Enum):
    BUY = "BUY"
    SELL = "SELL"
    HOLD = "HOLD"


def moving_average(prices: list[float], window: int) -> float | None:
    if window <= 0:
        raise ValueError("window must be positive")
    if len(prices) < window:
        return None
    return sum(prices[-window:]) / window


def mean_reversion_signal(prices: list[float], window: int = 20, threshold: float = 0.01) -> Signal:
    """Bet that the price drifts back towards its recent average.

    If the latest price is more than `threshold` (e.g. 1%) below the moving
    average, the stock looks cheap, so BUY. If it's that far above, SELL.
    Otherwise HOLD. Returns HOLD until there is enough history.
    """
    avg = moving_average(prices, window)
    if avg is None:
        return Signal.HOLD
    last = prices[-1]
    if last < avg * (1 - threshold):
        return Signal.BUY
    if last > avg * (1 + threshold):
        return Signal.SELL
    return Signal.HOLD
