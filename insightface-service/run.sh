#!/usr/bin/env bash
# Start the InsightFace sidecar (Linux / macOS).
#
# First time only:
#   python3 -m venv .venv
#   source .venv/bin/activate
#   pip install -r requirements.txt
#
# Then just run:  ./run.sh
set -euo pipefail
cd "$(dirname "$0")"

if [ -f ".venv/bin/activate" ]; then
    # shellcheck disable=SC1091
    source .venv/bin/activate
fi

export INSIGHTFACE_MODEL="${INSIGHTFACE_MODEL:-buffalo_l}"

exec uvicorn app:app --host 127.0.0.1 --port 8099
