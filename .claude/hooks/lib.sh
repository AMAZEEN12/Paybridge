#!/bin/bash
# Shared helpers for the PayBridge guardrail hooks. Read the tool call (JSON on stdin) and expose:
#   TOOL  the tool name        FILE  the file path (Edit/Write)       CMD  the shell command (Bash)
#   TEXT  every string inside tool_input joined together (what is being written)
# Needs jq, or python3/python as a fallback. If neither exists the hook blocks, because a guardrail that
# cannot read the request must not quietly let it through.
INPUT=$(cat)

block() {
  echo "BLOCKED by PayBridge guardrail: $1" >&2
  exit 2
}

PY=$(command -v python3 || command -v python || true)

if command -v jq >/dev/null 2>&1; then
  TOOL=$(printf '%s' "$INPUT" | jq -r '.tool_name // empty')
  FILE=$(printf '%s' "$INPUT" | jq -r '.tool_input.file_path // empty')
  CMD=$(printf '%s' "$INPUT" | jq -r '.tool_input.command // empty')
  TEXT=$(printf '%s' "$INPUT" | jq -r '[.tool_input | .. | strings] | join("\n")')
elif [ -n "$PY" ]; then
  py_get() {
    printf '%s' "$INPUT" | "$PY" -c '
import sys, json
d = json.load(sys.stdin)
ti = d.get("tool_input") or {}
k = sys.argv[1]
if k == "tool":
    print(d.get("tool_name") or "")
elif k == "file":
    print(ti.get("file_path") or "")
elif k == "cmd":
    print(ti.get("command") or "")
else:
    out = []
    def walk(x):
        if isinstance(x, str):
            out.append(x)
        elif isinstance(x, dict):
            for v in x.values():
                walk(v)
        elif isinstance(x, list):
            for v in x:
                walk(v)
    walk(ti)
    print("\n".join(out))
' "$1"
  }
  TOOL=$(py_get tool)
  FILE=$(py_get file)
  CMD=$(py_get cmd)
  TEXT=$(py_get text)
else
  block "these hooks need jq (or python). Install one, for example: winget install jqlang.jq"
fi

# Windows paths use backslashes; the checks below expect forward slashes.
FILE=${FILE//\\//}
FILE=${FILE%$'\r'}
FILE=$(printf '%s' "$FILE" | sed -E 's#/+#/#g')
