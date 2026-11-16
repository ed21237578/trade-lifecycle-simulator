import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pytest

from backtest import buy_and_hold, run_backtest, simulate_prices
from strategy import Signal, mean_reversion_signal, moving_average


def test_moving_average():
    assert moving_average([1, 2, 3, 4], 2) == 3.5
    assert moving_average([1], 2) is None


def test_moving_average_rejects_bad_window():
    with pytest.raises(ValueError):
        moving_average([1, 2], 0)


def test_holds_without_enough_history():
    assert mean_reversion_signal([100.0] * 5, window=20) is Signal.HOLD


def test_buys_when_price_drops_below_average():
    assert mean_reversion_signal([100.0] * 19 + [90.0], window=20, threshold=0.01) is Signal.BUY


def test_sells_when_price_jumps_above_average():
    assert mean_reversion_signal([100.0] * 19 + [110.0], window=20, threshold=0.01) is Signal.SELL


def test_holds_inside_threshold():
    assert mean_reversion_signal([100.0] * 19 + [100.5], window=20, threshold=0.01) is Signal.HOLD


def test_simulated_prices_are_repeatable_and_positive():
    a = simulate_prices(200, seed=3)
    assert a == simulate_prices(200, seed=3)
    assert len(a) == 200 and min(a) > 0


def test_backtest_never_goes_negative_cash():
    prices = simulate_prices(300, seed=5)
    result = run_backtest(prices, window=10, threshold=0.005, cash=1_000.0, lot=1)
    assert result.final_value > 0
    assert result.trades > 0


def test_flat_market_means_no_trades_and_no_change():
    result = run_backtest([100.0] * 50, window=10, threshold=0.01)
    assert result.trades == 0
    assert result.return_pct == 0.0


def test_buy_and_hold():
    assert buy_and_hold([100.0, 110.0], cash=1_000.0).return_pct == 10.0
