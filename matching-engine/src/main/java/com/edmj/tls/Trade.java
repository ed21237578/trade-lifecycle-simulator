package com.edmj.tls;

/** A completed match between a buy order and a sell order. Immutable. */
public record Trade(
        long tradeId,
        String symbol,
        long buyOrderId,
        long sellOrderId,
        String buyerId,
        String sellerId,
        long pricePence,
        long quantity,
        long timestampMillis) {
}
