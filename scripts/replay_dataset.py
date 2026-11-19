#!/usr/bin/env python3
"""
replay_dataset.py — Stream and Replay Fraud Datasets into SentinelPay

Supports:
  1. Kaggle PaySim Dataset (Financial Fraud Detection)
  2. Kaggle Indian UPI Transaction Datasets
  3. Built-in Realistic UPI Dataset Generator (--demo / --generate-sample)

Usage:
  py scripts/replay_dataset.py --demo                      # Streams 25 sample transactions with various fraud patterns
  py scripts/replay_dataset.py --file my_upi_dataset.csv   # Streams a custom CSV dataset
  py scripts/replay_dataset.py --paysim PS_20174392719_1491205739460_log.csv --limit 100
"""

import sys
import os
import csv
import json
import time
import uuid
import random
import argparse
import urllib.request
import urllib.error

ENDPOINT = "http://localhost:8081/api/transactions"

CATEGORIES = ["GROCERY", "ELECTRONICS", "RESTAURANT", "ENTERTAINMENT", "TRAVEL", "UTILITIES"]
DEVICES = ["iPhone-15", "Samsung-Galaxy-S24", "Pixel-8", "OnePlus-12", "MacBook-Chrome", "Windows-Edge"]
INDIAN_CITIES = [
    "Timezone: Asia/Calcutta",
    "Geo: 17.3850, 78.4867 (Hyderabad)",
    "Geo: 19.0760, 72.8777 (Mumbai)",
    "Geo: 12.9716, 77.5946 (Bangalore)",
    "Geo: 28.6139, 77.2090 (Delhi)"
]

