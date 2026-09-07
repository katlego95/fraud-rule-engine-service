// What one request can ask the service to do.
//
// POST /decisions/batch takes List<TransactionEvent> with no size constraint. The list is decided
// sequentially, each item in its own database transaction, and nothing is returned until the last
// one finishes. This measures how long one caller can hold one thread.
//
//   k6 run -e SIZE=100 perf/k6/batch.js
//   k6 run -e SIZE=10000 perf/k6/batch.js

import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8080';
const SIZE = Number(__ENV.SIZE || 100);

export const options = {
    // One user, one request at a time. Concurrency is not the question here; the question is what
    // a single request costs.
    vus: 1,
    iterations: 3,
    // No threshold. This run is not expected to pass or fail — it is expected to produce a number
    // that says how large a request the service will currently accept without complaint.
};

function uuid() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
        const r = (Math.random() * 16) | 0;
        return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
    });
}

export default function () {
    const events = [];
    for (let i = 0; i < SIZE; i++) {
        events.push({
            eventId: uuid(),
            occurredAt: new Date(Date.now() + i * 1000).toISOString(),
            accountId: `acct-batch-${__ITER}`,
            cardToken: `40009999${String(__ITER).padStart(8, '0')}`,
            amount: (Math.random() * 5000).toFixed(2),
            currency: 'ZAR',
            merchantId: `m-${i % 50}`,
            merchantCategoryCode: '5411',
            merchantCountry: 'ZA',
            channel: 'CARD_PRESENT',
        });
    }

    const res = http.post(`${BASE}/api/v1/decisions/batch`, JSON.stringify(events), {
        headers: { 'Content-Type': 'application/json' },
        timeout: '600s',
    });

    console.log(`size=${SIZE} status=${res.status} duration=${Math.round(res.timings.duration)}ms`);

    check(res, {
        'accepted or refused, not hung': (r) => r.status !== 0,
    });
}
