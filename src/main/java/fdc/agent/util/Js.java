package fdc.agent.util;

/**
 * Node 판과의 수치/해시 패리티 헬퍼. fixture 데이터가 결정론이라 JS 구현과
 * bit-호환이어야 두 서버의 응답이 일치한다(패리티 하네스 전제).
 */
public final class Js {
    private Js() {
    }

    /**
     * mockData.ts / registry.ts 의 hash 와 동일: 32-bit 오버플로 `h*31+code`
     * 후 절대값. JS 의 `Math.abs(x|0)` 는 2^31 까지 갈 수 있어 long 으로 받는다.
     */
    public static long hash(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
            h = h * 31 + s.charAt(i);
        }
        return Math.abs((long) h);
    }

    /**
     * JS 의 수 표기 대응: 정수값이면 소수점 없이(JSON 1720, 0.95 그대로)
     * 직렬화되도록 Long/Double 을 가려 돌려준다.
     */
    public static Number num(double v) {
        if (Double.isFinite(v) && v == Math.rint(v)
                && v >= Long.MIN_VALUE && v <= Long.MAX_VALUE) {
            return (long) v;
        }
        return v;
    }
}
