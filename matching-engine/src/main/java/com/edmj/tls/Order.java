package com.edmj.tls;

/**
 * A request to buy or sell a quantity of shares at a limit price.
 *
 * Prices are stored as whole pence (a long), never as a double. Floating point
 * numbers cannot represent most decimal values exactly (0.1 + 0.2 != 0.3), and in
 * finance a rounding error on money is a real bug, so integers are the safe choice.
 */
public final class Order {
    private final long id;
    private final String traderId;
    private final String symbol;
    private final Side side;
    private final long pricePence;
    private final long quantity;
    private final long sequence;
    private long remaining;

    public Order(long id, String traderId, String symbol, Side side,
                 long pricePence, long quantity, long sequence) {
        this.id = id;
        this.traderId = traderId;
        this.symbol = symbol;
        this.side = side;
        this.pricePence = pricePence;
        this.quantity = quantity;
        this.sequence = sequence;
        this.remaining = quantity;
    }

    public long getId() { return id; }
    public String getTraderId() { return traderId; }
    public String getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public long getPricePence() { return pricePence; }
    public long getQuantity() { return quantity; }
    public long getSequence() { return sequence; }
    public long getRemaining() { return remaining; }
    public boolean isFilled() { return remaining == 0; }

    /** Reduce the unfilled quantity after part (or all) of the order trades. */
    void fill(long qty) {
        if (qty <= 0 || qty > remaining) {
            throw new IllegalArgumentException("Invalid fill quantity " + qty + " for order " + id);
        }
        remaining -= qty;
    }
}
