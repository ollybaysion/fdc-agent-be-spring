#!/bin/bash
# fdc-agent-be-spring 기동 — 빌드 후 백그라운드 실행, /health 가 응답할 때까지 기다린다.
#
# 실행: ./scripts/start.sh          # 빌드까지 (SKIP_BUILD=1 이면 기존 jar 로 바로)
# 종료: ./scripts/stop.sh
#
# 개발 중에는 이 스크립트보다 ./gradlew bootRun 이 낫다 — 포그라운드로 로그를 보며
# 고치는 흐름이니까. 여기는 "띄워놓고 쓰는" 데모·운영 쪽이라 PID 파일과 헬스 대기가
# 붙어 있고, 그래야 stop.sh 가 우리가 띄운 프로세스만 정확히 내릴 수 있다.
#
# JDK 21 이 필요하다. 시스템 java 가 21 이 아니면 JAVA_HOME 을 지정한다(gradlew 도
# 같은 것을 쓴다). 레포 루트에 .env 가 있으면 읽어서 넘긴다 — 이름 계약이 Node 판과
# 같아서(application.yml 머리말) 같은 파일로 두 서버를 나란히 띄울 수 있다.
set -u

cd "$(dirname "$0")/.." || exit 1

PIDFILE=logs/fdc-agent-be.pid
CONSOLE=logs/console.log
WAIT_SECONDS=${WAIT_SECONDS:-60}

# .env 는 기본값 노릇만 한다 — 셸에 이미 있는 값이 이긴다(PORT=9090 ./scripts/start.sh
# 가 .env 의 PORT 에 먹히면 곤란하니까). source 대신 한 줄씩 읽는 이유이기도 하다.
if [ -f .env ]; then
	while IFS= read -r line || [ -n "$line" ]; do
		line=${line%$'\r'}
		line=${line#export }
		case "$line" in '' | '#'*) continue ;; esac
		key=${line%%=*}
		case "$key" in '' | *[!A-Za-z0-9_]*) continue ;; esac
		[ -n "${!key:-}" ] && continue
		val=${line#*=}
		case "$val" in
		\"*\") val=${val#\"} val=${val%\"} ;;
		\'*\') val=${val#\'} val=${val%\'} ;;
		esac
		export "$key=$val"
	done <.env
fi

PORT=${PORT:-8080}
BASE=http://localhost:$PORT

if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
	echo "이미 떠 있다 — PID $(cat "$PIDFILE"), $BASE"
	echo "재기동하려면 ./scripts/stop.sh 먼저."
	exit 1
fi

JAVA=java
[ -n "${JAVA_HOME:-}" ] && JAVA=$JAVA_HOME/bin/java
JAVA_VERSION_LINE=$("$JAVA" -version 2>&1 | head -1)
case "$JAVA_VERSION_LINE" in
*\"21*) ;;
*) echo "경고: JDK 21 이 아닌 것 같다 ($JAVA_VERSION_LINE) — JAVA_HOME 을 지정해라." ;;
esac

if [ "${SKIP_BUILD:-0}" != "1" ]; then
	echo "빌드 중 — ./gradlew bootJar"
	./gradlew bootJar -q --console=plain || {
		echo "빌드 실패 — 기동하지 않는다."
		exit 1
	}
fi

# bootJar 산출물만 고른다(-plain.jar 은 라이브러리 jar 이라 실행 불가).
JAR=""
for f in build/libs/*.jar; do
	case "$f" in *-plain.jar) continue ;; esac
	[ -f "$f" ] && JAR=$f
done
if [ -z "$JAR" ]; then
	echo "실행할 jar 이 없다 (build/libs/) — SKIP_BUILD 를 끄고 다시 실행해라."
	exit 1
fi

mkdir -p logs
nohup "$JAVA" -jar "$JAR" >"$CONSOLE" 2>&1 &
PID=$!
echo "$PID" >"$PIDFILE"

for i in $(seq 1 "$WAIT_SECONDS"); do
	if curl -sf "$BASE/health" >/dev/null 2>&1; then
		echo "기동 완료 — $BASE (PID $PID, ${i}s)"
		echo "로그: ${LOG_FILE:-logs/fdc-agent-be.log} · 콘솔: $CONSOLE"
		exit 0
	fi
	# 포트 충돌·설정 오류는 여기서 죽는다. 남은 시간을 기다릴 이유가 없다.
	if ! kill -0 "$PID" 2>/dev/null; then
		echo "기동 실패 — 프로세스가 죽었다. $CONSOLE 마지막 줄:"
		tail -20 "$CONSOLE"
		rm -f "$PIDFILE"
		exit 1
	fi
	sleep 1
done

echo "${WAIT_SECONDS}s 안에 /health 가 응답하지 않았다 — 프로세스(PID $PID)는 살아 있다."
echo "$CONSOLE 을 확인하고, 필요하면 ./scripts/stop.sh 로 내려라."
exit 1
