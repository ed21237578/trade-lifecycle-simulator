"""Simulated custodian: the bank's own independent record of each trade.

In real life the custodian (a bank like BNY) books trades from its own feed
rather than copying the exchange. Most of the time the two records agree, but
sometimes they don't: a fat-fingered quantity, a stale price, a trade that
never arrived. This module produces a custodian record set with a small,
controllable rate of those errors so reconciliation has something to find.
"""

from __future__ import annotations

import random
from dataclasses import dataclass


@dataclass(frozen=True)
class CustodianRecord:
    trade_id: int
    symbol: str
    buyer_id: str
    seller_id: str
    price_pence: int
    quantity: int


def book_trades(trades: list[dict], error_rate: float = 0.05, seed: int | None = None) -> list[CustodianRecord]:
    """Turn exchange trades into custodian records, injecting errors at `error_rate`.

    Each trade independently has an `error_rate` chance of one of:
      * being dropped (the custodian never received it)
      * having the wrong quantity
      * having the wrong price
    A fixed `seed` makes the output repeatable, which keeps tests deterministic.
    """
    if not 0.0 <= error_rate <= 1.0:
        raise ValueError("error_rate must be between 0 and 1")
    rng = random.Random(seed)
    records: list[CustodianRecord] = []
    for t in trades:
        record = CustodianRecord(
            trade_id=t["tradeId"],
            symbol=t["symbol"],
            buyer_id=t["buyerId"],
            seller_id=t["sellerId"],
            price_pence=t["pricePence"],
            quantity=t["quantity"],
        )
        if rng.random() < error_rate:
            fault = rng.choice(["drop", "quantity", "price"])
            if fault == "drop":
                continue
            if fault == "quantity":
                record = _replace(record, quantity=max(1, record.quantity + rng.choice([-1, 1]) * rng.randint(1, 5)))
            else:
                record = _replace(record, price_pence=record.price_pence + rng.choice([-1, 1]) * rng.randint(1, 50))
        records.append(record)
    return records


def _replace(record: CustodianRecord, **changes) -> CustodianRecord:
    data = record.__dict__ | changes
    return CustodianRecord(**data)
