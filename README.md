# Trade Lifecycle Simulator

A small trading and settlement platform that follows a trade from the moment an order is placed to the moment the bank checks it is correct.

![CI](https://github.com/ed21237578/trade-lifecycle-simulator/actions/workflows/ci.yml/badge.svg)

## What it does

1. **A trading bot places orders.** Simulated traders send buy and sell orders, for example "buy 10 AAPL at £150.00".
2. **The matching engine matches them.** A Java service keeps an order book for each stock and pairs buyers with sellers, using the same rule as real exchanges: best price first, then whoever was first in the queue.
3. **Every match is recorded** as a trade in the ledger.
4. **Settlement checks every trade.** A Python service compares each trade with an independent record from a simulated custodian bank. Any mismatch (a missing trade, the wrong quantity, the wrong price) is flagged as a **break**, which would have to be fixed before any shares or money move.
5. **A live dashboard** shows the order book, trades as they happen, and open breaks.

```
 trading-bot (Python)  ──orders──▶  matching-engine (Java, REST API)  ──trades──▶  settlement (Python)
                                           │                                          │
                                           └────────────── dashboard (JavaScript) ◀───┘
```

## Running it

**With Docker (one command):**

```bash
docker compose up --build
```

Then open <http://localhost:8080>.

**Without Docker:**

```bash
# 1. Matching engine (needs Java 17+ and Maven)
cd matching-engine && mvn package && API_KEY=change-me java -jar target/matching-engine.jar

# 2. Settlement service (new terminal, Python 3.10+)
cd settlement && python service.py

# 3. Trading bot (new terminal)
cd trading-bot && API_KEY=change-me python bot.py
```

**Run the tests:**

```bash
cd matching-engine && mvn test        # JUnit
cd settlement && python -m pytest     # pytest
cd trading-bot && python -m pytest
```

**Backtest the trading strategy:**

```bash
cd trading-bot && python backtest.py --days 500 --window 20 --threshold 0.01
```

## API

| Method | Path | What it does |
|---|---|---|
| `POST` | `/api/orders` | Submit an order. Needs an `X-API-Key` header. |
| `DELETE` | `/api/orders/{id}` | Cancel a resting order. Needs `X-API-Key`. |
| `GET` | `/api/book?symbol=AAPL` | Order book depth for one stock |
| `GET` | `/api/trades?since=0` | Trades with an id greater than `since` |
| `GET` | `/api/symbols` | Stocks you can trade |
| `GET` | `/health` | Status and metrics (orders, trades, volume) |

Example order:

```bash
curl -X POST localhost:8080/api/orders \
  -H "Content-Type: application/json" -H "X-API-Key: change-me" \
  -d '{"traderId":"alice","symbol":"AAPL","side":"BUY","pricePence":15000,"quantity":10}'
```

Settlement service (port 8001): `GET /breaks` and `GET /health`.

## Design decisions

- **Money is stored as whole pence (`long`), never `double`.** Floating point can't represent most decimals exactly (`0.1 + 0.2 != 0.3`), and a rounding error on money is a real bug.
- **Order book data structure.** Each side of the book is a `TreeMap` from price to a queue of orders. The `TreeMap` keeps prices sorted, so the best price is always `firstKey()` in O(log n). Bids sort highest first, asks lowest first. Each price level is an `ArrayDeque`, which gives first-in, first-out time priority. A `HashMap` from order id to order makes cancels a direct lookup.
- **Trades execute at the resting order's price.** The order that was in the book first set the price, which is how real exchanges work.
- **Self-trade prevention.** A trader's order never matches their own resting order. That would fake trading volume and is banned on real exchanges. Like many venues, the engine cancels the older resting order.
- **Thread safety.** The HTTP server handles requests on several threads, so the engine's public methods are `synchronized` and orders are processed one at a time in a single agreed order.
- **Reconciliation runs in O(n).** Custodian records are indexed by trade id in a dictionary, so each trade is checked with a direct lookup rather than compared against every record.
- **Minimal dependencies.** The engine uses the JDK's built-in HTTP server plus Gson for JSON. The Python services use only the standard library. Fewer dependencies means fewer security vulnerabilities to patch.

## Security

- **Input validation:** every order is checked before reaching the engine (known symbol, positive quantity and price within limits, safe trader id). Invalid orders get a `400` with the reasons.
- **API key** on endpoints that change state, compared in constant time so the key can't be guessed by timing the response.
- **Request size limit** of 4 KB on order bodies.
- **The dashboard only inserts data with `textContent`**, never `innerHTML`, so data from the API can't inject scripts (XSS).
- **Containers run as a non-root user.**
- **Dependabot** opens pull requests when a dependency has a security update.

## Testing and CI

- JUnit tests cover matching rules (price priority, time priority, partial fills, sweeping several price levels, cancels, self-trade prevention), validation, and the REST API end to end on a real HTTP server.
- pytest tests cover every break type, the custodian simulation, and the trading strategy and backtester.
- GitHub Actions runs every test suite and builds the Docker images on each push and pull request.

## Monitoring

- `/health` on both services reports status plus live metrics: orders received, trades executed, shares traded, self-trades prevented, and the reconciliation match rate.
- Both services log what they're doing (startup, reconciliation runs, rejected orders, unreachable services).

## How I used AI tools

<!-- Write this section yourself, honestly. For example:
- Which parts you used AI for (scaffolding, tests, debugging, documentation)
- What context and instructions you gave it
- What it got wrong and how you caught it (a failing test, a code review, running it)
- What you changed or rewrote yourself
-->

## What I'd add next

- Persist trades to a database (PostgreSQL) so nothing is lost on restart.
- Market orders and more order types.
- Measure matching latency and rewrite the hot path in C++.
- A machine-learning strategy to compare against the mean reversion baseline.
- Deploy to the cloud with a public demo link.
