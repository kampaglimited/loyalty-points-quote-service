package com.loyalty;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

@ExtendWith(VertxExtension.class)
public class QuoteServiceComponentTest {

    private static WireMockServer fxServer;
    private static WireMockServer promoServer;
    private static final int PORT = 8080;
    private static final int FX_PORT = 8081;
    private static final int PROMO_PORT = 8082;

    @BeforeAll
    static void setup(Vertx vertx, VertxTestContext testContext) {
        fxServer = new WireMockServer(options().port(FX_PORT));
        promoServer = new WireMockServer(options().port(PROMO_PORT));
        fxServer.start();
        promoServer.start();

        DeploymentOptions options = new DeploymentOptions()
                .setConfig(new JsonObject().put("http.port", PORT));

        vertx.deployVerticle(new QuoteServiceVerticle(), options)
                .onComplete(testContext.succeedingThenComplete());
    }

    @AfterAll
    static void tearDown() {
        fxServer.stop();
        promoServer.stop();
    }

    @Test
    @DisplayName("Should calculate points correctly for valid request (SILVER tier)")
    void testSuccessfulQuote(Vertx vertx, VertxTestContext testContext) {
        fxServer.stubFor(get(urlEqualTo("/v1/fx-rate/AED"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"rate\": 3.67}")));

        promoServer.stubFor(get(urlEqualTo("/v1/promos/SUMMER25"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"bonus\": 308, \"expiresSoon\": true}")));

        WebClient client = WebClient.create(vertx);
        JsonObject request = new JsonObject()
                .put("fareAmount", 1234.50)
                .put("currency", "AED")
                .put("customerTier", "SILVER")
                .put("promoCode", "SUMMER25");

        client.post(PORT, "localhost", "/v1/points/quote")
                .sendJsonObject(request)
                .onComplete(testContext.succeeding(response -> {
                    testContext.verify(() -> {
                        assertThat(response.statusCode()).isEqualTo(200);
                        JsonObject body = response.bodyAsJsonObject();
                        // Base: 1234.5 * 3.67 = 4530.615 -> 4530
                        // Tier: 4530 * 0.15 = 679.5 -> 679
                        // Promo: 308
                        // Total: 4530 + 679 + 308 = 5517
                        assertThat(body.getInteger("basePoints")).isEqualTo(4530);
                        assertThat(body.getInteger("tierBonus")).isEqualTo(679);
                        assertThat(body.getInteger("promoBonus")).isEqualTo(308);
                        assertThat(body.getInteger("totalPoints")).isEqualTo(5517);
                        assertThat(body.getJsonArray("warnings")).contains("PROMO_EXPIRES_SOON");
                        testContext.completeNow();
                    });
                }));
    }

