package com.app.maria.domain.risk.service;

import com.app.maria.domain.risk.calendar.KrxTradingCalendar;
import com.app.maria.domain.risk.clock.RiskMarketClock;
import com.app.maria.domain.risk.dto.StockRiskGradeDTO;
import com.app.maria.domain.risk.dto.UsableRiskGradeDTO;
import com.app.maria.domain.risk.mapper.StockRiskGradeMapper;
import com.app.maria.domain.risk.type.RiskDataStatus;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class RiskGradeServiceImpl implements RiskGradeService {
    private final StockRiskGradeMapper mapper;
    private final KrxTradingCalendar calendar;
    private final RiskMarketClock clock;
    private final boolean freshnessCheck;

    public RiskGradeServiceImpl(
            StockRiskGradeMapper mapper, KrxTradingCalendar calendar, RiskMarketClock clock) {
        this(mapper, calendar, clock, true);
    }

    /**
     * freshnessCheck가 false이면 등급 기준일이 얼마나 지났는지 보지 않고 등급이 있으면 그대로 쓴다(상태는 CURRENT). risk 배치를 돌리지 않는
     * 시연 환경에서 시드 등급이 실제 날짜 때문에 만료되는 것을 막는 용도이고, 기본값은 켜짐이다.
     */
    @Autowired
    public RiskGradeServiceImpl(
            StockRiskGradeMapper mapper,
            KrxTradingCalendar calendar,
            RiskMarketClock clock,
            @Value("${custom.risk.freshness-check:true}") boolean freshnessCheck) {
        this.mapper = mapper;
        this.calendar = calendar;
        this.clock = clock;
        this.freshnessCheck = freshnessCheck;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UsableRiskGradeDTO> findUsable() {
        var context = freshnessCheck ? context() : null;
        return mapper.findAll().stream()
                .map(row -> toUsable(row, context))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, UsableRiskGradeDTO> findUsableByTickers(Set<String> tickers) {
        var context = freshnessCheck ? context() : null;
        Map<String, UsableRiskGradeDTO> usable = new LinkedHashMap<>();
        for (String ticker : tickers) {
            try {
                Optional.ofNullable(mapper.findByTicker(ticker))
                        .flatMap(row -> toUsable(row, context))
                        .ifPresent(grade -> usable.put(ticker, grade));
            } catch (AppException e) {
                // 이 종목의 자료만 깨진 경우(달력에 없는 priceTo 등): 나머지 종목은 계속 쓴다.
                log.warn("위험등급 자료 이상으로 제외: {} ({})", ticker, e.getErrorType());
            }
        }
        return usable;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UsableRiskGradeDTO> findUsableByTicker(String ticker) {
        var context = freshnessCheck ? context() : null;
        return Optional.ofNullable(mapper.findByTicker(ticker))
                .flatMap(row -> toUsable(row, context));
    }

    private record Context(KrxTradingCalendar.Snapshot snapshot, LocalDate reference) {}

    private Context context() {
        var snapshot = calendar.load();
        var reference = snapshot.latest(clock.now());
        if (!Integer.valueOf(32400).equals(mapper.sessionUtcOffsetSeconds()))
            throw new AppException(ErrorType.RISK_TIMEZONE_UNAVAILABLE);
        return new Context(snapshot, reference);
    }

    private Optional<UsableRiskGradeDTO> toUsable(StockRiskGradeDTO row, Context context) {
        if (row.getAnalysisVersion() == null || row.getAnalysisVersion() <= 0)
            return Optional.empty();
        var status =
                context == null
                        ? RiskDataStatus.CURRENT
                        : context.snapshot().state(row.getPriceTo(), context.reference());
        if (status == RiskDataStatus.EXPIRED) return Optional.empty();
        return Optional.of(
                new UsableRiskGradeDTO(
                        row.getDomesticProductId(),
                        row.getTicker(),
                        row.getName(),
                        row.getVar975(),
                        row.getRiskGrade(),
                        row.getVolatility(),
                        row.getPriceFrom(),
                        row.getPriceTo(),
                        row.getAnalysisVersion(),
                        status));
    }
}
