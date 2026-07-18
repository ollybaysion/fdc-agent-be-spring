package fdc.agent.contract;

import java.util.List;

/**
 * 설비 상세(@fdc/contract equipment.ts 대응). 세션 배열 — FE 는 key 로
 * 렌더러를 고른다. 필드 구성/이름은 zod 계약과 1:1.
 */
public record EquipmentDetail(String id, String name, String model, List<EquipmentSection> sections) {

    /** 한 "세션"(정보 묶음). key = "equipment" | "chamber" | "sensor" | 확장. */
    public record EquipmentSection(String key, String label, List<String> columns, List<SectionRow> rows) {
    }

    /** 세션 표의 한 행. equipment 는 1행, 나머지는 N행. */
    public record SectionRow(String id, List<String> values) {
    }
}
