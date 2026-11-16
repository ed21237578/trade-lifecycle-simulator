"""Backtest the mean reversion strategy on a simulated price history.

Usage:  python backtest.py [--days 500] [--window 20] [--threshold 0.01] [--seed 7]

Prints the strategy's result next to simply buying and holding, because a
strategy is only interesting if it beats doing nothing.
"""

from __future__ import annotations

import argparse
import random
from dataclasses import dataclass

from strategy import Signal, mean_reversion_signal


def simulate_prices(days: int, start: float = 150.0, seed: int | None = None,
                    pull: float = 0.05, volatility: float = 0.015) -> list[float]:
    """A random walk with a gentle pull back towards `start` (an Ornstein-Uhlenbeck style process)."""
    rng = random.Random(seed)
    prices = [start]
    for _ in range(days - 1):
        p = prices[-1]
        drift = pull * (start - p) / start
        prices.append(max(0.01, p * (1 + drift + rng.gauss(0, volatility))))
    return prices


@dataclass
class Result:
    final_value: float
    trades: int
    return_pct: float


def run_backtest(prices: list[float], window: int, threshold: float,
                 cash: float = 10_000.0, lot: int = 10) -> Result:
    """Trade `lot` shares on each signal, never going short or into debt."""
    start_cash = cash
    shares = 0
    trades = 0
    for i in range(1, len(prices) + 1):
        history = prices[:i]
        price = history[-1]
        signal = mean_reversion_signal(history, window, threshold)
        if signal is Signal.BUY and cash >= price * lot:
            cash -= price * lot
            shares += lot
            trades += 1
        elif signal is Signal.SELL and shares >= lot:
            cash += price * lot
            shares -= lot
            trades += 1
    final = cash + shares * prices[-1]
    return Result(round(final, 2), trades, round((final / start_cash - 1) * 100, 2))


def buy_and_hold(prices: list[float], cash: float = 10_000.0) -> Result:
    shares = int(cash // prices[0])
    final = cash - shares * prices[0] + shares * prices[-1]
    return Result(round(final, 2), 1, round((final / cash - 1) * 100, 2))


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--days", type=int, default=500)
    ap.add_argument("--window", type=int, default=20)
    ap.add_argument("--threshold", type=float, default=0.01)
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()

    prices = simulate_prices(args.days, seed=args.seed)
    strat = run_backtest(prices, args.window, args.threshold)
    hold = buy_and_hold(prices)
    print(f"Simulated {args.days} days, start {prices[0]:.2f}, end {prices[-1]:.2f}")
    print(f"Mean reversion : {strat.return_pct:+.2f}%  ({strat.trades} trades, final £{strat.final_value:,.2f})")
    print(f"Buy and hold   : {hold.return_pct:+.2f}%  (final £{hold.final_value:,.2f})")


if __name__ == "__main__":
    main()