    @Test
    @DisplayName("Should retry FX service on failure and succeed if it eventually returns 200")
    void testFxRetrySuccess(Vertx vertx, VertxTestContext testContext) {
        fxServer.stubFor(get(urlEqualTo("/v1/fx-rate/EUR"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("First Failure"));

        fxServer.stubFor(get(urlEqualTo("/v1/fx-rate/EUR"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs("First Failure")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"rate\": 1.1}"))
                .willSetStateTo("Success"));

        WebClient client = WebClient.create(vertx);
        JsonObject request = new JsonObject()
                .put("fareAmount", 100).put("currency", "EUR").put("customerTier", "NONE");

        client.post(PORT, "localhost", "/v1/points/quote")
                .sendJsonObject(request)
                .onComplete(testContext.succeeding(response -> {
                    testContext.verify(() -> {
                        assertThat(response.statusCode()).isEqualTo(200);
                        assertThat(response.bodyAsJsonObject().getInteger("totalPoints")).isEqualTo(110);
                        testContext.completeNow();
                    });
                }));
    }

    @Test
    @DisplayName("Should handle Promo service timeout with default values")
    void testPromoTimeout(Vertx vertx, VertxTestContext testContext) {
        fxServer.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{\"rate\": 1.0}")));

        // Timeout is 1000ms in code, so we delay 2000ms
        promoServer.stubFor(get(urlEqualTo("/v1/promos/SLOWPROMO"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(2000)));

        WebClient client = WebClient.create(vertx);
        JsonObject request = new JsonObject()
                .put("fareAmount", 100).put("currency", "USD").put("customerTier", "NONE")
                .put("promoCode", "SLOWPROMO");

        client.post(PORT, "localhost", "/v1/points/quote")
                .sendJsonObject(request)
                .onComplete(testContext.succeeding(response -> {
                    testContext.verify(() -> {
                        assertThat(response.statusCode()).isEqualTo(200);
                        assertThat(response.bodyAsJsonObject().getInteger("promoBonus")).isEqualTo(0);
                        testContext.completeNow();
                    });
                }));
    }

    @Test
    @DisplayName("Should reject negative fare amounts with 400")
    void testNegativeFare(Vertx vertx, VertxTestContext testContext) {
        WebClient client = WebClient.create(vertx);
        JsonObject request = new JsonObject().put("fareAmount", -10).put("currency", "USD");

        client.post(PORT, "localhost", "/v1/points/quote")
                .sendJsonObject(request)
                .onComplete(testContext.succeeding(response -> {
                    testContext.verify(() -> {
                        assertThat(response.statusCode()).isEqualTo(400);
                        assertThat(response.bodyAsJsonObject().getString("error"))
                                .isEqualTo("Fare amount must be greater than zero");
                        testContext.completeNow();
                    });
                }));
    }

    @Test
    @DisplayName("Should reject zero fare amounts with 400")
    void testZeroFare(Vertx vertx, VertxTestContext testContext) {
        WebClient client = WebClient.create(vertx);
        JsonObject request = new JsonObject().put("fareAmount", 0).put("currency", "USD");

        client.post(PORT, "localhost", "/v1/points/quote")
                .sendJsonObject(request)
                .onComplete(testContext.succeeding(response -> {
                    testContext.verify(() -> {
                        assertThat(response.statusCode()).isEqualTo(400);
                        assertThat(response.bodyAsJsonObject().getString("error"))
                                .isEqualTo("Fare amount must be greater than zero");
                        testContext.completeNow();
                    });
                }));
    }

    @Test
    @DisplayName("Should reject missing currency with 400 and refined message")
    void testMissingCurrency(Vertx vertx, VertxTestContext testContext) {
        WebClient client = WebClient.create(vertx);
        JsonObject request = new JsonObject().put("fareAmount", 100);

        client.post(PORT, "localhost", "/v1/points/quote")
                .sendJsonObject(request)
                .onComplete(testContext.succeeding(response -> {
                    testContext.verify(() -> {
                        assertThat(response.statusCode()).isEqualTo(400);
                        assertThat(response.bodyAsJsonObject().getString("error"))
                                .isEqualTo("Currency is required and must be a 3-character ISO code");
                        testContext.completeNow();
                    });
                }));
    }

    @Test
    @DisplayName("Should reject invalid currency length with 400")
    void testInvalidCurrencyLength(Vertx vertx, VertxTestContext testContext) {
        WebClient client = WebClient.create(vertx);
        JsonObject request = new JsonObject().put("fareAmount", 100).put("currency", "US");

        client.post(PORT, "localhost", "/v1/points/quote")
                .sendJsonObject(request)
                .onComplete(testContext.succeeding(response -> {
                    testContext.verify(() -> {
                        assertThat(response.statusCode()).isEqualTo(400);
                        assertThat(response.bodyAsJsonObject().getString("error")).contains("3-character ISO code");
                        testContext.completeNow();
                    });
                }));
    }

    @Test
    @DisplayName("Should reject invalid tier with 400")
    void testInvalidTier(Vertx vertx, VertxTestContext testContext) {
        WebClient client = WebClient.create(vertx);
        JsonObject request = new JsonObject().put("fareAmount", 100).put("currency", "USD").put("customerTier",
                "DIAMOND");

        client.post(PORT, "localhost", "/v1/points/quote")
                .sendJsonObject(request)
                .onComplete(testContext.succeeding(response -> {
                    testContext.verify(() -> {
                        assertThat(response.statusCode()).isEqualTo(400);
                        assertThat(response.bodyAsJsonObject().getString("error"))
                                .isEqualTo("Invalid customer tier: DIAMOND");
                        testContext.completeNow();
                    });
                }));
    }

    @Test
    @DisplayName("Should reject empty promo code with 400")
    void testEmptyPromoCode(Vertx vertx, VertxTestContext testContext) {
        WebClient client = WebClient.create(vertx);
        JsonObject request = new JsonObject().put("fareAmount", 100).put("currency", "USD").put("promoCode", " ");

        client.post(PORT, "localhost", "/v1/points/quote")
                .sendJsonObject(request)
                .onComplete(testContext.succeeding(response -> {
                    testContext.verify(() -> {
                        assertThat(response.statusCode()).isEqualTo(400);
                        assertThat(response.bodyAsJsonObject().getString("error"))
                                .isEqualTo("Promo code cannot be empty if provided");
                        testContext.completeNow();
                    });
                }));
    }

    @Test
    @DisplayName("Should reject missing request body with 400")
    void testNullBody(Vertx vertx, VertxTestContext testContext) {
        WebClient client = WebClient.create(vertx);

        client.post(PORT, "localhost", "/v1/points/quote")
                .send()
                .onComplete(testContext.succeeding(response -> {
                    testContext.verify(() -> {
                        assertThat(response.statusCode()).isEqualTo(400);
                        assertThat(response.bodyAsJsonObject().getString("error")).isEqualTo("Request body is missing");
                        testContext.completeNow();
                    });
                }));
    }

    @Test
    @DisplayName("Should reject malformed JSON with 400")
    void testMalformedJson(Vertx vertx, VertxTestContext testContext) {
        WebClient client = WebClient.create(vertx);

        client.post(PORT, "localhost", "/v1/points/quote")
                .putHeader("content-type", "application/json")
                .sendBuffer(io.vertx.core.buffer.Buffer.buffer("{invalid-json}"))
                .onComplete(testContext.succeeding(response -> {
                    testContext.verify(() -> {
                        assertThat(response.statusCode()).isEqualTo(400);
                        assertThat(response.bodyAsJsonObject().getString("error")).isEqualTo("Invalid JSON format");
                        testContext.completeNow();
                    });
                }));
    }

    @Test
    @DisplayName("Should cap total points at 50,000")
    void testPointCap(Vertx vertx, VertxTestContext testContext) {
        fxServer.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{\"rate\": 1.0}")));

