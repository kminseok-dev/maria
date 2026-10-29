package com.app.maria.global.client.exchange;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RateWithDate {
    private final BigDecimal rate;

    /** getBaseRateWithDate() 에서 실제 조회에 성공한 날짜. */
    private final LocalDate baseDate;
}
