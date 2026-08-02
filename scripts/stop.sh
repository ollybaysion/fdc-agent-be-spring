#!/bin/bash
# fdc-agent-be-spring 종료 — start.sh 가 남긴 PID 파일의 프로세스만 내린다.
#
# 실행: ./scripts/stop.sh          # SIGTERM 후 GRACE 초 대기, 안 내려가면 SIGKILL
#
# 이름 패턴으로 pkill 하지 않는다 — 같은 박스에서 다른 사람·다른 세션이 띄운 서버를
# 함께 죽이는 사고를 막기 위해, 대상은 우리가 띄운 PID 하나뿐이다. PID 재사용까지
# 감안해 죽이기 전에 그 프로세스가 정말 이 서버인지 cmdline 으로 확인한다.
set -u

cd "$(dirname "$0")/.." || exit 1

PIDFILE=logs/fdc-agent-be.pid
GRACE=${GRACE:-20}

if [ ! -f "$PIDFILE" ]; then
	echo "PID 파일이 없다 ($PIDFILE) — 내릴 것이 없다."
	echo "bootRun 이나 손으로 띄운 프로세스는 이 스크립트가 모른다. 직접 내려라."
	exit 0
fi

PID=$(cat "$PIDFILE")
if ! kill -0 "$PID" 2>/dev/null; then
	echo "PID $PID 는 이미 없다 — PID 파일만 정리한다."
	rm -f "$PIDFILE"
	exit 0
fi

if [ -r "/proc/$PID/cmdline" ]; then
	case "$(tr '\0' ' ' <"/proc/$PID/cmdline")" in
	*fdc-agent-be-spring*) ;;
	*)
		echo "PID $PID 는 이 서버가 아니다 — 건드리지 않는다(PID 재사용으로 보인다)."
		echo "확인 후 $PIDFILE 을 지워라."
		exit 1
		;;
	esac
fi

kill "$PID"
for i in $(seq 1 "$GRACE"); do
	if ! kill -0 "$PID" 2>/dev/null; then
		echo "종료됨 — PID $PID (${i}s)"
		rm -f "$PIDFILE"
		exit 0
	fi
	sleep 1
done

echo "${GRACE}s 안에 내려가지 않는다 — SIGKILL."
kill -9 "$PID" 2>/dev/null
rm -f "$PIDFILE"
