// Throughput and latency of the decision path under increasing concurrency.
//
// Every request carries a fresh eventId. POST /decisions is idempotent on that identifier, so a
// repeated body takes the replay path — one indexed lookup, no rule evaluation, no velocity
// queries. A load test that reuses identifiers measures a cache and reports it as an engine.
//
//   k6 run perf/k6/decisions.js
//   k6 run -e BASE=http://localhost:8080 -e PEAK=400 perf/k6/decisions.js

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const PEAK = Number(__ENV.PEAK || 200);

// Broken out because the interesting failure here is not "slow" but "refused". Overload that
// arrives as a 500 is a different finding from overload that arrives as a 429, and the run has to
// distinguish them to say which one this service does.
const serverErrors = new Counter('server_errors_5xx');
const shed = new Counter('shed_429');
const decisionLatency = new Trend('decision_latency', true);

export const options = {
    stages: [
        { duration: '30s', target: 10 },
        { duration: '1m', target: 50 },
        { duration: '1m', target: Math.round(PEAK / 2) },
        { duration: '2m', target: PEAK },
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        // The stated budget in the README. A threshold rather than a note, so the run fails
        // rather than producing a number somebody has to interpret.
        'http_req_duration{expected_response:true}': ['p(99)<150'],
        'server_errors_5xx': ['count<1'],
    },
};

const CHANNELS = ['CARD_PRESENT', 'ECOMMERCE', 'ATM'];
const MCCS = ['5411', '5812', '5999', '7995'];

function uuid() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
        const r = (Math.random() * 16) | 0;
        return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
    });
}

export default function () {
    // A spread of cards and accounts rather than one, so the velocity windows read realistic
    // history instead of one card's ever-growing tail.
    const account = `acct-load-${__VU % 200}`;
    const card = `4000${String(__VU % 200).padStart(4, '0')}00001111`;

    const payload = JSON.stringify({
        eventId: uuid(),
        occurredAt: new Date().toISOString(),
        accountId: account,
        cardToken: card,
        amount: (Math.random() * 15000).toFixed(2),
        currency: 'ZAR',
        merchantId: `m-${Math.floor(Math.random() * 50)}`,
        merchantCategoryCode: MCCS[Math.floor(Math.random() * MCCS.length)],
        merchantCountry: 'ZA',
        channel: CHANNELS[Math.floor(Math.random() * CHANNELS.length)],
    });

    const res = http.post(`${BASE}/api/v1/decisions`, payload, {
        headers: { 'Content-Type': 'application/json' },
    });

    decisionLatency.add(res.timings.duration);
    if (res.status >= 500) serverErrors.add(1);
    if (res.status === 429) shed.add(1);

    check(res, {
        'decided': (r) => r.status === 200,
        'carries a verdict': (r) => r.status === 200 && r.json('verdict') !== undefined,
    });
}
