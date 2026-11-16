package com.edmj.tls;

import java.util.Set;

/** Starts the matching engine and its REST API. Configured with environment variables. */
public final class Main {
    public static final Set<String> DEFAULT_SYMBOLS = Set.of("AAPL", "MSFT", "BNY");

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        String apiKey = System.getenv("API_KEY");
        MatchingEngine engine = new MatchingEngine(DEFAULT_SYMBOLS);
        ApiServer api = new ApiServer(engine, apiKey);
        api.start(port);
        Runtime.getRuntime().addShutdownHook(new Thread(api::stop));
    }

    private Main() {
    }
}
