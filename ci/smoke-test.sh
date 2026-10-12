#!/usr/bin/env bash
# End-to-end check of a running crochet_back instance: CSRF, register, session, logout, login,
# pattern creation and progress. Usage: ci/smoke-test.sh <base-url>
# Extra curl options can be given in CURL_OPTS (e.g. CURL_OPTS=-k for a local certificate).
set -euo pipefail

BASE="${1:?usage: smoke-test.sh <base-url>}"
read -r -a EXTRA <<< "${CURL_OPTS:-}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
JAR="$WORK/cookies"
BODY="$WORK/body"
EMAIL="smoke-$(date +%s)-$RANDOM@example.com"
PASSWORD="smoke-test-password"
touch "$JAR"

fail() {
    echo "FAIL: $1" >&2
    [ -s "$BODY" ] && cat "$BODY" >&2 && echo >&2
    exit 1
}

xsrf() {
    awk '$6 == "XSRF-TOKEN" { token = $7 } END { print token }' "$JAR"
}

# call <method> <path> <expected status> [json body]
call() {
    local method="$1" path="$2" expected="$3" data="${4:-}" status
    local args=(-s "${EXTRA[@]}" --max-time 30 -o "$BODY" -w '%{http_code}' -b "$JAR" -c "$JAR" -X "$method"
        -H "X-XSRF-TOKEN: $(xsrf)")
    if [ -n "$data" ]; then
        args+=(-H 'Content-Type: application/json' --data "$data")
    fi
    status="$(curl "${args[@]}" "$BASE$path")" || fail "$method $path: no response"
    [ "$status" = "$expected" ] || fail "$method $path: expected $expected, got $status"
    echo "ok   $method $path -> $status"
}

# wait up to 60 s for the instance to answer
deadline=$((SECONDS + 60))
until [ "$(curl -s "${EXTRA[@]}" --max-time 5 -o /dev/null -w '%{http_code}' "$BASE/api/auth/csrf")" = 204 ] \
        || [ "$SECONDS" -ge "$deadline" ]; do
    sleep 1
done

CREDENTIALS="{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}"
call GET  /api/auth/csrf      204
call POST /api/auth/register  201 "$CREDENTIALS"
call GET  /api/me             200
grep -q "\"email\":\"$EMAIL\"" "$BODY" || fail "/api/me does not return the registered email"
call POST /api/auth/logout    204
call GET  /api/me             401
call POST /api/auth/login     200 "$CREDENTIALS"
call GET  /api/me             200
call POST /api/patterns       201 '{"title":"Smoke","steps":[{"title":"Corps","rows":[{"number":"1","value":"6ms (6)"},{"number":"2","value":"6aug (12)"}]}]}'
ID="$(grep -o '"id":[0-9]*' "$BODY" | head -1 | cut -d: -f2)"
[ -n "$ID" ] || fail "no pattern id in the creation response"
call PUT  "/api/patterns/$ID/progress" 200 '{"status":"IN_PROGRESS","stepIndex":0,"rowIndex":1}'
call GET  "/api/patterns/$ID" 200
grep -q '"status":"IN_PROGRESS"' "$BODY" || fail "the pattern does not show its progress"
call GET  /api/patterns       200
echo "Smoke test passed against $BASE"
