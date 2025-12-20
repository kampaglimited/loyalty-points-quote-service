package com.loyalty.logic;

import com.loyalty.model.QuoteRequest;
import com.loyalty.model.QuoteResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class QuoteCalculator {

    private static final Map<String, Double> TIER_MULTIPLIERS = Map.of(
        "NONE", 0.0,
        "SILVER", 0.15,
        "GOLD", 0.30,
        "PLATINUM", 0.50
    );

    private static final int MAX_POINTS = 50000;

    public QuoteResponse calculate(QuoteRequest request, double fxRate, int promoBonus, List<String> externalWarnings) {
        double fareInTargetCurrency = request.getFareAmount() * fxRate;
        int basePoints = (int) Math.floor(fareInTargetCurrency);
        
        double multiplier = TIER_MULTIPLIERS.getOrDefault(request.getCustomerTier(), 0.0);
        int tierBonus = (int) Math.floor(basePoints * multiplier);
        
        int totalPoints = basePoints + tierBonus + promoBonus;
        
        if (totalPoints > MAX_POINTS) {
            totalPoints = MAX_POINTS;
        }

        QuoteResponse response = new QuoteResponse();
        response.setBasePoints(basePoints);
        response.setTierBonus(tierBonus);
        response.setPromoBonus(promoBonus);
        response.setTotalPoints(totalPoints);
        response.setEffectiveFxRate(fxRate);
        
        List<String> warnings = new ArrayList<>(externalWarnings);
        if (totalPoints == MAX_POINTS) {
            warnings.add("POINTS_CAPPED_AT_MAX");
        }
        response.setWarnings(warnings);
        
        return response;
    }
}