        WebClient client = WebClient.create(vertx);
        JsonObject request = new JsonObject()
                .put("fareAmount", 100000).put("currency", "USD").put("customerTier", "PLATINUM");

        client.post(PORT, "localhost", "/v1/points/quote")
                .sendJsonObject(request)
                .onComplete(testContext.succeeding(response -> {
                    testContext.verify(() -> {
                        assertThat(response.statusCode()).isEqualTo(200);
                        JsonObject body = response.bodyAsJsonObject();
                        assertThat(body.getInteger("totalPoints")).isEqualTo(50000);
                        assertThat(body.getJsonArray("warnings")).contains("POINTS_CAPPED_AT_MAX");
                        testContext.completeNow();
                    });
                }));
    }

    @Test
    @DisplayName("Should calculate Gold tier and FX normalization for JPY")
    void testGoldTierNormalization(Vertx vertx, VertxTestContext testContext) {
        fxServer.stubFor(get(urlEqualTo("/v1/fx-rate/JPY"))
                .willReturn(aResponse().withStatus(200).withBody("{\"rate\": 110.5}")));

        promoServer.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{\"bonus\": 100}")));

        WebClient client = WebClient.create(vertx);
        JsonObject request = new JsonObject()
                .put("fareAmount", 10.0) // 10 USD equivalent
                .put("currency", "JPY")
                .put("customerTier", "GOLD")
                .put("promoCode", "GOLD_PROMO");

        client.post(PORT, "localhost", "/v1/points/quote")
                .sendJsonObject(request)
                .onComplete(testContext.succeeding(response -> {
                    testContext.verify(() -> {
                        assertThat(response.statusCode()).isEqualTo(200);
                        JsonObject body = response.bodyAsJsonObject();
                        // Base: 10 * 110.5 = 1105
                        // Tier: 1105 * 0.30 = 331.5 -> 331
                        // Promo: 100
                        // Total: 1105 + 331 + 100 = 1536
                        assertThat(body.getInteger("basePoints")).isEqualTo(1105);
                        assertThat(body.getInteger("tierBonus")).isEqualTo(331);
                        assertThat(body.getInteger("totalPoints")).isEqualTo(1536);
                        testContext.completeNow();
                    });
                }));
    }

    @Test
    @DisplayName("Should return 500 when FX service retries are exhausted")
    void testFxRetryExhaustion(Vertx vertx, VertxTestContext testContext) {
        fxServer.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(500)));

        WebClient client = WebClient.create(vertx);
        JsonObject request = new JsonObject().put("fareAmount", 100).put("currency", "GBP").put("customerTier", "NONE");

        client.post(PORT, "localhost", "/v1/points/quote")
                .sendJsonObject(request)
                .onComplete(testContext.succeeding(response -> {
                    testContext.verify(() -> {
                        assertThat(response.statusCode()).isEqualTo(500);
                        assertThat(response.bodyAsJsonObject().getString("error"))
                                .contains("Failed to fetch external data");
                        testContext.completeNow();
                    });
                }));
    }

    @Test
    @DisplayName("Should work without promo code (Optionality)")
    void testOptionalPromo(Vertx vertx, VertxTestContext testContext) {
        fxServer.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{\"rate\": 1.0}")));

        WebClient client = WebClient.create(vertx);
        JsonObject request = new JsonObject().put("fareAmount", 100).put("currency", "USD").put("customerTier", "NONE");

        client.post(PORT, "localhost", "/v1/points/quote")
                .sendJsonObject(request)
                .onComplete(testContext.succeeding(response -> {
                    testContext.verify(() -> {
                        assertThat(response.statusCode()).isEqualTo(200);
                        assertThat(response.bodyAsJsonObject().getInteger("promoBonus")).isEqualTo(0);
                        testContext.completeNow();
                    });
                }));
    }

    @Test
    @DisplayName("Should handle non-existent promo code with 0 bonus (Fallback)")
    void testPromoNotFound(Vertx vertx, VertxTestContext testContext) {
        fxServer.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{\"rate\": 1.0}")));
        promoServer.stubFor(get(urlEqualTo("/v1/promos/INVALID"))
                .willReturn(aResponse().withStatus(404)));

        WebClient client = WebClient.create(vertx);
        JsonObject request = new JsonObject()
                .put("fareAmount", 100).put("currency", "USD").put("customerTier", "NONE").put("promoCode", "INVALID");

        client.post(PORT, "localhost", "/v1/points/quote")
                .sendJsonObject(request)
                .onComplete(testContext.succeeding(response -> {
                    testContext.verify(() -> {
                        assertThat(response.statusCode()).isEqualTo(200);
                        assertThat(response.bodyAsJsonObject().getInteger("promoBonus")).isEqualTo(0);
                        testContext.completeNow();
                    });
                }));
    }

    @Test
    @DisplayName("Should return 500 when currency is unsupported (404 from FX)")
    void testUnsupportedCurrency(Vertx vertx, VertxTestContext testContext) {
        fxServer.stubFor(get(urlEqualTo("/v1/fx-rate/XYZ"))
                .willReturn(aResponse().withStatus(404)));

        WebClient client = WebClient.create(vertx);
        JsonObject request = new JsonObject().put("fareAmount", 100).put("currency", "XYZ").put("customerTier", "NONE");

        client.post(PORT, "localhost", "/v1/points/quote")
                .sendJsonObject(request)
                .onComplete(testContext.succeeding(response -> {
                    testContext.verify(() -> {
                        assertThat(response.statusCode()).isEqualTo(500);
                        assertThat(response.bodyAsJsonObject().getString("error"))
                                .contains("FX service failed with status 404");
                        testContext.completeNow();
                    });
                }));
    }
}
