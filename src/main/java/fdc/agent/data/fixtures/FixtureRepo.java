package fdc.agent.data.fixtures;

import fdc.agent.contract.EquipmentDetail;
import fdc.agent.contract.EquipmentDetail.EquipmentSection;
import fdc.agent.contract.EquipmentDetail.SectionRow;
import fdc.agent.contract.SetupEvent;
import fdc.agent.data.EquipmentRepo;
import java.util.List;
import java.util.stream.IntStream;

/**
 * fixture 기반 EquipmentRepo(Phase 0). 컬럼 라벨 = col1..col10 (기존 FE
 * COL_NAMES 와 동일) — oracle 모드에서만 SCHEMA-MAP 의 실 라벨로 바뀐다.
 */
public class FixtureRepo implements EquipmentRepo {

    private static final List<String> FIXTURE_COLS =
            IntStream.rangeClosed(1, 10).mapToObj(i -> "col" + i).toList();

    private static EquipmentDetail toDetail(MockData.MockEquipmentDetail d) {
        return new EquipmentDetail(d.id(), d.name(), d.model(), List.of(
                new EquipmentSection("equipment", "설비 정보", FIXTURE_COLS,
                        List.of(new SectionRow(d.id(), d.values()))),
                new EquipmentSection("chamber", "챔버 정보", FIXTURE_COLS, d.chambers()),
                new EquipmentSection("sensor", "센서 정보", FIXTURE_COLS, d.sensors())));
    }

    @Override
    public EquipmentDetail getDetail(String id) {
        return MockData.getEquipmentDetail(id).map(FixtureRepo::toDetail).orElse(null);
    }

    @Override
    public List<EquipmentDetail> getPeers(String id) {
        return MockData.getPeers(id).stream().map(FixtureRepo::toDetail).toList();
    }

    @Override
    public List<SetupEvent> getSetupEvents(String id) {
        return MockData.getSetupEvents(id);
    }
}
