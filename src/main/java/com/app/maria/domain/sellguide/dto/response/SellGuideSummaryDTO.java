package com.app.maria.domain.sellguide.dto.response;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SellGuideSummaryDTO {
    private BigDecimal totalRecommendedSellAmount;
    private BigDecimal totalWeightedGain;

    /** 추천 매도 + 감면 적용 예상세액. */
    private BigDecimal estimatedTaxWithRelief;

    /** 추천 매도 + 감면 미적용 예상세액. */
    private BigDecimal estimatedTaxWithoutRelief;

    /** taxWithoutRelief - taxWithRelief. 항상 >= 0. 감면 제외 계좌는 0. */
    private BigDecimal estimatedReliefEffect;

    /** 예상치 안내 문구. */
    private String taxNote;
}
