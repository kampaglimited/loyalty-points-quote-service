package com.loyalty;

import com.loyalty.logic.QuoteCalculator;
import com.loyalty.model.QuoteRequest;
import com.loyalty.model.QuoteResponse;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.client.WebClient;
import io.vertx.core.Future;

import java.util.ArrayList;
import java.util.List;

public class QuoteServiceVerticle extends AbstractVerticle {

    private WebClient webClient;
    private final QuoteCalculator calculator = new QuoteCalculator();

    @Override
    public void start(Promise<Void> startPromise) {
        webClient = WebClient.create(vertx);
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        
        router.post("/v1/points/quote").handler(this::handleQuote);

        int port = config().getInteger("http.port", 8080);
        vertx.createHttpServer()
            .requestHandler(router)
            .listen(port, http -> {
                if (http.succeeded()) {
                    startPromise.complete();
                    System.out.println("HTTP server started on port " + port);
                } else {
                    startPromise.fail(http.cause());
                }
            });
    }

    private void handleQuote(RoutingContext ctx) {
        try {
            QuoteRequest request = ctx.body().asPojo(QuoteRequest.class);
            if (request.getFareAmount() <= 0) {
                ctx.response().setStatusCode(400).end(new JsonObject().put("error", "Invalid fare amount").encode());
                return;
            }

            // In a real scenario, these would be external calls. Stubs for now.
            Future<Double> fxFuture = getFxRate(request.getCurrency());
            Future<JsonObject> promoFuture = getPromoInfo(request.getPromoCode());

            Future.all(fxFuture, promoFuture).onComplete(ar -> {
                if (ar.succeeded()) {
                    double fxRate = fxFuture.result();
                    JsonObject promo = promoFuture.result();
                    int promoBonus = promo.getInteger("bonus", 0);
                    List<String> warnings = new ArrayList<>();
                    if (promo.getBoolean("expiresSoon", false)) {
                        warnings.add("PROMO_EXPIRES_SOON");
                    }

                    QuoteResponse response = calculator.calculate(request, fxRate, promoBonus, warnings);
                    ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(Json.encodePrettily(response));
                } else {
                    ctx.response().setStatusCode(500).end(new JsonObject().put("error", "Failed to fetch external data").encode());
                }
            });

        } catch (Exception e) {
            ctx.response().setStatusCode(400).end(new JsonObject().put("error", "Invalid request body").encode());
        }
    }

    private Future<Double> getFxRate(String currency) {
        if ("USD".equalsIgnoreCase(currency)) return Future.succeededFuture(1.0);
        
        // Mock FX service URL - in real scenario this would be in config
        String fxServiceUrl = "http://localhost:8081/v1/fx-rate/" + currency;
        
        return retryFuture(() -> 
            webClient.getAbs(fxServiceUrl)
                .timeout(2000)
                .send()
                .map(res -> {
                    if (res.statusCode() == 200) {
                        return res.bodyAsJsonObject().getDouble("rate", 1.0);
                    }
                    throw new RuntimeException("FX service failed with status " + res.statusCode());
                }), 3);
    }

    private Future<JsonObject> getPromoInfo(String promoCode) {
        if (promoCode == null || promoCode.isEmpty()) {
            return Future.succeededFuture(new JsonObject().put("bonus", 0).put("expiresSoon", false));
        }

        String promoServiceUrl = "http://localhost:8082/v1/promos/" + promoCode;
        
        return webClient.getAbs(promoServiceUrl)
            .timeout(1000) // Promo timeout handling requirement
            .send()
            .map(res -> {
                if (res.statusCode() == 200) {
                    return res.bodyAsJsonObject();
                }
                return new JsonObject().put("bonus", 0).put("expiresSoon", false);
            })
            .recover(t -> {
                System.err.println("Promo service timed out or failed: " + t.getMessage());
                return Future.succeededFuture(new JsonObject().put("bonus", 0).put("expiresSoon", false));
            });
    }

    private <T> Future<T> retryFuture(java.util.function.Supplier<Future<T>> supplier, int retries) {
        return supplier.get().recover(t -> {
            if (retries > 1) {
                System.out.println("Retrying FX call... attempts left: " + (retries - 1));
                return retryFuture(supplier, retries - 1);
            }
            return Future.failedFuture(t);
        });
    }
}
