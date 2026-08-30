#!/usr/bin/env bash
# Simulates concurrent PUT /kv/<key> requests from multiple clients.
# Usage: bash concurrent_put.sh [host] [key] [concurrency]

HOST="${1:-http://localhost:9000}"
KEY="${2:-testkey}"
CONCURRENCY="${3:-3}"
REQUESTS_PER_CLIENT=100

PAYLOAD='{"value": "hello"}'

run_client() {
  local client_id=$1
  local success=0
  local fail=0

  for i in $(seq 1 $REQUESTS_PER_CLIENT); do
    response=$(curl -s -o /dev/null -w "%{http_code}" \
      -X PUT \
      -H "Content-Type: application/json" \
      -d "$PAYLOAD" \
      "${HOST}/kv/${KEY}")

    if [[ "$response" =~ ^2 ]]; then
      ((success++))
    else
      ((fail++))
    fi
  done

  echo "[client $client_id] done — success: $success, non-2xx: $fail"
}

export -f run_client
export HOST KEY PAYLOAD REQUESTS_PER_CLIENT

echo "Starting $CONCURRENCY concurrent clients, $REQUESTS_PER_CLIENT requests each"
echo "Target: PUT ${HOST}/kv/${KEY}"
echo "---"

pids=()
for c in $(seq 1 $CONCURRENCY); do
  run_client "$c" &
  pids+=($!)
done

for pid in "${pids[@]}"; do
  wait "$pid"
done

echo "---"
echo "All clients finished."
