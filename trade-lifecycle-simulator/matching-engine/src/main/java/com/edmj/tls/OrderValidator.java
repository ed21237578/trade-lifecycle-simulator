package com.edmj.tls;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks an order request before it reaches the engine. Never trust input from
 * outside the system: reject anything malformed with a clear reason.
 */
public final class OrderValidator {
    public static final long MAX_QUANTITY = 1_000_000L;
    public static final long MAX_PRICE_PENCE = 100_000_000L; // £1,000,000 per share
    private static final Pattern TRADER_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final Set<String> allowedSymbols;

    public OrderValidator(Set<String> allowedSymbols) {
        this.allowedSymbols = Set.copyOf(allowedSymbols);
    }

    /** Returns a list of problems. An empty list means the request is valid. */
    public List<String> validate(OrderRequest req) {
        List<String> errors = new ArrayList<>();
        if (req == null) {
            errors.add("Request body is required");
            return errors;
        }
        if (req.traderId() == null || !TRADER_ID.matcher(req.traderId()).matches()) {
            errors.add("traderId must be 1 to 32 letters, numbers, '_' or '-'");
        }
        if (req.symbol() == null || !allowedSymbols.contains(req.symbol())) {
            errors.add("symbol must be one of " + allowedSymbols);
        }
        if (req.side() == null) {
            errors.add("side must be BUY or SELL");
        }
        if (req.pricePence() == null || req.pricePence() <= 0 || req.pricePence() > MAX_PRICE_PENCE) {
            errors.add("pricePence must be between 1 and " + MAX_PRICE_PENCE);
        }
        if (req.quantity() == null || req.quantity() <= 0 || req.quantity() > MAX_QUANTITY) {
            errors.add("quantity must be between 1 and " + MAX_QUANTITY);
        }
        return errors;
    }

    /** The shape of an incoming JSON order. Boxed types so missing fields arrive as null. */
    public record OrderRequest(String traderId, String symbol, Side side, Long pricePence, Long quantity) {
    }
}
