#!/bin/bash
# ═══════════════════════════════════════════════════════════════════
# 🎯 FULL E2E DEMO SCRIPT — Distributed Payment Processing Engine
# ═══════════════════════════════════════════════════════════════════
#
# Run this AFTER:
#   1. docker compose up -d   (PostgreSQL + Redis + Kafka)
#   2. ./mvnw spring-boot:run (Spring Boot app on port 8080)
#
# This script tests the ENTIRE payment pipeline:
#   Wallet → Payment → Idempotency → Process → Ledger → Kafka → Webhook
# ═══════════════════════════════════════════════════════════════════

BASE_URL="http://localhost:8080/api/v1"
PASS=0
FAIL=0

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

check() {
    if [ $1 -eq 0 ]; then
        echo -e "  ${GREEN}✅ PASS${NC}: $2"
        PASS=$((PASS + 1))
    else
        echo -e "  ${RED}❌ FAIL${NC}: $2"
        FAIL=$((FAIL + 1))
    fi
}

echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}  💳 Distributed Payment Engine — Full E2E Test Suite  ${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════${NC}"
echo ""

# ─── STEP 1: Health Check ───
echo -e "${YELLOW}[1/8] Health Check${NC}"
HEALTH=$(curl -s -o /dev/null -w "%{http_code}" $BASE_URL/health)
check $([ "$HEALTH" = "200" ] && echo 0 || echo 1) "Health endpoint returns 200"

# ─── STEP 2: Create Wallets ───
echo -e "\n${YELLOW}[2/8] Create Wallets${NC}"
W1=$(curl -s -X POST $BASE_URL/wallets \
    -H "Content-Type: application/json" \
    -d '{"walletId":1,"balance":10000,"userId":101,"phoneNumber":9876543210}')
echo "  Wallet 1: $W1"
check $(echo $W1 | grep -q "walletId" && echo 0 || echo 1) "Wallet 1 created with ₹10,000"

W2=$(curl -s -X POST $BASE_URL/wallets \
    -H "Content-Type: application/json" \
    -d '{"walletId":2,"balance":5000,"userId":102,"phoneNumber":9876543211}')
echo "  Wallet 2: $W2"
check $(echo $W2 | grep -q "walletId" && echo 0 || echo 1) "Wallet 2 created with ₹5,000"

# ─── STEP 3: Create Payment (with Idempotency Key) ───
echo -e "\n${YELLOW}[3/8] Create Payment (Idempotent)${NC}"
P1=$(curl -s -X POST $BASE_URL/payments \
    -H "Content-Type: application/json" \
    -d '{"fromWalletId":1,"toWalletId":2,"amount":500,"idempotencyKey":"demo-txn-001","paymentType":"P2P"}')
echo "  Payment: $P1"
PAYMENT_ID=$(echo $P1 | grep -o '"paymentId":[0-9]*' | head -1 | grep -o '[0-9]*')
check $(echo $P1 | grep -q "CREATED" && echo 0 || echo 1) "Payment created with status CREATED"

# ─── STEP 4: Test Idempotency (Same Key = Same Payment) ───
echo -e "\n${YELLOW}[4/8] Test Idempotency — Duplicate Key${NC}"
P2=$(curl -s -X POST $BASE_URL/payments \
    -H "Content-Type: application/json" \
    -d '{"fromWalletId":1,"toWalletId":2,"amount":500,"idempotencyKey":"demo-txn-001","paymentType":"P2P"}')
PAYMENT_ID_2=$(echo $P2 | grep -o '"paymentId":[0-9]*' | head -1 | grep -o '[0-9]*')
check $([ "$PAYMENT_ID" = "$PAYMENT_ID_2" ] && echo 0 || echo 1) "Same idempotency key returns SAME payment (no duplicate charge)"

# ─── STEP 5: Process Payment ───
echo -e "\n${YELLOW}[5/8] Process Payment${NC}"
PROCESS=$(curl -s -X POST $BASE_URL/payments/$PAYMENT_ID/process)
echo "  Result: $PROCESS"
check $(echo $PROCESS | grep -q "CAPTURED" && echo 0 || echo 1) "Payment processed → status CAPTURED"

# ─── STEP 6: Verify Balances ───
echo -e "\n${YELLOW}[6/8] Verify Balances (Ledger-Derived)${NC}"
BAL1=$(curl -s $BASE_URL/wallets/1/balance)
BAL2=$(curl -s $BASE_URL/wallets/2/balance)
echo "  Wallet 1 balance: $BAL1"
echo "  Wallet 2 balance: $BAL2"
check $(echo $BAL1 | grep -q "9500" && echo 0 || echo 1) "Wallet 1: ₹10,000 - ₹500 = ₹9,500"
check $(echo $BAL2 | grep -q "5500" && echo 0 || echo 1) "Wallet 2: ₹5,000 + ₹500 = ₹5,500"

# ─── STEP 7: Refund Payment ───
echo -e "\n${YELLOW}[7/8] Refund Payment${NC}"
REFUND=$(curl -s -X POST $BASE_URL/payments/$PAYMENT_ID/refund)
echo "  Result: $REFUND"
check $(echo $REFUND | grep -q "REFUNDED" && echo 0 || echo 1) "Payment refunded → status REFUNDED"

# Verify balances restored
BAL1_AFTER=$(curl -s $BASE_URL/wallets/1/balance)
BAL2_AFTER=$(curl -s $BASE_URL/wallets/2/balance)
echo "  Wallet 1 after refund: $BAL1_AFTER"
echo "  Wallet 2 after refund: $BAL2_AFTER"
check $(echo $BAL1_AFTER | grep -q "10000" && echo 0 || echo 1) "Wallet 1 balance restored to ₹10,000"
check $(echo $BAL2_AFTER | grep -q "5000" && echo 0 || echo 1) "Wallet 2 balance restored to ₹5,000"

# ─── STEP 8: Cannot Re-Process Refunded Payment ───
echo -e "\n${YELLOW}[8/8] State Machine Guard — Cannot re-process${NC}"
REPROCESS=$(curl -s -X POST $BASE_URL/payments/$PAYMENT_ID/process)
echo "  Attempt to re-process: $REPROCESS"
check $(echo $REPROCESS | grep -qi "cannot\|invalid\|error\|illegal" && echo 0 || echo 1) "State machine blocks invalid transition (REFUNDED → CAPTURED)"

# ─── RESULTS ───
echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════════${NC}"
echo -e "  ${GREEN}PASSED: $PASS${NC}  |  ${RED}FAILED: $FAIL${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════${NC}"
echo ""

if [ $FAIL -eq 0 ]; then
    echo -e "${GREEN}🎉 ALL TESTS PASSED — System is working perfectly!${NC}"
else
    echo -e "${RED}⚠️  Some tests failed — check output above${NC}"
fi
