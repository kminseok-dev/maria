package com.app.maria.domain.sellorder.service;

import java.math.BigDecimal;

public interface SellLimitService {

    boolean isWithinSellLimit(Long accountId, BigDecimal orderAmount);

    /** 현재 사용 가능한 매도 한도를 조회한다. 행 락 없음 — 조회 전용. */
    BigDecimal getRemainingLimit(Long accountId);
}
