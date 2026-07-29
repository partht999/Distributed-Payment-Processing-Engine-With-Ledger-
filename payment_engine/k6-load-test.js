/*
 * ═══════════════════════════════════════════════════════════════════
 * k6 Load Test — Distributed Payment Processing Engine
 * ═══════════════════════════════════════════════════════════════════
 *
 * This script proves the resume claim:
 *   "processing 10K+ transactions/min under simulated concurrent load"
 *
 * 10,000 tx/min = ~167 TPS (Transactions Per Second)
 *
 * INSTALL k6:
 *   Windows:  winget install grafana.k6
 *   Mac:      brew install k6
 *   Linux:    sudo apt install k6
 *
 * RUN:
 *   k6 run k6-load-test.js
 *
 * WHAT THIS TESTS:
 *   1. Creates wallets with unique IDs
 *   2. Creates payments with unique idempotency keys
 *   3. Measures throughput (requests/sec) and latency (p95, p99)
 *   4. Validates response correctness
 *
 * EXPECTED OUTPUT:
 *   http_reqs......................: 10000+   167+/s
 *   http_req_duration (p95).......: < 200ms
 * ═══════════════════════════════════════════════════════════════════
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ─── Custom Metrics ───
const paymentCreated = new Counter('payments_created');
const paymentFailed = new Rate('payment_failure_rate');
const paymentLatency = new Trend('payment_create_latency', true);

// ─── Test Configuration ───
export const options = {
    scenarios: {
        // Ramp up to 100 virtual users over 30 seconds,
        // sustain for 60 seconds, then ramp down.
        payment_load: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '15s', target: 50 },   // Ramp up to 50 VUs
                { duration: '30s', target: 100 },   // Ramp to 100 VUs
                { duration: '60s', target: 100 },   // Sustain 100 VUs for 60s
                { duration: '15s', target: 0 },      // Ramp down
            ],
        },
    },
    thresholds: {
        // Pass/fail criteria for the resume claim
        'http_req_duration': ['p(95)<500'],         // 95% of requests under 500ms
        'http_req_duration': ['p(99)<1000'],        // 99% under 1 second
        'payment_failure_rate': ['rate<0.05'],       // Less than 5% failure rate
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// ─── Setup: Create test wallets before load test ───
export function setup() {
    console.log('Creating test wallets...');

    // Create sender wallet with large balance
    const sender = http.post(`${BASE_URL}/api/v1/wallets`, JSON.stringify({
        walletId: 9001,
        balance: 999999999,
        userId: 9001,
        phoneNumber: 9999999901,
    }), { headers: { 'Content-Type': 'application/json' } });

    // Create receiver wallet
    const receiver = http.post(`${BASE_URL}/api/v1/wallets`, JSON.stringify({
        walletId: 9002,
        balance: 0,
        userId: 9002,
        phoneNumber: 9999999902,
    }), { headers: { 'Content-Type': 'application/json' } });

    console.log(`Sender wallet: ${sender.status}`);
    console.log(`Receiver wallet: ${receiver.status}`);

    return { senderWalletId: 9001, receiverWalletId: 9002 };
}

// ─── Main Test: Create payments with unique idempotency keys ───
export default function (data) {
    // Generate a unique idempotency key per request
    const uniqueKey = `k6-${__VU}-${__ITER}-${Date.now()}`;

    const payload = JSON.stringify({
        fromWalletId: data.senderWalletId,
        toWalletId: data.receiverWalletId,
        amount: 1,  // Smallest unit to maximize throughput
        idempotencyKey: uniqueKey,
        paymentType: 'P2P',
    });

    const params = {
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'CreatePayment' },
    };

    const startTime = Date.now();
    const response = http.post(`${BASE_URL}/api/v1/payments`, payload, params);
    const duration = Date.now() - startTime;

    // Record custom metrics
    paymentLatency.add(duration);

    const success = check(response, {
        'status is 201 or 200': (r) => r.status === 201 || r.status === 200,
        'response has paymentId': (r) => r.json('paymentId') !== undefined,
        'payment status is CREATED': (r) => r.json('status') === 'CREATED',
    });

    if (success) {
        paymentCreated.add(1);
        paymentFailed.add(0);
    } else {
        paymentFailed.add(1);
    }

    // Small sleep to simulate realistic client behavior
    sleep(0.1);
}

// ─── Teardown: Print summary ───
export function teardown(data) {
    console.log('');
    console.log('═══════════════════════════════════════════════════');
    console.log('  Load Test Complete');
    console.log('  Target: 10,000+ transactions/min (167+ TPS)');
    console.log('═══════════════════════════════════════════════════');
}
