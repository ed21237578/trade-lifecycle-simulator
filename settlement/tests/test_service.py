import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from service import SettlementState


def trade(tid):
    return {"tradeId": tid, "symbol": "AAPL", "buyerId": "a", "sellerId": "b", "pricePence": 100, "quantity": 1}


def test_state_tracks_last_trade_id_and_reconciles():
    state = SettlementState(error_rate=0.0, seed=1)
    state.ingest([trade(1), trade(2)])
    state.ingest([trade(3)])
    snap = state.snapshot()
    assert state.last_trade_id == 3
    assert snap["summary"]["tradesChecked"] == 3
    assert snap["breaks"] == []


def test_empty_poll_changes_nothing():
    state = SettlementState(error_rate=0.0)
    state.ingest([])
    assert state.snapshot()["summary"]["tradesChecked"] == 0
