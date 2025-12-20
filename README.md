# 🖥️ Loyalty Points - Quote Service

Welcome to the **Quote Service**! This is the core "brain" of the Loyalty Points system. Its job is to take a flight fare and calculate exactly how many loyalty points a customer should earn based on their status and any active promotions.

---

## 🛠️ Prerequisites & Setup

To run this service on your computer, you need two standard tools:
1.  **Java 21 SDK**: The engine that runs Java code.
2.  **Maven 3.x**: The manager that helps build and run the project.

### First-Time Installation
- **Mac**: `brew install openjdk@21 maven`
- **Windows**: Download and install from the official Oracle and Apache websites.

---

## 🌟 What does this app do?
Think of this service as a high-speed calculator. When a travel app wants to show a user how many points they'll get for a flight, it sends the details here. This service then:
1.  **Checks the Exchange Rate**: Converts the fare into a standard format.
2.  **Applies Bonuses**: Adds extra points if the user is a "Silver", "Gold", or "Platinum" member.
3.  **Applies Promos**: Checks if a special code (like "SUMMER25") gives even more points.
4.  **Caps the Total**: Ensures no one gets more than 50,000 points in one go!

---

## 📂 Key Files & What They Do

### `App.java`
The **Starting Switch**. This is the literal entry point of the code. If you were starting a car, this would be the ignition.

### `QuoteServiceVerticle.java`
The **Main Brain**. This file handles all the incoming requests. It listens for people asking for quotes, coordinates with other "mini-services" (like the currency converter), and sends back the final answer.

### `logic/CalculationEngine.java`
The **Math Guru**. This contains the actual formulas for the points. It doesn't care about the internet or servers; it only cares about doing the math correctly.

---

## 🚀 How to Run (No Tech Help Needed!)

1.  **Open a Terminal**: (On Mac, press `Command + Space` and type "Terminal").
2.  **Go to the Folder**:
    Use the `cd` command to navigate to where you saved this folder.
3.  **Start the Service**:
    Copy and paste this command:
    ```bash
    mvn compile test-compile exec:java
    ```
4.  **See it Work**:
    Once the screen says "Quote Service is RUNNING on http://localhost:8080", open another terminal window and paste this to "ask" for a quote:
    ```bash
    curl -X POST http://localhost:8080/v1/points/quote \
      -H "Content-Type: application/json" \
      -d '{"fareAmount": 1000, "currency": "AED", "customerTier": "GOLD", "promoCode": "SUMMER25"}'
    ```

---

## ✅ How we know it's working
We have a automated testing suite. To run all the "checks" at once, simply run:
```bash
mvn test
```
If you see **BUILD SUCCESS**, everything is perfect!
