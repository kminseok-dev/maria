package com.app.maria.domain.sellguide.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.domain.account.type.Status;
import com.app.maria.domain.sellguide.dto.response.SellGuideLotDTO;
import com.app.maria.domain.sellguide.dto.response.SellGuideProductDTO;
import com.app.maria.domain.sellguide.dto.response.SellGuideResponseDTO;
import com.app.maria.domain.sellguide.dto.response.SellGuideSummaryDTO;
import com.app.maria.domain.sellorder.service.SellLimitService;
import com.app.maria.domain.tax.dto.ExternalBuyDTO;
import com.app.maria.domain.tax.dto.HeldLotDTO;
import com.app.maria.domain.tax.dto.SellLotDTO;
import com.app.maria.domain.tax.dto.TaxCalculationResultDTO;
import com.app.maria.domain.tax.dto.TaxRuleDTO;
import com.app.maria.domain.tax.mapper.TaxMapper;
import com.app.maria.domain.tax.service.TaxCalculations;
import com.app.maria.domain.tax.service.TaxCalculator;
import com.app.maria.domain.tax.type.TaxRuleType;
import com.app.maria.global.client.exchange.ExchangeRateClient;
import com.app.maria.global.client.exchange.RateWithDate;
import com.app.maria.global.client.kis.KisExchangeCode;
import com.app.maria.global.client.kis.KisPriceClient;
import com.app.maria.global.client.kis.PriceWithDate;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellGuideServiceImpl implements SellGuideService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int QTY_SCALE = 4;

    private final AccountMapper accountMapper;
    private final SellLimitService sellLimitService;
    private final TaxMapper taxMapper;
    private final TaxCalculator taxCalculator;
    private final KisPriceClient kisPriceClient;
    private final ExchangeRateClient exchangeRateClient;
    private final BusinessClockService businessClockService;

    @Override
    public SellGuideResponseDTO guide(Long customerId) {
        // ── 1. 기준 시각 ───────────────────────────────────────────────────────
        LocalDateTime now = businessClockService.now();
        LocalDate today = now.toLocalDate();
        LocalDate expectedSettlementDate = computeExpectedSettlementDate(today);
        int taxYear = expectedSettlementDate.getYear();

        // ── 2. 계좌 검증 ───────────────────────────────────────────────────────
        AccountDTO account =
                accountMapper
                        .selectByCustomerId(customerId)
                        .orElseThrow(() -> new AppException(ErrorType.ACCOUNT_NOT_FOUND));
        if (account.getStatus() != Status.OPENED) {
            throw new AppException(ErrorType.ACCOUNT_NOT_FOUND);
        }

        // ── 3. 남은 한도 (응답용 스냅샷) ────────────────────────────────────────
        // getExternalSellTotal() 실패 시 AppException 전파 — 한도 불명 상태로 추천 반환 안 함
        BigDecimal remainingLimit = sellLimitService.getRemainingLimit(account.getAccountId());

        // ── 4. 세금 규칙 + 현재 가중치 ────────────────────────────────────────
        List<TaxRuleDTO> rules = taxMapper.selectTaxRules();
        BigDecimal currentWeight = findWeight(rules, expectedSettlementDate);
        String periodLabel = buildPeriodLabel(rules, expectedSettlementDate, currentWeight);

        // ── 5. 보유 lot 조회 (purchaseDate ASC, inboundDetailId ASC — 쿼리에서 정렬) ──
        List<HeldLotDTO> heldLots = taxMapper.selectHeldLotsByAccountId(account.getAccountId());
        if (heldLots.isEmpty()) {
            return emptyResponse(
                    remainingLimit,
                    currentWeight,
                    periodLabel,
                    expectedSettlementDate,
                    "보유 중인 입고 lot이 없습니다.");
        }

        // ── 6. 시세·환율 일괄 조회 ────────────────────────────────────────────
        List<String> warnings = new ArrayList<>();
        // 종목별 currentPriceKrw
        Map<Long, BigDecimal> priceKrwByProduct = new HashMap<>();
        // 종목별 priceBaseDate
        Map<Long, LocalDate> priceBaseDateByProduct = new HashMap<>();
        // 통화별 fxRateBaseDate
        Map<String, LocalDate> fxRateDateByCurrency = new HashMap<>();
        // 시세·환율 조회 실패 종목
        Map<Long, String> failedProductNames = new LinkedHashMap<>();

        // 통화별 환율 조회 (중복 없이)
        Map<String, BigDecimal> ratesByCurrency = new HashMap<>();
        for (HeldLotDTO lot : heldLots) {
            String currency = lot.getCurrency();
            if (ratesByCurrency.containsKey(currency)
                    || failedProductNames.containsKey(lot.getForeignProductId())) {
                continue;
            }
            if (!ratesByCurrency.containsKey(currency)) {
                try {
                    RateWithDate rwd = exchangeRateClient.getBaseRateWithDate(currency);
                    ratesByCurrency.put(currency, rwd.getRate());
                    fxRateDateByCurrency.put(currency, rwd.getBaseDate());
                } catch (AppException e) {
                    warnings.add(currency + " 환율 조회 실패 — 해당 통화 종목은 추천에서 제외됩니다.");
                    ratesByCurrency.put(currency, null); // null = 실패 마커
                }
            }
        }

        // 종목별 현재가 조회 (중복 없이)
        Map<Long, HeldLotDTO> representativeLot = new LinkedHashMap<>();
        for (HeldLotDTO lot : heldLots) {
            representativeLot.putIfAbsent(lot.getForeignProductId(), lot);
        }
        for (Map.Entry<Long, HeldLotDTO> entry : representativeLot.entrySet()) {
            Long productId = entry.getKey();
            HeldLotDTO rep = entry.getValue();
            BigDecimal fxRate = ratesByCurrency.get(rep.getCurrency());
            if (fxRate == null) {
                // 환율 조회 실패 — 이미 warnings에 추가됨
                failedProductNames.put(productId, rep.getTicker());
                continue;
            }
            try {
                String exchangeCode = KisExchangeCode.fromMarket(rep.getMarket());
                PriceWithDate pwd =
                        kisPriceClient.getPreviousCloseWithDate(exchangeCode, rep.getTicker());
                if (pwd.getPrice().compareTo(ZERO) <= 0) {
                    throw new AppException(ErrorType.KIS_PRICE_NOT_FOUND, rep.getTicker());
                }
                BigDecimal priceKrw = pwd.getPrice().multiply(fxRate);
                priceKrwByProduct.put(productId, priceKrw);
                priceBaseDateByProduct.put(productId, pwd.getBaseDate()); // null 가능

                if (pwd.getBaseDate() == null
                        && !warnings.contains("일부 종목의 전일종가 기준일을 확인할 수 없습니다. 실제 기준일과 다를 수 있습니다.")) {
                    warnings.add("일부 종목의 전일종가 기준일을 확인할 수 없습니다. 실제 기준일과 다를 수 있습니다.");
                }
            } catch (AppException e) {
                warnings.add(rep.getTicker() + " 시세 조회 실패 — 추천에서 제외됩니다.");
                failedProductNames.put(productId, rep.getTicker());
            }
        }

        // ── 7. 종목별 FIFO Deque 구성 ─────────────────────────────────────────
        // lot은 이미 purchaseDate ASC, inboundDetailId ASC 정렬됨
        Map<Long, Deque<LotEntry>> deques = new LinkedHashMap<>();
        Map<Long, List<HeldLotDTO>> lotsByProduct =
                heldLots.stream()
                        .collect(
                                Collectors.groupingBy(
                                        HeldLotDTO::getForeignProductId,
                                        LinkedHashMap::new,
                                        Collectors.toList()));

        for (Map.Entry<Long, List<HeldLotDTO>> e : lotsByProduct.entrySet()) {
            Long productId = e.getKey();
            if (failedProductNames.containsKey(productId)) continue;
            Deque<LotEntry> deque = new ArrayDeque<>();
            for (HeldLotDTO lot : e.getValue()) {
                BigDecimal priceKrw = priceKrwByProduct.get(productId);
                BigDecimal purchasePriceKrw =
                        lot.getPurchasePrice().multiply(lot.getPurchaseFxRate());
                BigDecimal gainRate = null;
                if (priceKrw != null && priceKrw.compareTo(ZERO) > 0) {
                    gainRate =
                            priceKrw.subtract(purchasePriceKrw)
                                    .divide(priceKrw, 4, RoundingMode.HALF_UP);
                }
                deque.add(
                        new LotEntry(
                                lot.getInboundDetailId(),
                                lot.getCurrentQty(),
                                purchasePriceKrw,
                                gainRate));
            }
            if (!deque.isEmpty()) {
                deques.put(productId, deque);
            }
        }

        // ── 8. FIFO 단계별 greedy ─────────────────────────────────────────────
        Map<Long, ProductResult> productResults = new LinkedHashMap<>();
        for (Long productId : deques.keySet()) {
            productResults.put(productId, new ProductResult());
        }

        BigDecimal workingRemainingLimit = remainingLimit;

        outer:
        while (true) {
            Long bestProductId = null;
            LotEntry bestLot = null;
            BigDecimal bestGainRate = null;

            for (Map.Entry<Long, Deque<LotEntry>> e : deques.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                LotEntry lot = e.getValue().peek();
                BigDecimal gainRate = lot.gainRate;
                if (gainRate == null || gainRate.compareTo(ZERO) <= 0) continue;

                BigDecimal price = priceKrwByProduct.get(e.getKey());
                if (price == null || price.compareTo(ZERO) <= 0) continue;

                // 0으로 내림될 종목은 후보 제외 (무한루프 방지)
                BigDecimal sellable = lot.workingQty.multiply(price).min(workingRemainingLimit);
                BigDecimal qty = sellable.divide(price, QTY_SCALE, RoundingMode.DOWN);
                if (qty.compareTo(ZERO) <= 0) continue;

                if (bestLot == null || gainRate.compareTo(bestGainRate) > 0) {
                    bestLot = lot;
                    bestProductId = e.getKey();
                    bestGainRate = gainRate;
                }
            }

            if (bestLot == null) break;

            BigDecimal price = priceKrwByProduct.get(bestProductId);
            BigDecimal sellable = bestLot.workingQty.multiply(price).min(workingRemainingLimit);
            BigDecimal recommendedQty = sellable.divide(price, QTY_SCALE, RoundingMode.DOWN);
            BigDecimal actualDeducted = recommendedQty.multiply(price);
            BigDecimal gain = recommendedQty.multiply(price.subtract(bestLot.purchasePriceKrw));

            productResults.get(bestProductId).add(recommendedQty, actualDeducted, gain);
            bestLot.consumedQty = bestLot.consumedQty.add(recommendedQty);
            workingRemainingLimit = workingRemainingLimit.subtract(actualDeducted);

            BigDecimal remaining = bestLot.workingQty.subtract(recommendedQty);
            if (remaining.compareTo(ZERO) <= 0) {
                deques.get(bestProductId).poll();
            } else {
                bestLot.workingQty = remaining;
            }

            if (workingRemainingLimit.compareTo(ZERO) <= 0) break;
        }

        // ── 9. SellGuideProductDTO 조립 ───────────────────────────────────────
        List<SellGuideProductDTO> products = new ArrayList<>();

        for (Map.Entry<Long, List<HeldLotDTO>> e : lotsByProduct.entrySet()) {
            Long productId = e.getKey();
            List<HeldLotDTO> lots = e.getValue();
            HeldLotDTO rep = lots.get(0);
            BigDecimal priceKrw = priceKrwByProduct.get(productId);

            if (failedProductNames.containsKey(productId)) {
                products.add(buildFailedProduct(rep, lots));
                continue;
            }

            ProductResult result = productResults.get(productId);
            boolean recommended = result != null && result.recommendedQty.compareTo(ZERO) > 0;

            BigDecimal effectiveGainRate = recommended && result.expectedSellAmount.compareTo(ZERO) > 0
                    ? result.expectedGain.divide(result.expectedSellAmount, 4, RoundingMode.HALF_UP)
                    : null;

            List<SellGuideLotDTO> lotDTOs = buildLotDTOs(lots, priceKrw, productId);

            products.add(
                    SellGuideProductDTO.builder()
                            .foreignProductId(productId)
                            .ticker(rep.getTicker())
                            .productName(rep.getName())
                            .currency(rep.getCurrency())
                            .currentPriceKrw(priceKrw)
                            .priceBaseDate(priceBaseDateByProduct.get(productId))
                            .fxRateBaseDate(fxRateDateByCurrency.get(rep.getCurrency()))
                            .effectiveGainRate(effectiveGainRate)
                            .recommendedQty(result != null ? result.recommendedQty : ZERO)
                            .expectedSellAmount(result != null ? result.expectedSellAmount : ZERO)
                            .expectedGain(result != null ? result.expectedGain : ZERO)
                            .weightedGain(result != null ? result.expectedGain.multiply(currentWeight) : ZERO)
                            .recommended(recommended)
                            .lots(lotDTOs)
                            .build());
        }

        // 정렬: 추천 먼저, effectiveGainRate 내림차순
        products.sort(
                Comparator.<SellGuideProductDTO>comparingInt(p -> p.isRecommended() ? 0 : 1)
                        .thenComparing(
                                Comparator.comparing(
                                        p -> p.getEffectiveGainRate() != null
                                                ? p.getEffectiveGainRate()
                                                : BigDecimal.valueOf(Long.MIN_VALUE),
                                        Comparator.reverseOrder())));

        boolean hasRecommended = products.stream().anyMatch(SellGuideProductDTO::isRecommended);

        // ── 10. 세액 비교 ─────────────────────────────────────────────────────
        SellGuideSummaryDTO summary = null;
        if (hasRecommended) {
            warnings.add("가정산(PROVISIONAL) 건은 세액 시뮬레이션에 포함되지 않았습니다.");

            List<SellLotDTO> existingLots =
                    taxMapper.selectFinalizedLotsByAccountIdsAndYear(
                            List.of(account.getAccountId()), taxYear, now);

            List<SellLotDTO> virtualLots =
                    buildVirtualLots(
                            products, lotsByProduct, expectedSettlementDate, priceKrwByProduct);

            List<SellLotDTO> allLots =
                    Stream.concat(existingLots.stream(), virtualLots.stream())
                            .collect(Collectors.toList());

            List<ExternalBuyDTO> externalTrades =
                    taxMapper.selectExternalBuysByAccountIdsAndYear(
                            List.of(account.getAccountId()), taxYear, now);

            boolean reliefExcluded = BenefitType.isReliefExcluded(account.getBenefit());
            TaxCalculationResultDTO withRelief =
                    taxCalculator.calculate(allLots, rules, externalTrades, reliefExcluded);
            TaxCalculationResultDTO withoutRelief =
                    taxCalculator.calculate(allLots, rules, externalTrades, true);

            BigDecimal totalSellAmount =
                    products.stream()
                            .map(SellGuideProductDTO::getExpectedSellAmount)
                            .reduce(ZERO, BigDecimal::add);
            BigDecimal totalWeightedGain =
                    products.stream()
                            .map(SellGuideProductDTO::getWeightedGain)
                            .reduce(ZERO, BigDecimal::add);

            summary =
                    SellGuideSummaryDTO.builder()
                            .totalRecommendedSellAmount(totalSellAmount)
                            .totalWeightedGain(totalWeightedGain)
                            .estimatedTaxWithRelief(withRelief.getFinalTax())
                            .estimatedTaxWithoutRelief(withoutRelief.getFinalTax())
                            .estimatedReliefEffect(
                                    withoutRelief
                                            .getFinalTax()
                                            .subtract(withRelief.getFinalTax())
                                            .max(ZERO))
                            .taxNote(
                                    "예상 확정일("
                                            + expectedSettlementDate
                                            + ") 기준 예상치. 가정산(PROVISIONAL) 건 미포함.")
                            .build();
        }

        return SellGuideResponseDTO.builder()
                .remainingLimit(remainingLimit)
                .currentWeight(currentWeight)
                .periodLabel(periodLabel)
                .expectedSettlementDate(expectedSettlementDate)
                .isEstimate(true)
                .warnings(warnings)
                .products(products)
                .summary(summary)
                .build();
    }

    // ── 유틸 메서드 ─────────────────────────────────────────────────────────────

    /** T+2 영업일 추정 (주말 제외, 공휴일 미고려 — isEstimate=true). */
    private LocalDate computeExpectedSettlementDate(LocalDate today) {
        LocalDate date = today;
        int businessDays = 0;
        while (businessDays < 2) {
            date = date.plusDays(1);
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY
                    && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                businessDays++;
            }
        }
        return date;
    }

    private BigDecimal findWeight(List<TaxRuleDTO> rules, LocalDate date) {
        return rules.stream()
                .filter(r -> r.getRuleType() == TaxRuleType.RELIEF_RATE)
                .filter(r -> !date.isBefore(r.getValidFrom()) && !date.isAfter(r.getValidTo()))
                .findFirst()
                .map(r -> TaxCalculations.weightOf(r.getRuleValue()))
                .orElseThrow(
                        () ->
                                new AppException(
                                        ErrorType.TAX_RULE_NOT_FOUND,
                                        "expectedSettlementDate=" + date));
    }

    private String buildPeriodLabel(List<TaxRuleDTO> rules, LocalDate date, BigDecimal weight) {
        return rules.stream()
                .filter(r -> r.getRuleType() == TaxRuleType.RELIEF_RATE)
                .filter(r -> !date.isBefore(r.getValidFrom()) && !date.isAfter(r.getValidTo()))
                .findFirst()
                .map(
                        r ->
                                r.getValidFrom().getMonthValue()
                                        + "~"
                                        + r.getValidTo().getMonthValue()
                                        + "월 구간 ("
                                        + weight.multiply(BigDecimal.valueOf(100))
                                                .stripTrailingZeros()
                                                .toPlainString()
                                        + "%)")
                .orElse("구간 정보 없음");
    }

    private BigDecimal computeGainRate(BigDecimal currentPriceKrw, BigDecimal purchasePriceKrw) {
        if (currentPriceKrw == null || currentPriceKrw.compareTo(ZERO) <= 0) return null;
        return currentPriceKrw
                .subtract(purchasePriceKrw)
                .divide(currentPriceKrw, 4, RoundingMode.HALF_UP);
    }

    private SellGuideProductDTO buildFailedProduct(HeldLotDTO rep, List<HeldLotDTO> lots) {
        List<SellGuideLotDTO> lotDTOs =
                lots.stream()
                        .map(
                                lot ->
                                        SellGuideLotDTO.builder()
                                                .inboundDetailId(lot.getInboundDetailId())
                                                .availableQty(lot.getCurrentQty())
                                                .purchasePriceKrw(
                                                        lot.getPurchasePrice()
                                                                .multiply(lot.getPurchaseFxRate()))
                                                .gainRate(null)
                                                .recommendedQty(ZERO)
                                                .build())
                        .collect(Collectors.toList());

        return SellGuideProductDTO.builder()
                .foreignProductId(rep.getForeignProductId())
                .ticker(rep.getTicker())
                .productName(rep.getName())
                .currency(rep.getCurrency())
                .currentPriceKrw(null)
                .priceBaseDate(null)
                .fxRateBaseDate(null)
                .effectiveGainRate(null)
                .recommendedQty(ZERO)
                .expectedSellAmount(ZERO)
                .expectedGain(ZERO)
                .weightedGain(ZERO)
                .recommended(false)
                .lots(lotDTOs)
                .build();
    }

    private List<SellGuideLotDTO> buildLotDTOs(
            List<HeldLotDTO> lots, BigDecimal priceKrw, Long productId) {
        List<SellGuideLotDTO> result = new ArrayList<>();
        // consumedQty는 LotEntry에 저장했으나 deques는 이미 소비됨
        // lots와 동일 순서로 iterate하면서 consumed는 0으로 초기화 (FIFO 표시 목적)
        // 정확한 consumedQty는 ProductResult에서 총량만 알 수 있음 — lot별 배분은 표시용으로 간략화
        for (HeldLotDTO lot : lots) {
            BigDecimal purchasePriceKrw = lot.getPurchasePrice().multiply(lot.getPurchaseFxRate());
            BigDecimal gainRate = computeGainRate(priceKrw, purchasePriceKrw);
            result.add(
                    SellGuideLotDTO.builder()
                            .inboundDetailId(lot.getInboundDetailId())
                            .availableQty(lot.getCurrentQty())
                            .purchasePriceKrw(purchasePriceKrw)
                            .gainRate(gainRate)
                            .recommendedQty(ZERO) // 상세 배분은 summary에서 총량으로 확인
                            .build());
        }
        return result;
    }

    /** 추천 종목별 가상 SellLotDTO 생성. weight는 TaxCalculator가 finalAt으로 rules에서 조회. */
    private List<SellLotDTO> buildVirtualLots(
            List<SellGuideProductDTO> products,
            Map<Long, List<HeldLotDTO>> lotsByProduct,
            LocalDate expectedSettlementDate,
            Map<Long, BigDecimal> priceKrwByProduct) {

        List<SellLotDTO> virtualLots = new ArrayList<>();
        for (SellGuideProductDTO product : products) {
            if (!product.isRecommended()) continue;

            BigDecimal priceKrw = priceKrwByProduct.get(product.getForeignProductId());
            BigDecimal qtyLeft = product.getRecommendedQty();
            List<HeldLotDTO> lots = lotsByProduct.get(product.getForeignProductId());
            if (lots == null || priceKrw == null) continue;

            // FIFO 순으로 추천 수량 배분
            for (HeldLotDTO lot : lots) {
                if (qtyLeft.compareTo(ZERO) <= 0) break;
                BigDecimal consumed = qtyLeft.min(lot.getCurrentQty());
                qtyLeft = qtyLeft.subtract(consumed);

                virtualLots.add(
                        SellLotDTO.builder()
                                .accountId(null) // TaxCalculator가 accountId를 직접 쓰지 않음
                                .inboundDetailId(lot.getInboundDetailId())
                                .sellQty(consumed)
                                .finalAt(expectedSettlementDate)
                                .finalAmount(consumed.multiply(priceKrw))
                                .purchasePrice(lot.getPurchasePrice())
                                .purchaseFxRate(lot.getPurchaseFxRate())
                                .productLabel(lot.getProductLabel())
                                .build());
            }
        }
        return virtualLots;
    }

    private SellGuideResponseDTO emptyResponse(
            BigDecimal remainingLimit,
            BigDecimal weight,
            String periodLabel,
            LocalDate expectedSettlementDate,
            String warningMsg) {
        return SellGuideResponseDTO.builder()
                .remainingLimit(remainingLimit)
                .currentWeight(weight)
                .periodLabel(periodLabel)
                .expectedSettlementDate(expectedSettlementDate)
                .isEstimate(true)
                .warnings(List.of(warningMsg))
                .products(List.of())
                .summary(null)
                .build();
    }

    // ── 내부 작업용 클래스 ──────────────────────────────────────────────────────

    private static class LotEntry {
        final Long inboundDetailId;
        BigDecimal workingQty;
        final BigDecimal purchasePriceKrw;
        final BigDecimal gainRate;
        BigDecimal consumedQty = ZERO;

        LotEntry(
                Long inboundDetailId,
                BigDecimal workingQty,
                BigDecimal purchasePriceKrw,
                BigDecimal gainRate) {
            this.inboundDetailId = inboundDetailId;
            this.workingQty = workingQty;
            this.purchasePriceKrw = purchasePriceKrw;
            this.gainRate = gainRate;
        }
    }

    private static class ProductResult {
        BigDecimal recommendedQty = ZERO;
        BigDecimal expectedSellAmount = ZERO;
        BigDecimal expectedGain = ZERO;

        void add(BigDecimal qty, BigDecimal sellAmount, BigDecimal gain) {
            recommendedQty = recommendedQty.add(qty);
            expectedSellAmount = expectedSellAmount.add(sellAmount);
            expectedGain = expectedGain.add(gain);
        }
    }
}
