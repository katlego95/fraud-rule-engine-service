// At what concurrency does p99 leave the 150ms budget?
//
// The ramp run reports one p99 across every stage, which cannot answer that. This holds a fixed
// number of users long enough for the number to settle, so each level is measured rather than
// passed through.
//
//   k6 run -e VUS=50 perf/k6/knee.js
//
// Run once per level and record each. Reset the database between levels — a run writes hundreds
// of thousands of events and the velocity windows scan them.

import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 50);

export const options = {
    scenarios: {
        held: {
            executor: 'constant-vus',
            vus: VUS,
            duration: '90s',
            gracefulStop: '10s',
        },
    },
    thresholds: {
        // Not a pass/fail gate here. The point is to read the number at each level, so the
        // threshold is recorded and the run is allowed to finish either way.
        'http_req_duration': ['p(99)<150'],
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
    const payload = JSON.stringify({
        eventId: uuid(),
        occurredAt: new Date().toISOString(),
        accountId: `acct-load-${__VU % 200}`,
        cardToken: `4000${String(__VU % 200).padStart(4, '0')}00001111`,
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

    check(res, { 'decided': (r) => r.status === 200 });
}
