package com.edmj.tls;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class OrderBookTest {
    private OrderBook book;
    private final AtomicLong ids = new AtomicLong();
    private final AtomicLong seq = new AtomicLong();

    @BeforeEach
    void setUp() {
        AtomicLong tradeIds = new AtomicLong();
        book = new OrderBook("AAPL", tradeIds::incrementAndGet, () -> 1_000L);
    }

    private Order order(String trader, Side side, long price, long qty) {
        return new Order(ids.incrementAndGet(), trader, "AAPL", side, price, qty, seq.incrementAndGet());
    }

    @Test
    @DisplayName("Orders that don't cross rest in the book without trading")
    void nonCrossingOrdersRest() {
        assertTrue(book.add(order("alice", Side.BUY, 14_900, 10)).isEmpty());
        assertTrue(book.add(order("bob", Side.SELL, 15_000, 10)).isEmpty());
        assertEquals(14_900L, book.bestBid());
        assertEquals(15_000L, book.bestAsk());
    }

    @Test
    @DisplayName("A buy at the ask price fills completely and clears the book")
    void exactMatch() {
        book.add(order("bob", Side.SELL, 15_000, 10));
        List<Trade> trades = book.add(order("alice", Side.BUY, 15_000, 10));

        assertEquals(1, trades.size());
        Trade t = trades.get(0);
        assertEquals("alice", t.buyerId());
        assertEquals("bob", t.sellerId());
        assertEquals(15_000, t.pricePence());
        assertEquals(10, t.quantity());
        assertNull(book.bestAsk());
        assertNull(book.bestBid());
    }

    @Test
    @DisplayName("Trades happen at the resting order's price, not the incoming one")
    void tradesAtRestingPrice() {
        book.add(order("bob", Side.SELL, 15_000, 10));
        List<Trade> trades = book.add(order("alice", Side.BUY, 15_500, 10));
        assertEquals(15_000, trades.get(0).pricePence());
    }

    @Test
    @DisplayName("A partly filled order leaves its remainder resting in the book")
    void partialFill() {
        book.add(order("bob", Side.SELL, 15_000, 4));
        Order buy = order("alice", Side.BUY, 15_000, 10);
        List<Trade> trades = book.add(buy);

        assertEquals(4, trades.get(0).quantity());
        assertEquals(6, buy.getRemaining());
        assertEquals(15_000L, book.bestBid());
        assertEquals(6L, book.depth(Side.BUY).get(15_000L));
    }

    @Test
    @DisplayName("Price priority: the cheapest seller is matched first")
    void pricePriority() {
        book.add(order("expensive", Side.SELL, 15_100, 10));
        book.add(order("cheap", Side.SELL, 15_000, 10));
        List<Trade> trades = book.add(order("alice", Side.BUY, 15_100, 10));
        assertEquals("cheap", trades.get(0).sellerId());
    }

    @Test
    @DisplayName("Time priority: at the same price, the earlier order is matched first")
    void timePriority() {
        book.add(order("first", Side.SELL, 15_000, 10));
        book.add(order("second", Side.SELL, 15_000, 10));
        List<Trade> trades = book.add(order("alice", Side.BUY, 15_000, 10));
        assertEquals("first", trades.get(0).sellerId());
    }

    @Test
    @DisplayName("One large order can sweep through several price levels")
    void sweepsLevels() {
        book.add(order("s1", Side.SELL, 15_000, 5));
        book.add(order("s2", Side.SELL, 15_010, 5));
        book.add(order("s3", Side.SELL, 15_020, 5));
        List<Trade> trades = book.add(order("alice", Side.BUY, 15_010, 12));

        assertEquals(2, trades.size());
        assertEquals(15_000, trades.get(0).pricePence());
        assertEquals(15_010, trades.get(1).pricePence());
        assertEquals(15_010L, book.bestBid()); // 2 shares left over rest as a bid
        assertEquals(15_020L, book.bestAsk());
    }

    @Test
    @DisplayName("Cancelled orders are removed and can no longer trade")
    void cancel() {
        Order sell = order("bob", Side.SELL, 15_000, 10);
        book.add(sell);
        assertTrue(book.cancel(sell.getId()));
        assertFalse(book.contains(sell.getId()));
        assertTrue(book.add(order("alice", Side.BUY, 15_000, 10)).isEmpty());
    }

    @Test
    @DisplayName("Cancelling an unknown or already filled order returns false")
    void cancelUnknown() {
        assertFalse(book.cancel(999));
    }

    @Test
    @DisplayName("Orders for the wrong symbol are rejected")
    void wrongSymbol() {
        Order msft = new Order(1, "alice", "MSFT", Side.BUY, 100, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> book.add(msft));
    }

    @Test
    @DisplayName("Self-trade prevention: a trader's order never matches their own resting order")
    void selfTradePrevention() {
        Order mine = order("alice", Side.SELL, 15_000, 10);
        book.add(mine);
        book.add(order("bob", Side.SELL, 15_010, 10));
        List<Trade> trades = book.add(order("alice", Side.BUY, 15_010, 10));

        assertEquals(1, trades.size());
        assertEquals("bob", trades.get(0).sellerId());
        assertFalse(book.contains(mine.getId()));
        assertEquals(1, book.getSelfTradesPrevented());
    }
}
