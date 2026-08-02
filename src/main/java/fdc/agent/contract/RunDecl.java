package fdc.agent.contract;

import java.util.Map;

/**
 * 진행 중인 절차 하나의 <b>명시 선언</b> — (스킬, 인자 원문) (#38 T3).
 *
 * <p>두 가지를 해결한다. 첫째, 스냅샷이 하나도 안 도착한 절차도 존재를 주장할 수
 * 있어 <b>첫 카드</b>가 나온다(도착에서만 유도하면 절차 시작이 불가능하다). 둘째,
 * {@code args} 가 <b>인자 원문 채널</b>이다(T1) — queryKey 는 구분자를 {@code _} 로
 * 접는 손실 인코딩이라 키 파싱으로 args 를 복원하면 오염된 SQL 이 사용자 권한으로
 * 실행될 수 있다. 카드 SQL 은 오직 여기 실린 원문 값으로만 만든다.
 */
public record RunDecl(String skill, Map<String, String> args) {
}
