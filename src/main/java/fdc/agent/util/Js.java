package fdc.agent.util;

/**
 * 결정론적 해시/수치 포맷 헬퍼. fixture 데이터가 결정론이므로 동일 입력에는
 * 항상 동일한 값이 나와야 한다(재현성 전제).
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
     * 수 표기: 정수값이면 소수점 없이(JSON 1720, 0.95 그대로)
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
