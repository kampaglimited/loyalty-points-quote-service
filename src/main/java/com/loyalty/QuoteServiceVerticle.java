package com.loyalty;

import com.loyalty.logic.QuoteCalculator;
import com.loyalty.model.QuoteRequest;
import com.loyalty.model.QuoteResponse;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.client.WebClient;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class QuoteServiceVerticle extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(QuoteServiceVerticle.class);
    private WebClient webClient;
    private final QuoteCalculator calculator = new QuoteCalculator();
    private JsonObject appConfig;

    @Override
    public void start(Promise<Void> startPromise) {
        webClient = WebClient.create(vertx);

        ConfigStoreOptions fileStore = new ConfigStoreOptions()
                .setType("file")
                .setFormat("yaml")
                .setConfig(new JsonObject().put("path", "application.yaml"));

        ConfigStoreOptions envStore = new ConfigStoreOptions()
                .setType("env");

        ConfigStoreOptions sysStore = new ConfigStoreOptions()
                .setType("sys");

        ConfigRetriever retriever = ConfigRetriever.create(vertx,
                new ConfigRetrieverOptions()
                        .addStore(fileStore)
                        .addStore(envStore)
                        .addStore(sysStore));

        retriever.getConfig(ar -> {
            if (ar.succeeded()) {
                // Programmatic config (like in tests) should override file config
                this.appConfig = ar.result().mergeIn(config());
                startApp(startPromise);
            } else {
                logger.error("Failed to load configuration", ar.cause());
                startPromise.fail(ar.cause());
            }
        });
    }

    private void startApp(Promise<Void> startPromise) {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        router.post("/v1/points/quote").handler(this::handleQuote);

        int port = appConfig.getInteger("http.port", 8080);
        vertx.createHttpServer()
                .requestHandler(router)
                .listen(port, http -> {
                    if (http.succeeded()) {
                        startPromise.complete();
                        logger.info("HTTP server started on port {}", port);
                    } else {
                        logger.error("Failed to start HTTP server", http.cause());
                        startPromise.fail(http.cause());
                    }
                });
    }

    private void handleQuote(RoutingContext ctx) {
        long startTime = System.currentTimeMillis();
        try {
            JsonObject body;
            try {
                body = ctx.body().asJsonObject();
            } catch (Exception e) {
                logger.warn("Request received with malformed JSON: {}", e.getMessage());
                sendError(ctx, 400, "Invalid JSON format");
                return;
            }

            if (body == null) {
                logger.warn("Request received with missing body");
                sendError(ctx, 400, "Request body is missing");
                return;
            }

            QuoteRequest request;
            try {
                request = body.mapTo(QuoteRequest.class);
            } catch (Exception e) {
                logger.warn("Request received with invalid fields: {}", e.getMessage());
                sendError(ctx, 400, "Invalid request fields: " + e.getMessage());
                return;
            }

            logger.info("Processing quote request for currency={}, fare={}", request.getCurrency(),
                    request.getFareAmount());

            // Validation
            if (request.getCurrency() == null || request.getCurrency().trim().length() != 3) {
                logger.warn("Validation failed: Invalid currency '{}'", request.getCurrency());
                sendError(ctx, 400, "Currency is required and must be a 3-character ISO code");
                return;
            }
            if (request.getFareAmount() <= 0) {
                logger.warn("Validation failed: Invalid fare amount {}", request.getFareAmount());
                sendError(ctx, 400, "Fare amount must be greater than zero");
                return;
            }

            String tier = request.getCustomerTier() != null ? request.getCustomerTier().toUpperCase() : "NONE";
            List<String> validTiers = List.of("NONE", "SILVER", "GOLD", "PLATINUM");
            if (!validTiers.contains(tier)) {
                logger.warn("Validation failed: Invalid customer tier '{}'", tier);
                sendError(ctx, 400, "Invalid customer tier: " + tier);
                return;
            }
            request.setCustomerTier(tier);

            if (request.getPromoCode() != null && request.getPromoCode().trim().isEmpty()) {
                logger.warn("Validation failed: Empty promo code");
                sendError(ctx, 400, "Promo code cannot be empty if provided");
                return;
            }

            Future<Double> fxRateFuture = getFxRate(request.getCurrency());
            Future<JsonObject> promoFuture = getPromoInfo(request.getPromoCode());

            Future.all(fxRateFuture, promoFuture)
                    .onSuccess(res -> {
                        double rate = fxRateFuture.result();
                        JsonObject promo = promoFuture.result();

                        int promoBonus = promo.getInteger("bonus", 0);
                        List<String> warnings = new ArrayList<>();
                        if (promo.getBoolean("expiresSoon", false)) {
                            warnings.add("PROMO_EXPIRES_SOON");
                        }

                        QuoteResponse response = calculator.calculate(request, rate, promoBonus, warnings);
                        long duration = System.currentTimeMillis() - startTime;
                        logger.info("Quote calculated: totalPoints={}, duration={}ms", response.getTotalPoints(),
                                duration);

                        ctx.response()
                                .putHeader("content-type", "application/json")
                                .end(JsonObject.mapFrom(response).encode());
                    })
                    .onFailure(err -> {
                        logger.error("Failed to fetch external data for quote", err);
                        sendError(ctx, 500, "Failed to fetch external data: " + err.getMessage());
                    });
        } catch (Exception e) {
            logger.error("Internal server error during quote handling", e);
            sendError(ctx, 500, "Internal server error: " + e.getMessage());
        }
    }

    private void sendError(RoutingContext ctx, int statusCode, String message) {
        ctx.response()
                .setStatusCode(statusCode)
                .putHeader("content-type", "application/json")
                .end(new JsonObject()
                        .put("error", message)
                        .put("code", statusCode)
                        .encode());
    }

    private Future<Double> getFxRate(String currency) {
        if ("USD".equalsIgnoreCase(currency))
            return Future.succeededFuture(1.0);

        JsonObject fxConfig = appConfig.getJsonObject("services").getJsonObject("fx");
        String baseUrl = fxConfig.getString("url");
        long timeout = fxConfig.getLong("timeout");
        int retries = fxConfig.getInteger("retries");

        String fxServiceUrl = baseUrl + currency;

        return retryFuture(() -> webClient.getAbs(fxServiceUrl)
                .timeout(timeout)
                .send()
                .map(res -> {
                    if (res.statusCode() == 200) {
                        return res.bodyAsJsonObject().getDouble("rate", 1.0);
                    }
                    throw new RuntimeException("FX service failed with status " + res.statusCode());
                }), retries);
    }

    private Future<JsonObject> getPromoInfo(String promoCode) {
        if (promoCode == null || promoCode.isEmpty()) {
            return Future.succeededFuture(new JsonObject().put("bonus", 0).put("expiresSoon", false));
        }

        JsonObject promoConfig = appConfig.getJsonObject("services").getJsonObject("promo");
        String baseUrl = promoConfig.getString("url");
        long timeout = promoConfig.getLong("timeout");

        String promoServiceUrl = baseUrl + promoCode;

        return webClient.getAbs(promoServiceUrl)
                .timeout(timeout)
                .send()
                .map(res -> {
                    if (res.statusCode() == 200) {
                        return res.bodyAsJsonObject();
                    }
                    return new JsonObject().put("bonus", 0).put("expiresSoon", false);
                })
                .recover(t -> {
                    logger.warn("Promo service timed out or failed for code {}: {}", promoCode, t.getMessage());
                    return Future.succeededFuture(new JsonObject().put("bonus", 0).put("expiresSoon", false));
                });
    }

    private <T> Future<T> retryFuture(java.util.function.Supplier<Future<T>> supplier, int retries) {
        return supplier.get().recover(t -> {
            if (retries > 1) {
                logger.info("Retrying external call, attempts left: {}", retries - 1);
                return retryFuture(supplier, retries - 1);
            }
            return Future.failedFuture(t);
        });
    }
}
