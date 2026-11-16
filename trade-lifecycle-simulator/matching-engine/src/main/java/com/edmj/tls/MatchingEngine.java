package com.edmj.tls;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Owns one order book per symbol and the full trade ledger.
 *
 * Public methods are synchronized so that requests arriving on different
 * HTTP threads are processed one at a time, in a single agreed order.
 * Real exchanges get the same guarantee from a single-threaded matching loop.
 */
public final class MatchingEngine {
    private final Map<String, OrderBook> books = new LinkedHashMap<>();
    private final List<Trade> ledger = new ArrayList<>();
    private final AtomicLong orderIds = new AtomicLong();
    private final AtomicLong tradeIds = new AtomicLong();
    private final AtomicLong sequence = new AtomicLong();
    private long ordersReceived;
    private long ordersCancelled;

    public MatchingEngine(Set<String> symbols) {
        this(symbols, System::currentTimeMillis);
    }

    public MatchingEngine(Set<String> symbols, LongSupplier clock) {
        for (String s : symbols.stream().sorted().toList()) {
            books.put(s, new OrderBook(s, tradeIds::incrementAndGet, clock));
        }
    }

    public synchronized SubmitResult submit(String traderId, String symbol, Side side, long pricePence, long quantity) {
        OrderBook book = books.get(symbol);
        if (book == null) {
            throw new IllegalArgumentException("Unknown symbol " + symbol);
        }
        Order order = new Order(orderIds.incrementAndGet(), traderId, symbol, side,
                pricePence, quantity, sequence.incrementAndGet());
        ordersReceived++;
        List<Trade> trades = book.add(order);
        ledger.addAll(trades);
        return new SubmitResult(order.getId(), order.getQuantity() - order.getRemaining(), order.getRemaining(), trades);
    }

    /** Each book indexes its own live orders, so ask each one; there are only a handful of symbols. */
    public synchronized boolean cancel(long orderId) {
        for (OrderBook book : books.values()) {
            if (book.cancel(orderId)) {
                ordersCancelled++;
                return true;
            }
        }
        return false;
    }

    /** Trades with an id greater than sinceId, oldest first. Lets clients poll for new trades only. */
    public synchronized List<Trade> tradesSince(long sinceId) {
        List<Trade> out = new ArrayList<>();
        for (Trade t : ledger) {
            if (t.tradeId() > sinceId) {
                out.add(t);
            }
        }
        return out;
    }

    public synchronized Map<String, Object> bookSnapshot(String symbol) {
        OrderBook book = books.get(symbol);
        if (book == null) {
            return null;
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("symbol", symbol);
        snap.put("bids", book.depth(Side.BUY));
        snap.put("asks", book.depth(Side.SELL));
        snap.put("bestBid", book.bestBid());
        snap.put("bestAsk", book.bestAsk());
        return snap;
    }

    public synchronized Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("ordersReceived", ordersReceived);
        s.put("ordersCancelled", ordersCancelled);
        s.put("tradesExecuted", ledger.size());
        long volume = 0;
        for (Trade t : ledger) {
            volume += t.quantity();
        }
        s.put("sharesTraded", volume);
        long selfTrades = 0;
        for (OrderBook b : books.values()) {
            selfTrades += b.getSelfTradesPrevented();
        }
        s.put("selfTradesPrevented", selfTrades);
        return s;
    }

    public Set<String> symbols() {
        return Collections.unmodifiableSet(books.keySet());
    }

    public record SubmitResult(long orderId, long filledQuantity, long restingQuantity, List<Trade> trades) {
    }
}
