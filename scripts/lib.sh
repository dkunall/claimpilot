# Shared helpers for demo.sh and bench.sh. Source, don't run.
BASE=${BASE:-http://127.0.0.1:8940}
OAUTH=${OAUTH:-http://127.0.0.1:8941}
MAILPIT=${MAILPIT:-http://127.0.0.1:8942}
MANAGER_SUB=${MANAGER_SUB:-manager@demo.example.test}

# The app validates the issuer as http://oauth:8080/claimpilot (its in-network name), and mock-oauth2-server
# derives the issuer from the Host header. The client_id becomes the token subject.
token() {
  curl -sf -H "Host: oauth:8080" -d grant_type=client_credentials -d "client_id=$1" -d client_secret=unused \
    -d scope=claimpilot "$OAUTH/claimpilot/token" | jq -r .access_token
}

# call TOKEN METHOD PATH [BODY] [EXTRA_CURL_ARGS...]; prints the body, then the status on its own line.
call() {
  local tok=$1 method=$2 path=$3 body=${4:-}
  shift 4 2> /dev/null || shift $#
  curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" ${tok:+-H "Authorization: Bearer $tok"} \
    -H 'Content-Type: application/json' ${body:+-d "$body"} "$@"
}

# expect STATUS TOKEN METHOD PATH [BODY] [EXTRA...]; prints the body or fails loudly.
expect() {
  local want=$1 out status
  shift
  out=$(call "$@")
  status=${out##*$'\n'}
  if [[ $status != "$want" ]]; then
    echo "FAIL: $2 $3 -> $status (wanted $want): ${out%$'\n'*}" >&2
    exit 1
  fi
  printf '%s' "${out%$'\n'*}"
}

check() {
  if [[ $2 == "$3" ]]; then echo "  ok   $1"; else echo "  FAIL $1: got '$2', wanted '$3'" >&2; exit 1; fi
}

# until_eq SECONDS WANT CMD...; reruns CMD until it prints WANT, or fails after SECONDS.
until_eq() {
  local limit=$1 want=$2 got=""
  shift 2
  for ((i = 0; i < limit; i++)); do
    got=$("$@" 2> /dev/null || true)
    [[ $got == "$want" ]] && { echo "$got"; return 0; }
    sleep 1
  done
  echo "timed out after ${limit}s; last value '$got', wanted '$want'" >&2
  return 1
}

# mails TO SUBJECT_PART; number of mails to TO whose subject contains SUBJECT_PART.
mails() {
  curl -sf -G "$MAILPIT/api/v1/search" --data-urlencode "query=to:\"$1\"" \
    | jq --arg s "$2" '[.messages[] | select(.Subject | contains($s))] | length'
}

now_iso() { date -u +%Y-%m-%dT%H:%M:%S.%3NZ; }
today_ist() { TZ=Asia/Kolkata date +%F; }

percentile() {
  sort -n "$1" | awk -v p="$2" '{a[NR]=$1} END {i=int((p/100)*NR+0.999); if (i<1) i=1; print a[i]}'
}

# A demo-only product: same as Health Shield but with no waiting period, so a claim on a policy bound a
# second ago can go straight through. Real products keep their waiting periods (see README).
demo_product() {
  curl -sf "$BASE/v1/products/health-shield" -H "Authorization: Bearer $1" \
    | jq '{productId: "health-demo", line, name: "Health Demo (no waiting period, synthetic)", coverages, riders,
           rating, claimRules: (.claimRules + {minPolicyAgeDays: 0})}'
}

# new_policy TOKEN PRODUCT EMAIL [AUTOPAY]; quotes and binds, prints the policy id.
new_policy() {
  local tok=$1 product=$2 email=$3 autopay=${4:-true} q
  q=$(expect 201 "$tok" POST /v1/quotes "$(jq -nc --arg p "$product" --arg e "$email" '{productId: $p,
      holderName: "Demo Holder", holderEmail: $e, inputs: {sumInsuredMinor: 50000000,
      numeric: {age: 34, members: 2}, categorical: {region: "TIER2"}, riders: []}}')" | jq -r .id)
  expect 201 "$tok" POST "/v1/quotes/$q/bind" "{\"paymentToken\":\"tok_visa_demo\",\"autopay\":$autopay}" \
    -H "Idempotency-Key: bind-$q" | jq -r .policyId
}
