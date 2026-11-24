package com.app.maria.domain.account.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.dto.request.AccountReapplyRequestDTO;
import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.global.audit.dto.AuditActor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public interface AccountTransactionalService {
    AccountDTO applyAs(
            AuditActor actor, AccountDTO account, LocalDateTime appliedAt, boolean autoApprove);

    AccountDTO updateLimit(
            Long adminId,
            Long customerId,
            BigDecimal expectedCurrentLimit,
            BigDecimal newLimit,
            LocalDateTime changedAt);

    AccountDTO apply(
            Long adminId, AccountDTO account, LocalDateTime appliedAt, boolean autoApprove);

    AccountDTO approve(
            Long adminId, Long accountId, BigDecimal expectedLimit, LocalDateTime openedAt);

    AccountDTO reject(Long adminId, Long accountId, String reason, LocalDateTime changedAt);

    AccountDTO reapply(
            Long adminId,
            Long accountId,
            AccountReapplyRequestDTO request,
            LocalDateTime appliedAt);

    AccountDTO override(Long adminId, Long accountId, String reason, LocalDateTime openedAt);

    /**
     * account.amount를 직접 변경하는 유일한 정당 진입점. RIA 내 현금 직접입금은 금지되어 있음.(D2), 이 메서드는 매도대금 가환전에서만 호출됨. 새로운
     * 호출자를 추가하기 전에 AccountAmountWritePathTest를 반드시 확인할 것.
     */
    void updateAmount(AccountDTO newAmountAccount);

    /**
     * 계좌 세제혜택 상태를 바꾸고 변경 이력을 남긴다.
     *
     * <p>전이 규칙
     *
     * <ul>
     *   <li>POSSIBLE ↔ REDUCED : 왕복 허용. 외부 순매수가 상계로 0이 되면 되돌아온다
     *   <li>→ IMPOSSIBLE : 제도적 배제(한도초과·조기인출). 다른 상태를 덮어쓴다
     *   <li>IMPOSSIBLE → : 복구 경로 없음
     * </ul>
     *
     * @return 실제로 바뀌었으면 true. 같은 상태거나 전이가 허용되지 않으면 false(이력도 남기지 않음)
     */
    boolean changeBenefit(
            Long accountId, BenefitType newStatus, String reason, LocalDateTime changedAt);

    /**
     * 여러 계좌의 세제혜택 상태를 한 번에 바꾼다. 전이 규칙은 {@link #changeBenefit}과 같다.
     *
     * <p>세액 스냅샷 배치가 청크(200계좌)마다 부른다. 계좌 행을 id 순으로 잠근 뒤 바뀌는 계좌만 묶어서 UPDATE하고, 이력도 한 번에 넣는다.
     *
     * @param newStatusByAccountId 계좌 id → 바꿀 상태
     * @return 실제로 바뀐 계좌 수(IMPOSSIBLE·같은 상태·없는 계좌는 제외)
     */
    int changeBenefits(
            Map<Long, BenefitType> newStatusByAccountId, String reason, LocalDateTime changedAt);
}
