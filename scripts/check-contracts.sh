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

# One "message file" pair per entry of components.messages (a key indented by
# four spaces), whatever the order of the two keys inside the entry.
pairs=$(awk '
  function flush() { if (message != "") print message, file; message = ""; file = "" }
  /^    [A-Za-z0-9_]+:[[:space:]]*$/ { flush() }
  /^[[:space:]]+x-proto-message:/ { message = $2 }
  /^[[:space:]]+x-proto-file:/ { file = $2 }
  END { flush() }
' "$doc" | sort)
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
