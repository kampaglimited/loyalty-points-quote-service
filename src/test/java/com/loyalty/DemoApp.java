package com.loyalty;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * A Demo application that starts WireMock stubs and the QuoteServiceVerticle
 * so you can test the request/response flow with curl.
 */
public class DemoApp {

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();

        // 1. Start FX Server Stub (Port 8081)
        WireMockServer fxServer = new WireMockServer(options().port(8081));
        fxServer.start();
        fxServer.stubFor(get(urlMatching("/v1/fx-rate/.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"rate\": 3.67}")));
        System.out.println("FX Stub started on port 8081 (Default rate: 3.67)");

        // 2. Start Promo Server Stub (Port 8082)
        WireMockServer promoServer = new WireMockServer(options().port(8082));
        promoServer.start();
        // Specific stub with high priority (1 is highest)
        promoServer.stubFor(get(urlEqualTo("/v1/promos/SUMMER25")).atPriority(1)
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"bonus\": 308, \"expiresSoon\": true}")));
        
        // Catch-all with lower priority
        promoServer.stubFor(get(urlMatching("/v1/promos/.*")).atPriority(10)
            .willReturn(aResponse().withStatus(404)));
        System.out.println("Promo Stub started on port 8082 (SUMMER25 code active)");

        // 3. Deploy Quote Service (Port 8080)
        DeploymentOptions options = new DeploymentOptions()
            .setConfig(new JsonObject().put("http.port", 8080));

        vertx.deployVerticle(new QuoteServiceVerticle(), options)
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    System.out.println("=================================================");
                    System.out.println("Quote Service is RUNNING on http://localhost:8080");
                    System.out.println("=================================================");
                    System.out.println("\nTry this sample request:");
                    System.out.println("curl -X POST http://localhost:8080/v1/points/quote \\");
                    System.out.println("  -H \"Content-Type: application/json\" \\");
                    System.out.println("  -d '{\"fareAmount\": 1000, \"currency\": \"AED\", \"customerTier\": \"GOLD\", \"promoCode\": \"SUMMER25\"}'");
                    System.out.println("\nPress Ctrl+C to stop.");
                } else {
                    System.err.println("Failed to deploy verticle: " + ar.cause().getMessage());
                    System.exit(1);
                }
            });
    }
}
