package com.loyalty.model;

import java.util.List;

public class QuoteResponse {
    private int basePoints;
    private int tierBonus;
    private int promoBonus;
    private int totalPoints;
    private double effectiveFxRate;
    private List<String> warnings;

    // Getters and Setters
    public int getBasePoints() { return basePoints; }
    public void setBasePoints(int basePoints) { this.basePoints = basePoints; }

    public int getTierBonus() { return tierBonus; }
    public void setTierBonus(int tierBonus) { this.tierBonus = tierBonus; }

    public int getPromoBonus() { return promoBonus; }
    public void setPromoBonus(int promoBonus) { this.promoBonus = promoBonus; }

    public int getTotalPoints() { return totalPoints; }
    public void setTotalPoints(int totalPoints) { this.totalPoints = totalPoints; }

    public double getEffectiveFxRate() { return effectiveFxRate; }
    public void setEffectiveFxRate(double effectiveFxRate) { this.effectiveFxRate = effectiveFxRate; }

    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings; }
}
