#!/usr/bin/env bash
# Replicates CounterSpec "reachesExactly300WithIfVersionRetry" over HTTP.
#
# 3 clients each perform 100 successful counter increments on the same key
# using a read → increment → PUT with ifVersion retry loop.
# On a 409 conflict (someone else wrote first) the client re-reads and retries.
#
# Expected final state: value=300, version=301
#
# Usage: bash counter_increment.sh [host] [key]

HOST="${1:-http://localhost:9000}"
KEY="${2:-counter}"
OPS_PER_CLIENT=100
MAX_RETRIES=1000

seed_counter() {
  local resp
  resp=$(curl -s -w "\n%{http_code}" \
    -X PUT \
    -H "Content-Type: application/json" \
    -d '0' \
    "${HOST}/kv/${KEY}")
  local body status
  body=$(echo "$resp" | head -n -1)
  status=$(echo "$resp" | tail -n 1)
  if [[ "$status" != "200" ]]; then
    echo "[seed] ERROR: got HTTP $status — $body" >&2
    exit 1
  fi
  echo "[seed] counter initialised to 0"
}

run_client() {
  local client_id=$1
  local successful=0
  local retries=0

  while (( successful < OPS_PER_CLIENT )); do
    # --- READ current value + version ---
    local read_resp read_body read_status
    read_resp=$(curl -s -w "\n%{http_code}" "${HOST}/kv/${KEY}")
    read_body=$(echo "$read_resp" | head -n -1)
    read_status=$(echo "$read_resp" | tail -n 1)

    if [[ "$read_status" != "200" ]]; then
      echo "[client $client_id] READ failed (HTTP $read_status): $read_body" >&2
      exit 1
    fi

    local current_value current_version
    current_value=$(echo "$read_body" | grep -o '"value":[^,}]*' | grep -o '[0-9-]*')
    current_version=$(echo "$read_body" | grep -o '"version":[^,}]*' | grep -o '[0-9]*')

    local new_value=$(( current_value + 1 ))

    # --- WRITE with ifVersion guard ---
    local write_resp write_body write_status
    write_resp=$(curl -s -w "\n%{http_code}" \
      -X PUT \
      -H "Content-Type: application/json" \
      -d "$new_value" \
      "${HOST}/kv/${KEY}?ifVersion=${current_version}")
    write_body=$(echo "$write_resp" | head -n -1)
    write_status=$(echo "$write_resp" | tail -n 1)

    if [[ "$write_status" == "200" ]]; then
      (( successful++ ))
    elif [[ "$write_status" == "409" ]]; then
      (( retries++ ))
      if (( retries > MAX_RETRIES )); then
        echo "[client $client_id] ERROR: retry cap ($MAX_RETRIES) exceeded — possible livelock" >&2
        exit 1
      fi
      # conflict — re-read and retry (no sleep: tight retry mirrors CounterSpec)
    else
      echo "[client $client_id] WRITE unexpected HTTP $write_status: $write_body" >&2
      exit 1
    fi
  done

  echo "[client $client_id] done — $successful increments, $retries retries"
}

export -f run_client
export HOST KEY OPS_PER_CLIENT MAX_RETRIES

# --- Seed ---
seed_counter

# --- Run 3 clients concurrently ---
echo "Starting 3 concurrent clients, $OPS_PER_CLIENT increments each"
echo "Target: PUT ${HOST}/kv/${KEY}?ifVersion=<version>"
echo "---"

pids=()
for c in 1 2 3; do
  run_client "$c" &
  pids+=($!)
done

for pid in "${pids[@]}"; do
  wait "$pid" || exit 1
done

echo "---"

# --- Verify final state ---
final=$(curl -s "${HOST}/kv/${KEY}")
final_value=$(echo "$final" | grep -o '"value":[^,}]*' | grep -o '[0-9-]*')
final_version=$(echo "$final" | grep -o '"version":[^,}]*' | grep -o '[0-9]*')

echo "Final state: value=$final_value  version=$final_version"

if [[ "$final_value" == "300" && "$final_version" == "301" ]]; then
  echo "PASS — value=300, version=301"
else
  echo "FAIL — expected value=300 version=301, got value=$final_value version=$final_version"
  exit 1
fi
