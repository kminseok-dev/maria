package com.app.maria.global.client.kis;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PriceWithDate {
    private final BigDecimal price;
    /** KIS 응답에서 기준일을 확인할 수 없으면 null. 단순 오늘-1일 대입 금지. */
    private final LocalDate baseDate;
}
