package fdc.agent.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 렌더된 SQL 은 <b>사용자가 사내 DB 에서 자기 권한으로</b> 실행한다 — 이스케이프는 미관이
 * 아니라 경계다. 그리고 반쪽 결과는 내지 않는다: 못 채운 바인드가 남으면 문장을 안 만든다.
 */
class SqlRenderTest {

    private static Map<String, String> binds(String... kv) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put(kv[i], kv[i + 1]);
        }
        return out;
    }

    @Test
    void 문자열은_따옴표로_감싸고_숫자는_맨값으로() {
        String sql = SqlRender.render(
                "SELECT * FROM t WHERE id = :id AND idx = :idx", binds("id", "S-0004", "idx", "7"));
        assertThat(sql).isEqualTo("SELECT * FROM t WHERE id = 'S-0004' AND idx = 7");
    }

    @Test
    void 작은따옴표는_두_번으로_접어_문장을_닫지_못하게() {
        // 이게 새면 모델이 사람 손을 빌려 SQL 을 주입할 수 있다.
        String sql = SqlRender.render("SELECT * FROM t WHERE name = :name",
                binds("name", "O'Brien'; DROP TABLE t--"));
        assertThat(sql).isEqualTo("SELECT * FROM t WHERE name = 'O''Brien''; DROP TABLE t--'");
        // 값 전체가 하나의 리터럴 안에 갇힌다 — 따옴표 개수가 짝수다.
        assertThat(sql.chars().filter(c -> c == '\'').count() % 2).isZero();
    }

    @Test
    void ISO_날짜는_TO_DATE로_렌더한다() {
        // 문자열로 두면 NLS_DATE_FORMAT(기본 DD-MON-RR)에 걸려 ORA-01861 이 난다.
        String sql = SqlRender.render("SELECT * FROM t WHERE d BETWEEN :s AND :e",
                binds("s", "2026-05-01", "e", "2026-05-31 23:59:59"));
        assertThat(sql).isEqualTo("SELECT * FROM t WHERE d BETWEEN TO_DATE('2026-05-01', 'YYYY-MM-DD')"
                + " AND TO_DATE('2026-05-31 23:59:59', 'YYYY-MM-DD HH24:MI:SS')");
    }

    @Test
    void 따옴표_리터럴_안의_콜론은_바인드가_아니다() {
        // 날짜 마스크 'HH24:MI:SS' 가 바인드로 오인되면 문장이 망가진다.
        String sql = SqlRender.render(
                "SELECT TO_CHAR(t, 'YYYY-MM-DD HH24:MI:SS') FROM t WHERE id = :id",
                binds("id", "A"));
        assertThat(sql).isEqualTo("SELECT TO_CHAR(t, 'YYYY-MM-DD HH24:MI:SS') FROM t WHERE id = 'A'");
    }

    @Test
    void 값이_없는_바인드가_남으면_문장을_만들지_않는다() {
        // 카드에 :var 를 남겨 사람이 손으로 채우게 하지 않는다.
        assertThatThrownBy(() -> SqlRender.render("SELECT * FROM t WHERE a = :a AND b = :b",
                binds("a", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(":b");
    }

    @Test
    void 접두가_같은_바인드를_잘라_먹지_않는다() {
        String sql = SqlRender.render("SELECT * FROM t WHERE a = :id AND b = :id_2",
                binds("id", "1", "id_2", "2"));
        assertThat(sql).isEqualTo("SELECT * FROM t WHERE a = 1 AND b = 2");
    }

    @Test
    void 별칭과_컬럼_이름으로_기대_컬럼을_뽑는다() {
        assertThat(SqlRender.columnsOf(
                "SELECT COUNT(*) AS CNT, ROUND(AVG(v), 2) AS MEAN, eqp_id\n  FROM t WHERE a = :a"))
                .containsExactly("CNT", "MEAN", "EQP_ID");
    }

    @Test
    void 이름을_못_읽으면_반쪽_목록_대신_아무것도_내지_않는다() {
        // 틀린 기대 컬럼을 카드에 그리는 것이 안 그리는 것보다 나쁘다.
        assertThat(SqlRender.columnsOf("SELECT * FROM t")).isNull();
        assertThat(SqlRender.columnsOf("SELECT a, b + c FROM t")).isNull();
    }

    @Test
    void 번들_spec_의_SQL_여섯_개가_전부_읽힌다() {
        // 풀의 실물로 고정한다 — 스킬이 바뀌어 컬럼 추출이 깨지면 여기서 잡힌다.
        QueryPool pool = QueryPool.of(SkillRegistry.bundledSpecs());
        assertThat(pool.all()).hasSize(6);
        for (QueryPool.Query q : pool.all()) {
            assertThat(SqlRender.columnsOf(q.sql()))
                    .as("컬럼 추출: %s", q.queryId())
                    .isNotNull()
                    .isNotEmpty();
        }
    }

    @Test
    void 단일_테이블_SELECT_의_원천_테이블명을_뽑는다() {
        assertThat(SqlRender.tableOf(
                "SELECT a FROM FDC_SENSOR WHERE snsr_id = :id")).isEqualTo("fdc_sensor");
        assertThat(SqlRender.tableOf(
                "SELECT d, t FROM fdc_setup_event ORDER BY d, t")).isEqualTo("fdc_setup_event");
    }

    @Test
    void 조인_서브쿼리_다중_테이블이면_테이블명을_지어내지_않는다() {
        assertThat(SqlRender.tableOf("SELECT a FROM t1 JOIN t2 ON t1.x = t2.x")).isNull();
        assertThat(SqlRender.tableOf("SELECT a FROM t1, t2 WHERE t1.x = t2.x")).isNull();
        assertThat(SqlRender.tableOf("SELECT a FROM (SELECT a FROM t) WHERE a = 1")).isNull();
    }

    @Test
    void 번들_spec_의_스텝_전부가_원천_테이블을_가진다() {
        // steps[].table 저작 + FROM 폴백 — 어느 쪽이든 데이터 블록 헤딩이 비지 않는다.
        QueryPool pool = QueryPool.of(SkillRegistry.bundledSpecs());
        for (QueryPool.Query q : pool.all()) {
            assertThat(q.table()).as("원천 테이블: %s", q.queryId()).isNotBlank();
        }
    }
}
