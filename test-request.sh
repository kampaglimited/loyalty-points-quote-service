#!/bin/bash

echo "Testing image scenario (SILVER tier, SUMMER25 promo, USD)..."
curl -X POST http://localhost:8080/v1/points/quote \
  -H "Content-Type: application/json" \
  -d '{
    "fareAmount": 1234.50,
    "currency": "USD",
    "cabinClass": "ECONOMY",
    "customerTier": "SILVER",
    "promoCode": "SUMMER25"
  }'
echo -e "\n"

echo "Testing normal quote (GOLD tier, SUMMER25 promo)..."
curl -X POST http://localhost:8080/v1/points/quote \
  -H "Content-Type: application/json" \
  -d '{
    "fareAmount": 1000,
    "currency": "AED",
    "customerTier": "GOLD",
    "promoCode": "SUMMER25"
  }'
echo -e "\n"

echo "Testing point capping (100,000 USD fare)..."
curl -X POST http://localhost:8080/v1/points/quote \
  -H "Content-Type: application/json" \
  -d '{
    "fareAmount": 100000,
    "currency": "USD",
    "customerTier": "PLATINUM"
  }'
echo -e "\n"

echo "Testing invalid fare (negative amount)..."
curl -X POST http://localhost:8080/v1/points/quote \
  -H "Content-Type: application/json" \
  -d '{
    "fareAmount": -10,
    "currency": "USD"
  }'
echo -e "\n"
