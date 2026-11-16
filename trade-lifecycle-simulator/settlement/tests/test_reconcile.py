import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pytest

from custodian import CustodianRecord, book_trades
from reconcile import BreakType, reconcile, summarise


def trade(tid, qty=10, price=15000, buyer="alice", seller="bob", symbol="AAPL"):
    return {"tradeId": tid, "symbol": symbol, "buyerId": buyer, "sellerId": seller,
            "pricePence": price, "quantity": qty}


def record(tid, qty=10, price=15000, buyer="alice", seller="bob", symbol="AAPL"):
    return CustodianRecord(tid, symbol, buyer, seller, price, qty)


def test_matching_records_have_no_breaks():
    assert reconcile([trade(1), trade(2)], [record(1), record(2)]) == []


def test_missing_at_custodian():
    breaks = reconcile([trade(1)], [])
    assert [b.break_type for b in breaks] == [BreakType.MISSING_AT_CUSTODIAN]


def test_unknown_at_exchange():
    breaks = reconcile([], [record(7)])
    assert breaks[0].break_type == BreakType.UNKNOWN_AT_EXCHANGE
    assert breaks[0].trade_id == 7


def test_quantity_mismatch_reports_both_values():
    b = reconcile([trade(1, qty=10)], [record(1, qty=9)])[0]
    assert b.break_type == BreakType.QUANTITY_MISMATCH
    assert (b.exchange_value, b.custodian_value) == ("10", "9")


def test_price_mismatch():
    breaks = reconcile([trade(1, price=15000)], [record(1, price=15010)])
    assert breaks[0].break_type == BreakType.PRICE_MISMATCH


def test_counterparty_mismatch():
    breaks = reconcile([trade(1)], [record(1, buyer="carol")])
    assert breaks[0].break_type == BreakType.COUNTERPARTY_MISMATCH


def test_one_trade_can_have_several_breaks():
    breaks = reconcile([trade(1)], [record(1, qty=1, price=1)])
    assert {b.break_type for b in breaks} == {BreakType.QUANTITY_MISMATCH, BreakType.PRICE_MISMATCH}


def test_summary_counts():
    trades = [trade(i) for i in range(1, 5)]
    breaks = reconcile(trades, [record(1), record(2), record(3, qty=1)])
    s = summarise(len(trades), breaks)
    assert s["tradesChecked"] == 4
    assert s["tradesWithBreaks"] == 2
    assert s["tradesMatched"] == 2
    assert s["matchRate"] == 0.5


def test_custodian_with_zero_error_rate_matches_exactly():
    trades = [trade(i) for i in range(1, 101)]
    assert reconcile(trades, book_trades(trades, error_rate=0.0, seed=1)) == []


def test_custodian_with_full_error_rate_always_breaks():
    trades = [trade(i) for i in range(1, 51)]
    breaks = reconcile(trades, book_trades(trades, error_rate=1.0, seed=1))
    assert {b.trade_id for b in breaks} == set(range(1, 51))


def test_custodian_is_repeatable_with_a_seed():
    trades = [trade(i) for i in range(1, 51)]
    assert book_trades(trades, 0.3, seed=42) == book_trades(trades, 0.3, seed=42)


def test_invalid_error_rate_rejected():
    with pytest.raises(ValueError):
        book_trades([], error_rate=1.5)


def test_prices_are_shown_in_pounds():
    b = reconcile([trade(1, price=15000)], [record(1, price=15010)])[0]
    assert (b.exchange_value, b.custodian_value) == ("£150.00", "£150.10")
