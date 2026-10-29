package com.app.maria.domain.sellguide.dto.response;

import com.app.maria.domain.sellguide.type.LotRecommendationStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SellGuideProductDTO {
    private Long foreignProductId;
    private String ticker;
    private String productName;
    private String currency;

    /** 원화 환산 현재가. 시세 조회 실패 시 null. */
    private BigDecimal currentPriceKrw;

    /** KIS 전일종가 기준일. 응답에서 확인 불가면 null + warnings 안내. */
    private LocalDate priceBaseDate;

    /** 실제 환율 조회에 성공한 날짜. */
    private LocalDate fxRateBaseDate;

    /**
     * RECOMMENDED: expectedGain / expectedSellAmount (실제 달성 이득율). 그 외: 첫 FIFO lot의 gainRate (표시용).
     * 시세 조회 실패 시 null.
     */
    private BigDecimal effectiveGainRate;

    private BigDecimal recommendedQty;
    private BigDecimal expectedSellAmount;

    /** lot별 실제 소비 이득 합산. */
    private BigDecimal expectedGain;

    private BigDecimal weightedGain;
    private LotRecommendationStatus status;

    /** FIFO 순 전체 lot 목록 (표시용). */
    private List<SellGuideLotDTO> lots;
}
