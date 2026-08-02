#!/usr/bin/env bash
# 로컬 라이브 데모 기동 — 실 LLM(Anthropic OpenAI 호환)을 붙여 8094에 띄운다.
# 키는 이 스크립트가 실행 시점에 ~/.fdc-llm-key 에서 읽는다(밖으로 노출하지 않음).
set -euo pipefail
export JAVA_HOME="$HOME/tools/jdk-21.0.11+10"
export LLM_BASE_URL="https://api.anthropic.com/v1"
LLM_API_KEY="$(tr -d ' \t\n' <"$HOME/.fdc-llm-key")"
export LLM_API_KEY
export LLM_MODEL="claude-haiku-4-5-20251001"
cd "$(dirname "$0")"
exec ./gradlew bootRun --no-daemon --args='--server.port=8094'
