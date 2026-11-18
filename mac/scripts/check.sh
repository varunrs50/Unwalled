#!/bin/zsh
# Compiles the name rules on their own and runs their self-check (no app build needed).
set -euo pipefail
cd "$(dirname "$0")/.."
out=$(mktemp)
swiftc -Onone -o "$out" Sources/Unwalled/Support.swift checks/main.swift
"$out"
rm -f "$out"
