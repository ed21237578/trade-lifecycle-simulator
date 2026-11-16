"""Trade reconciliation: compare the exchange ledger with the custodian's records.

Every trade must appear in both records with identical economics. Anything
else is a "break" that has to be investigated before the trade can settle,
because settling on wrong data moves the wrong shares or the wrong money.
"""

from __future__ import annotations

from dataclasses import dataclass, asdict
from enum import Enum

from custodian import CustodianRecord


class BreakType(str, Enum):
    MISSING_AT_CUSTODIAN = "MISSING_AT_CUSTODIAN"   # exchange has it, custodian doesn't
    UNKNOWN_AT_EXCHANGE = "UNKNOWN_AT_EXCHANGE"     # custodian has it, exchange doesn't
    QUANTITY_MISMATCH = "QUANTITY_MISMATCH"
    PRICE_MISMATCH = "PRICE_MISMATCH"
    COUNTERPARTY_MISMATCH = "COUNTERPARTY_MISMATCH"


@dataclass(frozen=True)
class Break:
    trade_id: int
    break_type: BreakType
    symbol: str
    exchange_value: str | None
    custodian_value: str | None

    def to_dict(self) -> dict:
        d = asdict(self)
        d["break_type"] = self.break_type.value
        return d


def reconcile(exchange_trades: list[dict], custodian_records: list[CustodianRecord]) -> list[Break]:
    """Return every break between the two record sets, sorted by trade id.

    Matching is done on trade id using a dictionary, so the whole run is O(n)
    rather than comparing every trade with every record (O(n^2)).
    """
    custodian_by_id = {r.trade_id: r for r in custodian_records}
    seen: set[int] = set()
    breaks: list[Break] = []

    for t in exchange_trades:
        tid = t["tradeId"]
        seen.add(tid)
        rec = custodian_by_id.get(tid)
        if rec is None:
            breaks.append(Break(tid, BreakType.MISSING_AT_CUSTODIAN, t["symbol"], _describe(t), None))
            continue
        if rec.quantity != t["quantity"]:
            breaks.append(Break(tid, BreakType.QUANTITY_MISMATCH, t["symbol"], str(t["quantity"]), str(rec.quantity)))
        if rec.price_pence != t["pricePence"]:
            breaks.append(Break(tid, BreakType.PRICE_MISMATCH, t["symbol"], _money(t["pricePence"]), _money(rec.price_pence)))
        if (rec.buyer_id, rec.seller_id) != (t["buyerId"], t["sellerId"]):
            breaks.append(Break(tid, BreakType.COUNTERPARTY_MISMATCH, t["symbol"],
                                f"{t['buyerId']}/{t['sellerId']}", f"{rec.buyer_id}/{rec.seller_id}"))

    for rec in custodian_records:
        if rec.trade_id not in seen:
            breaks.append(Break(rec.trade_id, BreakType.UNKNOWN_AT_EXCHANGE, rec.symbol, None,
                                f"{rec.quantity} @ {_money(rec.price_pence)}"))

    return sorted(breaks, key=lambda b: (b.trade_id, b.break_type.value))


def summarise(trade_count: int, breaks: list[Break]) -> dict:
    """Headline numbers for the dashboard and health check."""
    broken_ids = {b.trade_id for b in breaks}
    by_type: dict[str, int] = {}
    for b in breaks:
        by_type[b.break_type.value] = by_type.get(b.break_type.value, 0) + 1
    matched = max(trade_count - len({b.trade_id for b in breaks if b.break_type != BreakType.UNKNOWN_AT_EXCHANGE}), 0)
    return {
        "tradesChecked": trade_count,
        "tradesMatched": matched,
        "tradesWithBreaks": len(broken_ids),
        "matchRate": round(matched / trade_count, 4) if trade_count else 1.0,
        "breaksByType": by_type,
    }


def _money(pence: int) -> str:
    return f"\u00a3{pence / 100:,.2f}"


def _describe(t: dict) -> str:
    return f"{t['quantity']} @ {_money(t['pricePence'])}"
