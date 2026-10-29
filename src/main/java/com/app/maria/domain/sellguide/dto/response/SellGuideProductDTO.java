package com.app.maria.domain.sellguide.dto.response;

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

    /** expectedGain / expectedSellAmount. 시세 조회 실패 시 null. */
    private BigDecimal effectiveGainRate;

    private BigDecimal recommendedQty;
    private BigDecimal expectedSellAmount;
    private BigDecimal expectedGain;
    private BigDecimal weightedGain;

    /** 추천 종목 여부. true면 추천 수량 > 0. */
    private boolean recommended;

    /** FIFO 순 전체 lot 목록 (표시용). */
    private List<SellGuideLotDTO> lots;
}
