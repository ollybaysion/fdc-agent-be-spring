package fdc.agent.chat;

import fdc.agent.chat.AgentTool.ToolResult;
import fdc.agent.contract.ChatTable;
import fdc.agent.contract.EquipmentDetail;
import fdc.agent.contract.EquipmentDetail.EquipmentSection;
import fdc.agent.contract.SetupEvent;
import fdc.agent.data.EquipmentRepo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 설비 조회 손툴 3종(Node 판 chat/tools.ts buildEquipmentTools 대응). */
public final class EquipmentTools {
    private EquipmentTools() {
    }

    /** 설비 ID 하나를 받는 공통 파라미터 스키마. */
    private static Map<String, Object> idParam() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "id", Map.of("type", "string", "description", "설비 ID (예: ETCH-01)")));
        schema.put("required", List.of("id"));
        return schema;
    }

    public static List<AgentTool> buildEquipmentTools(EquipmentRepo repo) {
        return List.of(
                new AgentTool(
                        "get_equipment_detail",
                        "설비 하나의 상세(설비/챔버/센서 정보 표)를 조회한다. 특정 설비 ID를 알 때 사용.",
                        idParam(),
                        args -> {
                            String id = argId(args);
                            EquipmentDetail detail = repo.getDetail(id);
                            if (detail == null) {
                                return ToolResult.of(
                                        "설비 " + (id.isEmpty() ? "(미지정)" : id) + " 는 등록되어 있지 않습니다.");
                            }
                            return new ToolResult(
                                    summarizeDetail(detail),
                                    detail.sections().stream().map(EquipmentTools::sectionToTable).toList());
                        }),
                new AgentTool(
                        "get_peers",
                        "같은 모델의 다른(동종) 설비 목록을 조회한다. 비교 대상이나 동종 설비를 찾을 때 사용.",
                        idParam(),
                        args -> {
                            String id = argId(args);
                            List<EquipmentDetail> peers = repo.getPeers(id);
                            if (peers.isEmpty()) {
                                return ToolResult.of("설비 " + id + " 의 동종 설비가 없습니다.");
                            }
                            String ids = String.join(", ", peers.stream().map(EquipmentDetail::id).toList());
                            return new ToolResult(
                                    "설비 " + id + " 와 같은 모델의 동종 설비 " + peers.size() + "대: " + ids + ".",
                                    List.of(new ChatTable("동종 설비", List.of("ID", "모델"),
                                            peers.stream().map(p -> {
                                                Map<String, Object> row = new LinkedHashMap<String, Object>();
                                                row.put("ID", p.id());
                                                row.put("모델", p.model());
                                                return row;
                                            }).toList())));
                        }),
                new AgentTool(
                        "get_setup_events",
                        "설비의 최근 셋업/설비변경/정비 이벤트 이력을 조회한다.",
                        idParam(),
                        args -> {
                            String id = argId(args);
                            List<SetupEvent> events = repo.getSetupEvents(id);
                            if (events.isEmpty()) {
                                return ToolResult.of("설비 " + id + " 의 셋업 이벤트가 없습니다.");
                            }
                            return new ToolResult(
                                    "설비 " + id + " 의 최근 셋업/정비 이벤트 " + events.size() + "건을 조회했습니다.",
                                    List.of(new ChatTable("셋업 이벤트", List.of("시각", "유형", "라벨"),
                                            events.stream().map(e -> {
                                                Map<String, Object> row = new LinkedHashMap<String, Object>();
                                                row.put("시각", e.time());
                                                row.put("유형", e.type());
                                                row.put("라벨", e.label() != null ? e.label() : "");
                                                return row;
                                            }).toList())));
                        }));
    }

    private static String argId(Map<String, Object> args) {
        Object id = args.get("id");
        return id == null ? "" : String.valueOf(id).trim();
    }

    /** EquipmentSection → FE 호환 표 (columns 앞에 ID 열 추가). */
    private static ChatTable sectionToTable(EquipmentSection s) {
        List<String> columns = new ArrayList<>();
        columns.add("ID");
        columns.addAll(s.columns());
        List<Map<String, Object>> rows = s.rows().stream().map(r -> {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("ID", r.id());
            for (int i = 0; i < s.columns().size(); i++) {
                row.put(s.columns().get(i), i < r.values().size() ? r.values().get(i) : "");
            }
            return row;
        }).toList();
        return new ChatTable(s.label(), columns, rows);
    }

    private static String summarizeDetail(EquipmentDetail d) {
        EquipmentSection chamber = section(d, "chamber");
        EquipmentSection sensor = section(d, "sensor");
        List<String> parts = new ArrayList<>();
        parts.add("설비 **" + d.id() + "**(모델 " + d.model() + ")");
        if (chamber != null) {
            parts.add("챔버 " + chamber.rows().size() + "개");
        }
        if (sensor != null) {
            parts.add("센서 " + sensor.rows().size() + "개");
        }
        return String.join(", ", parts) + " 정보를 조회했습니다. 아래 표를 확인하세요.";
    }

    private static EquipmentSection section(EquipmentDetail d, String key) {
        return d.sections().stream().filter(s -> s.key().equals(key)).findFirst().orElse(null);
    }
}
