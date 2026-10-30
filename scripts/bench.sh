#!/usr/bin/env bash
# Rough numbers from one machine, not a load test: bind POLICIES policies, time QUERIES coverage-as-of reads,
# then time one renewal run over those policies. Prints measured values only.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
POLICIES=${POLICIES:-200}
QUERIES=${QUERIES:-300}
RUN=$(openssl rand -hex 3)
M=$(token "$MANAGER_SUB")
OUT=$(mktemp -d /tmp/claimpilot-bench.XXXXXX)
trap 'rm -rf "$OUT"' EXIT

echo "binding $POLICIES policies (one customer each)..."
t0=$(date +%s%N)
for ((i = 0; i < POLICIES; i++)); do
  sub="bench-$RUN-$i@demo.example.test"
  tok=$(token "$sub")
  echo "$tok $(new_policy "$tok" health-shield "$sub")" >> "$OUT/policies"
done
t1=$(date +%s%N)
echo "  bind: $POLICIES in $(( (t1 - t0) / 1000000 )) ms sequential (token + quote + bind per policy)"

echo "coverage-as-of reads ($QUERIES, sequential, client-side curl time incl. connection)..."
mapfile -t ROWS < "$OUT/policies"
for ((i = 0; i < QUERIES; i++)); do
  read -r tok id <<< "${ROWS[$((i % ${#ROWS[@]}))]}"
  at=$(date -u -d "+$((i % 300)) days" +%Y-%m-%dT%H:%M:%SZ)
  curl -s -o /dev/null -w '%{time_total}\n' -H "Authorization: Bearer $tok" "$BASE/v1/policies/$id?validAt=$at" \
    | awk '{printf "%.2f\n", $1 * 1000}' >> "$OUT/lat"
done
echo "  p50 $(percentile "$OUT/lat" 50) ms, p95 $(percentile "$OUT/lat" 95) ms, p99 $(percentile "$OUT/lat" 99) ms"
curl -sf "$BASE/actuator/prometheus" | grep -E '^claimpilot_policy_read_seconds\{.*quantile="0.95"' \
  | awk '{printf "  server-side p95 (all reads since start): %.2f ms\n", $2 * 1000}' || true

echo "renewal run..."
# Policies bound today end in 365 days. Each business date runs once, so on a reused stack try later dates
# (still inside the window where today's policies are due) until one hasn't run yet.
for off in $(seq 365 420); do
  date=$(TZ=Asia/Kolkata date -d "+$off days" +%F)
  r=$(expect 200 "$M" POST "/v1/renewals/run?date=$date")
  [[ $(jq -r .replayed <<< "$r") == false ]] && break
done
renewed=$(jq -r .renewed <<< "$r")
ms=$(jq -r .durationMs <<< "$r")
echo "  date $date: $(jq -c '{status, renewed, paymentDue, lapsed, reminders, durationMs}' <<< "$r")"
if (( ms > 0 )); then
  echo "  throughput: $(( renewed * 60000 / ms )) renewals/min (grid 4, chunk 50, fake gateway, one app instance)"
fi