def send_transaction(tx):
    """Submits a single transaction to SentinelPay."""
    req = urllib.request.Request(
        ENDPOINT,
        data=json.dumps(tx).encode("utf-8"),
        headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status == 202
    except Exception as e:
        print(f"  [ERROR] Failed to send {tx.get('transactionId')}: {e}")
        return False

def poll_verdict(tx_id, delay=0.6):
    """Polls fraud detection verdict."""
    time.sleep(delay)
    try:
        with urllib.request.urlopen(f"{ENDPOINT}/{tx_id}", timeout=5) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return data.get("fraudStatus", "PENDING"), data.get("fraudDetectionLayer", "-"), data.get("fraudReason", "")
    except Exception:
        return "UNKNOWN", "-", "Failed to poll"

def generate_synthetic_upi_dataset(count=25):
    """Generates realistic Indian UPI transactions containing legitimate, typosquatted, and velocity fraud."""
    records = []
    
    # Normal user profiles
    users = ["karthik@gmail.com", "arjun.sharma@upi", "priya.nair@okhdfc", "vikram.singh@icici"]
    known_merchants = ["swiggy@icici", "zomato@hdfc", "amazonpay@apl", "uber.india@axis", "flipkart@axis"]
    
    # Scam / Fraud targets for testing
    typo_targets = ["amazonn@upi", "phonpe.care@ybl", "paytmm.support@paytm"]
    phishing_targets = ["amazon_refund@upi", "sbi_kyc_verification@upi", "googlepay_reward_claim@okhdfc"]
    
    for i in range(count):
        tx_id = f"DATASET-UPI-{uuid.uuid4().hex[:8].upper()}"
        user = random.choice(users)
        
        # Inject deliberate fraud scenarios at a ~35% rate
        scenario = random.choices(
            ["LEGITIMATE", "TYPOSQUATTING", "PHISHING", "HIGH_AMOUNT", "REMOTE_LOCATION"],
            weights=[65, 12, 10, 8, 5]
        )[0]
        
        if scenario == "TYPOSQUATTING":
            payee = random.choice(typo_targets)
            amount = round(random.uniform(500, 3000), 2)
            location = "Timezone: Asia/Calcutta"
            device = "Samsung-Galaxy-S24"
        elif scenario == "PHISHING":
            payee = random.choice(phishing_targets)
            amount = round(random.uniform(1000, 8000), 2)
            location = "Timezone: Asia/Calcutta"
            device = "iPhone-15"
        elif scenario == "HIGH_AMOUNT":
            payee = random.choice(known_merchants)
            amount = round(random.uniform(85000, 150000), 2)  # High anomaly amount
            location = "Timezone: Asia/Calcutta"
            device = "Samsung-Galaxy-S24"
        elif scenario == "REMOTE_LOCATION":
            payee = random.choice(known_merchants)
            amount = round(random.uniform(200, 1500), 2)
            location = "Geo: 51.5074, -0.1278 (London, UK)"   # Impossible location jump
            device = "Unknown-Linux-Curl"
        else:
            payee = random.choice(known_merchants)
            amount = round(random.uniform(49, 2500), 2)
            location = "Timezone: Asia/Calcutta"
            device = "Samsung-Galaxy-S24"
            
        records.append({
            "transactionId": tx_id,
            "userId": user,
            "amount": amount,
            "location": location,
            "device": device,
            "merchantCategory": random.choice(CATEGORIES),
            "payeeUpiId": payee,
            "expectedScenario": scenario
        })
        
    return records

def replay_paysim(filepath, limit=50):
    """Converts and replays PaySim CSV records into SentinelPay format."""
    print(f"Loading PaySim dataset from {filepath} (limit: {limit})...")
    records = []
    with open(filepath, mode="r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for i, row in enumerate(reader):
            if i >= limit:
                break
            tx_id = f"PAYSIM-{row.get('step', '0')}-{uuid.uuid4().hex[:6].upper()}"
            orig = row.get("nameOrig", "C1000") + "@upi"
            dest = row.get("nameDest", "M1000") + "@upi"
            amount = float(row.get("amount", 0.0))
            category = "TRANSFER" if row.get("type") in ["TRANSFER", "CASH_OUT"] else "PAYMENT"
            
            records.append({
                "transactionId": tx_id,
                "userId": orig,
                "amount": round(amount, 2),
                "location": "Timezone: Asia/Calcutta",
                "device": "Mobile-Device",
                "merchantCategory": category,
                "payeeUpiId": dest,
                "expectedScenario": "FRAUD" if row.get("isFraud") == "1" else "LEGITIMATE"
            })
    return records

def main():
    parser = argparse.ArgumentParser(description="Replay fraud datasets into SentinelPay")
    parser.add_argument("--demo", action="store_true", help="Generate and replay realistic Indian UPI synthetic data")
    parser.add_argument("--count", type=int, default=20, help="Number of demo transactions to stream")
    parser.add_argument("--file", type=str, help="Path to custom CSV dataset")
    parser.add_argument("--paysim", type=str, help="Path to PaySim CSV dataset")
    parser.add_argument("--limit", type=int, default=50, help="Max records to replay from file")
    args = parser.parse_args()

    print("=" * 70)
    print("  SentinelPay Dataset Streamer & Benchmark Replayer")
    print("=" * 70)

    if args.paysim:
        records = replay_paysim(args.paysim, limit=args.limit)
    elif args.file:
        print(f"Reading custom CSV dataset from {args.file}...")
        # Auto-parse standard CSV columns
        records = []
        with open(args.file, mode="r", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for i, row in enumerate(reader):
                if i >= args.limit:
                    break
                records.append({
                    "transactionId": row.get("transactionId") or f"TX-{uuid.uuid4().hex[:8]}",
                    "userId": row.get("userId") or row.get("sender_upi") or "karthik@gmail.com",
                    "amount": float(row.get("amount") or row.get("amount_inr") or 500.0),
                    "location": row.get("location") or "Timezone: Asia/Calcutta",
                    "device": row.get("device") or "Samsung-Galaxy-S24",
                    "merchantCategory": row.get("merchantCategory") or "GROCERY",
                    "payeeUpiId": row.get("payeeUpiId") or row.get("receiver_upi") or "swiggy@icici",
                })
    else:
        # Default to synthetic benchmark demo
        print(f"Generating {args.count} realistic UPI benchmark transactions...")
        records = generate_synthetic_upi_dataset(count=args.count)

    print(f"\nStreaming {len(records)} transactions to {ENDPOINT}...\n")
    safe_cnt, fraud_cnt, review_cnt = 0, 0, 0

    print(f"{'TX ID':<22} | {'PAYEE / TYPE':<26} | {'AMOUNT':<10} | {'VERDICT':<10} | {'LAYER / REASON'}")
    print("-" * 90)

    for r in records:
        sent = send_transaction(r)
        if not sent:
            continue
        
        status, layer, reason = poll_verdict(r["transactionId"])
        
        if status == "FRAUD":
            fraud_cnt += 1
            verdict_badge = "[FRAUD]"
        elif status == "REVIEW":
            review_cnt += 1
            verdict_badge = "[REVIEW]"
        else:
            safe_cnt += 1
            verdict_badge = "[SAFE]"

        short_reason = (reason[:35] + "...") if len(reason) > 35 else reason
        print(f"{r['transactionId']:<22} | {r.get('payeeUpiId', ''):<26} | Rs.{r['amount']:<7.2f} | {verdict_badge:<10} | {layer} ({short_reason})")
        time.sleep(0.3)

    print("-" * 90)
    print(f"\nReplay Complete! Summary:")
    print(f"  Total Processed: {len(records)}")
    print(f"  Safe:            {safe_cnt}")
    print(f"  Blocked (Fraud): {fraud_cnt}")
    print(f"  Review Queue:    {review_cnt}")
    print(f"\nView full telemetry and charts in Admin Portal: http://localhost:3001/admin")

if __name__ == "__main__":
    main()
