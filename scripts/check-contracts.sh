#!/usr/bin/env bash
# Checks that contracts/proto and contracts/asyncapi/asyncapi.yaml describe the
# same events: every `x-proto-file` exists and declares the message named by
# `x-proto-message`, and every event message in contracts/proto has an entry in
# the AsyncAPI document. Messages listed in SHARED are parts of events, not
# events.
set -euo pipefail

cd "$(dirname "$0")/.."
doc=contracts/asyncapi/asyncapi.yaml
SHARED="bmc.dispatch.v1.TimeWindow"
fail=0

# One "message file" pair per entry of components.messages, whatever the order
# of the two keys inside the entry. An entry starts at any key indented by four
# spaces inside components.messages, quoted or with `-` or `.` in it. An entry
# with only one of the two keys, or with either key twice, is an error.
entries=$(awk '
  function flush() {
    if (entry != "" && (message != "" || file != "")) {
      if (message == "" || file == "") print "error", entry, "needs both x-proto-message and x-proto-file"
      else print "pair", message, file
    }
    entry = ""; message = ""; file = ""; seenMessage = 0; seenFile = 0
  }
  /^[^ #]/ { flush(); inComponents = ($0 ~ /^components:[[:space:]]*$/); inMessages = 0; next }
  inComponents && /^  [^ #]/ { flush(); inMessages = ($0 ~ /^  messages:[[:space:]]*$/); next }
  inMessages && /^    [^ #][^:]*:[[:space:]]*$/ { flush(); entry = $1; sub(/:$/, "", entry); next }
  inMessages && entry != "" && /^[[:space:]]+x-proto-message:/ {
    if (seenMessage++) print "error", entry, "has x-proto-message twice"
    message = $2
  }
  inMessages && entry != "" && /^[[:space:]]+x-proto-file:/ {
    if (seenFile++) print "error", entry, "has x-proto-file twice"
    file = $2
  }
  END { flush() }
' "$doc")
while read -r kind rest; do
  [ "$kind" = error ] || continue
  echo "error: $doc: $rest" >&2
  fail=1
done <<<"$entries"
pairs=$(awk '$1 == "pair" { print $2, $3 }' <<<"$entries" | sort)
if [ -z "$pairs" ]; then
  echo "error: $doc lists no message with x-proto-message and x-proto-file" >&2
  exit 1
fi
documented=$(awk '{print $1}' <<<"$pairs")

while read -r message file; do
  path="contracts/asyncapi/$file"
  if [ -z "$file" ] || [ ! -f "$path" ]; then
    echo "error: $message: x-proto-file '$file' does not exist" >&2
    fail=1
    continue
  fi
  package=$(grep -E '^package ' "$path" | sed -E 's/^package (.*);$/\1/')
  name=${message##*.}
  if [ "$package.$name" != "$message" ] || ! grep -q -E "^message $name \{" "$path"; then
    echo "error: $path does not declare $message" >&2
    fail=1
  fi
done <<<"$pairs"

declared=$(
  find contracts/proto -name '*.proto' | sort | while read -r path; do
    package=$(grep -E '^package ' "$path" | sed -E 's/^package (.*);$/\1/')
    grep -E '^message [A-Za-z0-9]+ \{' "$path" | sed -E "s/^message ([A-Za-z0-9]+) \{.*/$package.\1/"
  done | sort
)

for message in $declared; do
  case " $SHARED " in *" $message "*) continue ;; esac
  if ! grep -q -x "$message" <<<"$documented"; then
    echo "error: $message is in contracts/proto but not in $doc" >&2
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  exit 1
fi
echo "contracts: $(wc -l <<<"$documented" | tr -d ' ') events, proto and AsyncAPI agree"
