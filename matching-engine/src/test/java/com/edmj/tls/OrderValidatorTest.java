package com.edmj.tls;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OrderValidatorTest {
    private final OrderValidator validator = new OrderValidator(Set.of("AAPL"));

    private static OrderValidator.OrderRequest req(String trader, String symbol, Side side, Long price, Long qty) {
        return new OrderValidator.OrderRequest(trader, symbol, side, price, qty);
    }

    @Test
    void validOrderPasses() {
        assertTrue(validator.validate(req("alice", "AAPL", Side.BUY, 15_000L, 10L)).isEmpty());
    }

    @Test
    void rejectsNegativeOrZeroQuantity() {
        assertFalse(validator.validate(req("alice", "AAPL", Side.BUY, 15_000L, 0L)).isEmpty());
        assertFalse(validator.validate(req("alice", "AAPL", Side.BUY, 15_000L, -5L)).isEmpty());
    }

    @Test
    void rejectsHugeQuantity() {
        assertFalse(validator.validate(req("alice", "AAPL", Side.BUY, 15_000L, 10_000_000L)).isEmpty());
    }

    @Test
    void rejectsBadPrice() {
        assertFalse(validator.validate(req("alice", "AAPL", Side.BUY, 0L, 10L)).isEmpty());
    }

    @Test
    void rejectsUnknownSymbol() {
        assertFalse(validator.validate(req("alice", "TSLA", Side.BUY, 15_000L, 10L)).isEmpty());
    }

    @Test
    void rejectsUnsafeTraderId() {
        assertFalse(validator.validate(req("<script>", "AAPL", Side.BUY, 15_000L, 10L)).isEmpty());
        assertFalse(validator.validate(req("", "AAPL", Side.BUY, 15_000L, 10L)).isEmpty());
    }

    @Test
    void reportsEveryMissingField() {
        assertEquals(5, validator.validate(req(null, null, null, null, null)).size());
    }

    @Test
    void rejectsNullRequest() {
        assertFalse(validator.validate(null).isEmpty());
    }
}
