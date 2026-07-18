package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fdc.agent.data.oracle.SchemaMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Node 판 test/schema-map.test.ts 포팅 — 식별자 화이트리스트 검증. */
class SchemaMapTest {

    @Test
    void 유효한_식별자는_그대로_돌려준다() {
        assertThat(SchemaMap.assertIdent("FDC_EQUIPMENT", "t")).isEqualTo("FDC_EQUIPMENT");
        assertThat(SchemaMap.assertIdent("SCOTT.EMP", "t")).isEqualTo("SCOTT.EMP");
        assertThat(SchemaMap.assertIdent("col$1", "t")).isEqualTo("col$1");
        assertThat(SchemaMap.assertIdent("A#B_9", "t")).isEqualTo("A#B_9");
    }

    @Test
    void 위험한_식별자는_거부한다() {
        for (String bad : List.of(
                "BAD-NAME", "1ABC", "TAB LE", "DROP TABLE X", "a".repeat(31), "", "T;--")) {
            assertThatThrownBy(() -> SchemaMap.assertIdent(bad, "ctx"))
                    .as("assertIdent(%s)", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ctx");
        }
    }

    @Test
    void 라벨은_valueLabels_우선_없으면_valueCols() {
        assertThat(SchemaMap.labelsFor(List.of("C1"), List.of("라벨1"))).containsExactly("라벨1");
        assertThat(SchemaMap.labelsFor(List.of("C1"), null)).containsExactly("C1");
    }
}
