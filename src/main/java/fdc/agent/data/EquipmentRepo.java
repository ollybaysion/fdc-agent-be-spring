package fdc.agent.data;

import fdc.agent.contract.Compare;
import fdc.agent.contract.EquipmentDetail;
import fdc.agent.contract.SetupEvent;
import java.util.List;

/**
 * 설비 데이터 접근 계층(Node 판 repo.types.ts 대응). fixture ↔ oracle 구현을
 * DataConfig seam 에서 갈아끼운다 — 라우트/에이전트는 이 인터페이스만 안다.
 * blocking 호출은 가상 스레드 전제라 그대로 둔다.
 */
public interface EquipmentRepo {

    /** 없으면 null(Node 판 `EquipmentDetail | null` 대응). */
    EquipmentDetail getDetail(String id);

    List<EquipmentDetail> getPeers(String id);

    List<SetupEvent> getSetupEvents(String id);

    Compare.CompareResponse getCompare(String id, String peerId, String recipe, int windowDays);
}
