package com.app.maria.domain.tax.batch;

import com.app.maria.domain.account.service.AccountTransactionalService;
import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.domain.tax.dto.TaxSnapshotDTO;
import com.app.maria.domain.tax.mapper.TaxSnapshotMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TaxSnapshotWriter implements ItemWriter<TaxSnapshotDTO> {
    private static final String BENEFIT_CHANGE_REASON = "일별 세액 스냅샷 배치 판정";

    private final TaxSnapshotMapper taxSnapshotMapper;
    private final AccountTransactionalService accountTransactionalService;

    @Override
    public void write(Chunk<? extends TaxSnapshotDTO> chunk) {
        if (chunk.isEmpty()) {
            return;
        }
        taxSnapshotMapper.upsertSnapshots(new ArrayList<>(chunk.getItems()));

        // 계좌마다 따로 바꾸면 청크 하나에 쿼리가 수백 개 나간다. 청크 단위로 모아 한 번에 바꾼다
        Map<Long, BenefitType> newBenefitByAccountId = new LinkedHashMap<>();
        for (TaxSnapshotDTO snapshot : chunk) {
            newBenefitByAccountId.put(
                    snapshot.getAccountId(),
                    snapshot.getWeightedExternalAmount().signum() > 0
                            ? BenefitType.REDUCED
                            : BenefitType.POSSIBLE);
        }
        accountTransactionalService.changeBenefits(
                newBenefitByAccountId,
                BENEFIT_CHANGE_REASON,
                chunk.getItems().get(0).getCalculatedAt());
    }
}
