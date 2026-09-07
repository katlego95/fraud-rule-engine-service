#!/usr/bin/env bash
# Reads p99 at each level of concurrency, resetting between levels.
#
# The reset is the point. A run writes hundreds of thousands of events and the velocity windows
# scan them, so without it each level starts against a larger table than the last and the numbers
# measure the data rather than the concurrency.
#
#   ./perf/knee-sweep.sh              # 50 100 150 200
#   ./perf/knee-sweep.sh 25 50 75     # custom levels
#
# Results land in perf/results/. Stop the jar yourself first — this script runs it.

set -euo pipefail

LEVELS=("${@:-}")
[ -z "${LEVELS[0]}" ] && LEVELS=(50 100 150 200)

JAR=$(ls target/fraud-rule-engine-*.jar 2>/dev/null | head -1)
[ -z "$JAR" ] && { echo "No jar. Run: ./mvnw -q -DskipTests package"; exit 1; }

mkdir -p perf/results

for VUS in "${LEVELS[@]}"; do
    echo "=== $VUS VUs ==="

    docker compose down -v >/dev/null 2>&1 || true
    docker compose up -d postgres >/dev/null
    until docker compose exec -T postgres pg_isready -U fraud -d fraud >/dev/null 2>&1; do sleep 1; done

    SPRING_PROFILES_ACTIVE=demo java -jar "$JAR" > "perf/results/app-${VUS}-$(date +%s).log" 2>&1 &
    APP=$!
    trap 'kill $APP 2>/dev/null || true' EXIT

    until curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; do
        kill -0 $APP 2>/dev/null || { echo "app died, see perf/results/app-${VUS}.log"; exit 1; }
        sleep 1
    done

    RUN=1; while [ -e "perf/results/knee-${VUS}-${RUN}.txt" ]; do RUN=$((RUN+1)); done
    k6 run -e VUS="$VUS" perf/k6/knee.js 2>&1 | tee "perf/results/knee-${VUS}-${RUN}.txt"

    kill $APP 2>/dev/null || true
    wait $APP 2>/dev/null || true
    trap - EXIT
done

echo
echo "=== summary ==="
printf "%-6s %-10s %-10s %-10s\n" VUs RPS p95 p99
for VUS in "${LEVELS[@]}"; do
    F=$(ls -t perf/results/knee-${VUS}-*.txt 2>/dev/null | head -1)
    # p99 appears only inside the thresholds block, since k6's default summary stops at p95.
    RPS=$(grep 'http_reqs' "$F" | grep -o '[0-9.]*/s' | tail -1)
    P95=$(grep 'http_req_duration\.' "$F" | grep -o 'p(95)=[^ ]*' | head -1 | cut -d= -f2)
    P99=$(grep -o 'p(99)=[^ ]*' "$F" | head -1 | cut -d= -f2)
    printf "%-6s %-10s %-10s %-10s\n" "$VUS" "${RPS:-?}" "${P95:-?}" "${P99:-?}"
done
