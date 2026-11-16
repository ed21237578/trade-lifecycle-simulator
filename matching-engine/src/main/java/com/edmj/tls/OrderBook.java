package com.edmj.tls;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * The order book for one symbol, matched with price-time priority.
 *
 * Data structure choice:
 *  - Each side is a TreeMap from price to a queue of orders at that price.
 *    The TreeMap keeps prices sorted, so the best price is always firstKey(): O(log n).
 *  - Bids are sorted highest first (buyers paying more go first),
 *    asks lowest first (sellers asking less go first).
 *  - Within one price, an ArrayDeque gives first-in, first-out order (time priority).
 *  - A HashMap from order id to order makes cancel lookups O(1).
 */
public final class OrderBook {
    private final String symbol;
    private final NavigableMap<Long, Deque<Order>> bids = new TreeMap<>(Collections.reverseOrder());
    private final NavigableMap<Long, Deque<Order>> asks = new TreeMap<>();
    private final Map<Long, Order> liveOrders = new HashMap<>();
    private final LongSupplier tradeIds;
    private final LongSupplier clock;
    private long selfTradesPrevented;

    public OrderBook(String symbol, LongSupplier tradeIds, LongSupplier clock) {
        this.symbol = symbol;
        this.tradeIds = tradeIds;
        this.clock = clock;
    }

    public String getSymbol() { return symbol; }

    /**
     * Match an incoming order against the opposite side, then rest any unfilled
     * remainder in the book. Returns the trades that happened (possibly none).
     */
    public List<Trade> add(Order incoming) {
        if (!incoming.getSymbol().equals(symbol)) {
            throw new IllegalArgumentException("Order for " + incoming.getSymbol() + " sent to " + symbol + " book");
        }
        List<Trade> trades = new ArrayList<>();
        NavigableMap<Long, Deque<Order>> opposite = incoming.getSide() == Side.BUY ? asks : bids;

        while (!incoming.isFilled() && !opposite.isEmpty()) {
            long bestPrice = opposite.firstKey();
            if (!crosses(incoming, bestPrice)) {
                break; // best available price is worse than the incoming limit, stop matching
            }
            Deque<Order> queue = opposite.get(bestPrice);
            Order resting = queue.peekFirst();

            // Self-trade prevention: a trader must never trade with themselves (it fakes volume
            // and is banned on real exchanges). Like many venues, we cancel the older resting order.
            if (resting.getTraderId().equals(incoming.getTraderId())) {
                queue.pollFirst();
                liveOrders.remove(resting.getId());
                selfTradesPrevented++;
                if (queue.isEmpty()) {
                    opposite.remove(bestPrice);
                }
                continue;
            }

            long qty = Math.min(incoming.getRemaining(), resting.getRemaining());

            incoming.fill(qty);
            resting.fill(qty);
            trades.add(makeTrade(incoming, resting, bestPrice, qty));

            if (resting.isFilled()) {
                queue.pollFirst();
                liveOrders.remove(resting.getId());
                if (queue.isEmpty()) {
                    opposite.remove(bestPrice);
                }
            }
        }

        if (!incoming.isFilled()) {
            NavigableMap<Long, Deque<Order>> own = incoming.getSide() == Side.BUY ? bids : asks;
            own.computeIfAbsent(incoming.getPricePence(), p -> new ArrayDeque<>()).addLast(incoming);
            liveOrders.put(incoming.getId(), incoming);
        }
        return trades;
    }

    /** Remove a resting order. Returns false if it was not in the book (already filled or unknown). */
    public boolean cancel(long orderId) {
        Order order = liveOrders.remove(orderId);
        if (order == null) {
            return false;
        }
        NavigableMap<Long, Deque<Order>> side = order.getSide() == Side.BUY ? bids : asks;
        Deque<Order> queue = side.get(order.getPricePence());
        Iterator<Order> it = queue.iterator();
        while (it.hasNext()) {
            if (it.next().getId() == orderId) {
                it.remove();
                break;
            }
        }
        if (queue.isEmpty()) {
            side.remove(order.getPricePence());
        }
        return true;
    }

    public long getSelfTradesPrevented() { return selfTradesPrevented; }

    public boolean contains(long orderId) {
        return liveOrders.containsKey(orderId);
    }

    /** Total quantity resting at each price level, best price first. */
    public Map<Long, Long> depth(Side side) {
        NavigableMap<Long, Deque<Order>> book = side == Side.BUY ? bids : asks;
        Map<Long, Long> levels = new LinkedHashMap<>();
        for (Map.Entry<Long, Deque<Order>> e : book.entrySet()) {
            long total = 0;
            for (Order o : e.getValue()) {
                total += o.getRemaining();
            }
            levels.put(e.getKey(), total);
        }
        return levels;
    }

    public Long bestBid() { return bids.isEmpty() ? null : bids.firstKey(); }
    public Long bestAsk() { return asks.isEmpty() ? null : asks.firstKey(); }

    private static boolean crosses(Order incoming, long oppositePrice) {
        return incoming.getSide() == Side.BUY
                ? incoming.getPricePence() >= oppositePrice
                : incoming.getPricePence() <= oppositePrice;
    }

    /** Trades execute at the resting order's price: the order that was there first sets the price. */
    private Trade makeTrade(Order incoming, Order resting, long price, long qty) {
        Order buy = incoming.getSide() == Side.BUY ? incoming : resting;
        Order sell = incoming.getSide() == Side.SELL ? incoming : resting;
        return new Trade(tradeIds.getAsLong(), symbol, buy.getId(), sell.getId(),
                buy.getTraderId(), sell.getTraderId(), price, qty, clock.getAsLong());
    }
}
