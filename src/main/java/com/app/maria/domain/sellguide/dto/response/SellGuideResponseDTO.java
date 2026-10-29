package com.app.maria.domain.sellguide.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SellGuideResponseDTO {
    /** 추천 전 사용 가능 한도 스냅샷. 알고리즘 차감값 아님. */
    private BigDecimal remainingLimit;

    private BigDecimal currentWeight;
    private String periodLabel;

    /** 가중치 기준이 된 예상 확정일 (T+2 영업일 추정). */
    private LocalDate expectedSettlementDate;

    /** 항상 true — 전일종가·예상 확정일 기반 예상치. */
    private boolean isEstimate;

    private List<String> warnings;

    /**
     * RECOMMENDED 종목 먼저, effectiveGainRate 내림차순. 그 외 종목은 그 뒤에 status 순. RECOMMENDED 없으면
     * summary=null.
     */
    private List<SellGuideProductDTO> products;

    /** RECOMMENDED 종목이 하나도 없으면 null. */
    private SellGuideSummaryDTO summary;
}
