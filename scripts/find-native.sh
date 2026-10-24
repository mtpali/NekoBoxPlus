#!/usr/bin/env bash
set -euo pipefail
for round in $(seq 1 180); do
  run_id="$(gh api --paginate "repos/$GITHUB_REPOSITORY/actions/artifacts?name=native-7e662032-arm&per_page=100" \
    --jq '[.artifacts[] | select(.expired == false)] | sort_by(.created_at) | last | .workflow_run.id // empty' | tail -n1)"
  if [ -n "$run_id" ]; then
    echo "run_id=$run_id" >> "$GITHUB_OUTPUT"
    echo "Using native source build $run_id"
    exit 0
  fi
  if (( round % 2 == 1 )); then echo "Waiting for the pinned native core build"; fi
  sleep 30
done
echo "No successful native core artifact is available" >&2
exit 1
