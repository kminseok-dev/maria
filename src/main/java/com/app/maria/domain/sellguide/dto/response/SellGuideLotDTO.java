package com.app.maria.domain.sellguide.dto.response;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SellGuideLotDTO {
    private Long inboundDetailId;
    /** 조회 시점 스냅샷 잔량 — 불변. 알고리즘 작업용 잔량과 별개. */
    private BigDecimal availableQty;
    /** purchasePrice × purchaseFxRate. 시세 조회 실패와 무관하게 항상 반환. */
    private BigDecimal purchasePriceKrw;
    /** (currentPriceKrw - purchasePriceKrw) / currentPriceKrw. 시세 조회 실패 시 null. */
    private BigDecimal gainRate;
    /** 이 lot에서 실제 소비된 추천 수량. 0이면 미선택. */
    private BigDecimal recommendedQty;
}
