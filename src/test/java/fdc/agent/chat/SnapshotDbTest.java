package fdc.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.ChatTable;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Design B — 붙여넣은 스냅샷을 인메모리 SQLite 로 굽고 SELECT 로 조회(이슈 #11). */
class SnapshotDbTest {

    private static ChatDataSnapshot snap(String key, List<String> cols, List<List<String>> rows) {
        return new ChatDataSnapshot(
                key, key + " 라벨", "2026-07-22T00:00", cols, rows != null ? rows.size() : 0, rows);
    }

    @Test
    void 행_없는_스냅샷뿐이면_적재할_게_없어_null() {
        assertThat(SnapshotDb.build(List.of(snap("cat", List.of("A"), null)))).isNull();
        assertThat(SnapshotDb.build(List.of())).isNull();
        assertThat(SnapshotDb.build(null)).isNull();
    }

    @Test
    void 적재된_표를_SELECT로_조회하고_원문_NULL을_보존한다() {
        try (SnapshotDb db = SnapshotDb.build(List.of(snap("sensor_list",
                List.of("CHAMBER", "SENSOR"),
                List.of(Arrays.asList("챔버A", "온도"), Arrays.asList(null, "압력")))))) {
            assertThat(db.isEmpty()).isFalse();
            ChatTable t = db.query("SELECT * FROM \"sensor_list\"", null);
            assertThat(t.rows()).hasSize(2);
            assertThat(t.rows().get(0).get("SENSOR")).isEqualTo("온도");
            assertThat(t.rows().get(1).get("SENSOR")).isEqualTo("압력");
            // 붙여넣은 원문 NULL 은 SQL NULL → null 로 보존(빈 문자열과 구분).
            assertThat(t.rows().get(1).get("CHAMBER")).isNull();
        }
    }

    @Test
    void 스키마_카탈로그는_테이블과_컬럼을_담되_행은_담지_않는다() {
        try (SnapshotDb db = SnapshotDb.build(List.of(snap("sensor_list",
                List.of("CHAMBER", "SENSOR"),
                List.of(Arrays.asList("챔버A", "온도")))))) {
            String cat = db.schemaCatalog();
            assertThat(cat).contains("`sensor_list`").contains("CHAMBER").contains("SENSOR");
            assertThat(cat).doesNotContain("챔버A").doesNotContain("온도");
        }
    }

    @Test
    void SELECT_외_문장과_다중_문장은_거부한다() {
        try (SnapshotDb db =
                SnapshotDb.build(List.of(snap("t", List.of("A"), List.of(List.of("1")))))) {
            assertThatThrownBy(() -> db.query("INSERT INTO \"t\" VALUES ('x')", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> db.query("DROP TABLE \"t\"", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> db.query("PRAGMA table_info(\"t\")", null))
                    .isInstanceOf(IllegalArgumentException.class);
            // 여러 문장(인젝션 시도)도 거부한다.
            assertThatThrownBy(() -> db.query("SELECT * FROM \"t\"; DROP TABLE \"t\"", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void query_only_라_WITH_DML은_데이터를_바꾸지_못한다() {
        try (SnapshotDb db = SnapshotDb.build(List.of(
                snap("t", List.of("A"), List.of(List.of("1"), List.of("2")))))) {
            // WITH 로 시작하는 DML 은 문자열 가드를 통과하지만, query_only 연결이 실행을 막는다.
            try {
                db.query("WITH x AS (SELECT 1) DELETE FROM \"t\"", null);
            } catch (IllegalArgumentException expected) {
                // 막히는 게 정상 — 어떤 경로든 아래에서 데이터 불변을 확인한다.
            }
            assertThat(db.query("SELECT * FROM \"t\"", null).rows()).hasSize(2);
        }
    }

    @Test
    void 식별자는_안전_문자셋으로_슬러그된다() {
        assertThat(SnapshotDb.slugIdent("sensor_list", "x")).isEqualTo("sensor_list");
        assertThat(SnapshotDb.slugIdent("a-b c", "x")).isEqualTo("a_b_c");
        assertThat(SnapshotDb.slugIdent("설비", "x")).isEqualTo("x"); // 전부 비ASCII → fallback
        assertThat(SnapshotDb.slugIdent("3col", "x")).isEqualTo("t_3col"); // 숫자 시작 → 접두
        assertThat(SnapshotDb.slugIdent("\"; DROP--", "x")).isEqualTo("DROP"); // 위험문자 제거
    }

    @Test
    void 테이블_이름_충돌은_접미사로_유일하게_한다() {
        try (SnapshotDb db = SnapshotDb.build(List.of(
                snap("dup", List.of("A"), List.of(List.of("1"))),
                snap("dup", List.of("A"), List.of(List.of("2")))))) {
            String cat = db.schemaCatalog();
            assertThat(cat).contains("`dup`").contains("`dup_2`");
        }
    }
}
