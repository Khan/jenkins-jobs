#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNNER="$ROOT/scripts/merge_branches_runner"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_eq() {
  local got="$1"
  local want="$2"
  local msg="$3"
  [[ "$got" == "$want" ]] || fail "$msg (got='$got', want='$want')"
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  local msg="$3"
  [[ "$haystack" == *"$needle"* ]] || fail "$msg (missing '$needle')"
}

create_remote_fixture() {
  local dir="$1"
  git init --bare --initial-branch=master "$dir/origin.git" >/dev/null
  git clone -q "$dir/origin.git" "$dir/seed" >/dev/null
  (
    cd "$dir/seed"
    git config user.name tester
    git config user.email tester@example.com
    echo "base" > app.txt
    git add app.txt
    git commit -m "base" >/dev/null
    git push origin HEAD:master >/dev/null
  )
}

run_runner() {
  local dir="$1"
  local revisions="$2"
  local commit_id="$3"
  local description="$4"
  local out_file="$5"
  local log_file="$6"

  (
    cd "$dir"
    KA_WEBAPP_REPO_URL="$dir/origin.git" \
    GAE_VERSION_NAME="test-gae-version" \
    MERGE_BRANCHES_API_LOG="$log_file" \
    "$RUNNER" \
      --git-revisions "$revisions" \
      --commit-id "$commit_id" \
      --revision-description "$description" \
      >"$out_file" 2>"$out_file.err"
  )
}

test_happy_path_merge() {
  local dir
  dir="$(mktemp -d /tmp/merge-branches-happy.XXXXXX)"
  create_remote_fixture "$dir"

  (
    cd "$dir/seed"
    git checkout -b feature >/dev/null
    echo "feature" > feature.txt
    git add feature.txt
    git commit -m "feature change" >/dev/null
    git push origin feature >/dev/null

    git checkout master >/dev/null
    echo "master" > master.txt
    git add master.txt
    git commit -m "master change" >/dev/null
    git push origin master >/dev/null
  )

  : > "$dir/api.ndjson"
  run_runner "$dir" "master + feature" "c1" "happy path" "$dir/out.txt" "$dir/api.ndjson"

  local sha
  sha="$(tail -n 1 "$dir/out.txt" | tr -d '\n')"
  [[ "$sha" =~ ^[0-9a-f]{40}$ ]] || fail "runner did not output a sha"

  git clone -q "$dir/origin.git" "$dir/verify" >/dev/null
  local parent_count
  parent_count="$(cd "$dir/verify" && git rev-list --parents -n 1 "$sha" | awk '{print NF-1}')"
  assert_eq "$parent_count" "2" "happy path should produce a merge commit"

  local tag_count
  tag_count="$(cd "$dir/verify" && git tag --points-at "$sha" | grep -c '^buildmaster-c1-' || true)"
  assert_eq "$tag_count" "1" "merge result should be tagged"

  local log
  log="$(cat "$dir/api.ndjson")"
  assert_contains "$log" '"method":"notifyMergeResult"' "missing notification call"
  assert_contains "$log" '"status":"success"' "missing success status"
  assert_contains "$log" '"gae_version_name":"test-gae-version"' "missing gae version"
}

test_invalid_committish_failure() {
  local dir
  dir="$(mktemp -d /tmp/merge-branches-invalid.XXXXXX)"
  create_remote_fixture "$dir"

  : > "$dir/api.ndjson"
  if run_runner "$dir" "master + does-not-exist" "c2" "invalid" "$dir/out.txt" "$dir/api.ndjson"; then
    fail "runner should fail for invalid committish"
  fi

  local err
  err="$(cat "$dir/out.txt.err")$(cat "$dir/out.txt")"
  assert_contains "$err" "Cannot find 'does-not-exist'" "invalid committish error should be clear"

  local log
  log="$(cat "$dir/api.ndjson")"
  assert_contains "$log" '"status":"failed"' "invalid committish should emit failed notification"
  assert_contains "$log" '"sha1":null' "failed notification should have null sha"
}

test_merge_conflict_failure() {
  local dir
  dir="$(mktemp -d /tmp/merge-branches-conflict.XXXXXX)"
  create_remote_fixture "$dir"

  (
    cd "$dir/seed"
    git checkout -b feature >/dev/null
    echo "feature-value" > conflict.txt
    git add conflict.txt
    git commit -m "feature adds conflict file" >/dev/null
    git push origin feature >/dev/null

    git checkout master >/dev/null
    echo "master-value" > conflict.txt
    git add conflict.txt
    git commit -m "master adds conflict file" >/dev/null
    git push origin master >/dev/null
  )

  : > "$dir/api.ndjson"
  if run_runner "$dir" "master + feature" "c3" "conflict" "$dir/out.txt" "$dir/api.ndjson"; then
    fail "runner should fail on merge conflict"
  fi

  local err
  err="$(cat "$dir/out.txt.err")$(cat "$dir/out.txt")"
  assert_contains "$err" "CONFLICT" "merge conflict should surface git conflict output"

  local log
  log="$(cat "$dir/api.ndjson")"
  assert_contains "$log" '"status":"failed"' "merge conflict should emit failed notification"
}

main() {
  test_happy_path_merge
  test_invalid_committish_failure
  test_merge_conflict_failure
  echo "PASS: merge_branches integration tests"
}

main "$@"
