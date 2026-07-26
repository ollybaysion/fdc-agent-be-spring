#!/bin/bash
# fdc-agent-be(Node, :8081) vs fdc-agent-be-spring(:8080) 응답 패리티 diff.
#
# 사전 조건: 두 서버가 같은 설정(fixture + mock LLM)으로 떠 있어야 한다.
#   터미널 1: (cd ../fdc-agent-be && PORT=8081 pnpm dev)
#   터미널 2: ./gradlew bootRun
# 실행: ./scripts/parity.sh   (jq 필요)
# 기준선: 2026-07-17 — 당시 24케이스 전부 byte-identical (SSE 는 messageId 만 정규화).
#
# ⚠ 비교 가능한 표면이 계속 줄고 있다. Node 판은 deprecated(2026-07-18)라 더
# 따라오지 않는다:
#   - 2026-07-21: 스킬 케이스 제외 — Node 는 spec v1, Spring 은 v2.
#   - 2026-07-27: 설비 조회 케이스 제외 — Spring 이 equipment 스택을 걷어냈다.
#     같은 질문에 Node 는 표를, Spring 은 요청 카드를 돌려주므로 비교가 성립하지
#     않는다. 아래 남은 것은 /health·/nope·messages_required 정도다.
# 남은 케이스가 이 정도면 스크립트 자체를 접는 편이 정직할 수 있다 — 판단 대기.
NODE=http://localhost:8081
SPRING=http://localhost:8080
PASS=0
FAIL=0

check_get() {
	local path="$1"
	local n s nc sc
	n=$(curl -s -w '\n%{http_code}' "$NODE$path")
	s=$(curl -s -w '\n%{http_code}' "$SPRING$path")
	nc=$(tail -1 <<<"$n")
	sc=$(tail -1 <<<"$s")
	nb=$(head -n -1 <<<"$n")
	sb=$(head -n -1 <<<"$s")
	if [ "$nc" != "$sc" ]; then
		echo "FAIL [$path] status node=$nc spring=$sc"
		FAIL=$((FAIL + 1))
		return
	fi
	if [ "$nb" == "$sb" ]; then
		echo "PASS [$path] ($nc, byte-identical)"
		PASS=$((PASS + 1))
		return
	fi
	# 바이트 다르면 구조 비교(jq 정렬)로 재판정
	if [ "$(jq -S . <<<"$nb" 2>/dev/null)" == "$(jq -S . <<<"$sb" 2>/dev/null)" ] && [ -n "$nb" ]; then
		echo "PASS [$path] ($nc, 구조 동일·키순서만 다름)"
		PASS=$((PASS + 1))
	else
		echo "FAIL [$path] body diff:"
		FAIL=$((FAIL + 1))
		diff <(jq -S . <<<"$nb" 2>/dev/null || echo "$nb") <(jq -S . <<<"$sb" 2>/dev/null || echo "$sb") | head -15
	fi
}

# SSE: messageId(타임스탬프)만 정규화 후 비교
check_chat() {
	local payload="$1" label="$2"
	local n s
	n=$(curl -s -X POST -H 'Content-Type: application/json' -d "$payload" "$NODE/api/fdc/v1/chat" | sed -E 's/"messageId":"[^"]+"/"messageId":"X"/')
	s=$(curl -s -X POST -H 'Content-Type: application/json' -d "$payload" "$SPRING/api/fdc/v1/chat" | sed -E 's/"messageId":"[^"]+"/"messageId":"X"/')
	if [ "$n" == "$s" ]; then
		echo "PASS [chat:$label] (SSE byte-identical)"
		PASS=$((PASS + 1))
	else
		echo "FAIL [chat:$label] SSE diff:"
		FAIL=$((FAIL + 1))
		diff <(echo "$n") <(echo "$s") | head -10
	fi
}

check_get "/health"
check_get "/api/fdc/v1/nope"

check_chat '{"messages":[]}' "messages_required"

echo "----------------------------------------"
echo "PASS=$PASS FAIL=$FAIL"
