#!/bin/bash
# fdc-agent-be(Node, :8081) vs fdc-agent-be-spring(:8080) 응답 패리티 diff — 9케이스.
#
# 사전 조건: 두 서버가 같은 설정(fixture + mock LLM)으로 떠 있어야 한다.
#   터미널 1: (cd ../fdc-agent-be && PORT=8081 pnpm dev)
#   터미널 2: ./gradlew bootRun
# 실행: ./scripts/parity.sh   (jq 필요)
# 기준선: 2026-07-17 — 24케이스 전부 byte-identical (SSE 는 messageId 만 정규화).
# 2026-07-19 정형 조회 4종(equipment) 제거로 GET 15케이스 삭제 — Node 판(대조군)
# 은 여전히 그 경로를 서빙하지만 Spring(기준 구현) 표면에서 빠졌으므로 비교 대상 아님.
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

check_chat '{"messages":[{"role":"user","content":"ETCH-01 설비 정보 보여줘"}]}' "detail"
check_chat '{"messages":[{"role":"user","content":"ETCH-01 동종 설비 알려줘"}]}' "peers"
check_chat '{"messages":[{"role":"user","content":"ETCH-01 셋업 이력"}]}' "events"
check_chat '{"messages":[{"role":"user","content":"안녕하세요"}]}' "generic"
check_chat '{"messages":[{"role":"user","content":"S-0004 센서 설명해줘"}]}' "skill-explain"
check_chat '{"messages":[]}' "messages_required"
check_chat '{"messages":[{"role":"user","content":"분석"}],"context":[{"equipment":"ETCH-01","chambers":[{"sensors":[{"name":"5"}]}]}],"timeRange":{"start":"2026-05-01","end":"2026-05-07"}}' "form-context"

echo "----------------------------------------"
echo "PASS=$PASS FAIL=$FAIL"
