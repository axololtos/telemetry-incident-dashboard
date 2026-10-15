#!/usr/bin/env bash
# Replays NDJSON files from sample-data/ into the app-logs topic (needs `docker compose up -d`).
# Usage: scripts/send-sample-logs.sh [file ...]   (default: every file in sample-data/, in order)
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."
files=("$@")
[ ${#files[@]} -eq 0 ] && files=(sample-data/*.ndjson)
cat "${files[@]}" | docker exec -i telemetry-kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic app-logs
echo "sent: ${files[*]}"
