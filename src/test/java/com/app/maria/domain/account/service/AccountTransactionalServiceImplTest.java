package com.app.maria.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountBenefitLogDTO;
import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.dto.request.AccountReapplyRequestDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.AuditLogReasonCode;
import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.domain.account.type.Status;
import com.app.maria.global.audit.dto.AuditActor;
import com.app.maria.global.audit.dto.AuditLogDTO;
import com.app.maria.global.audit.service.AuditLogService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class AccountTransactionalServiceImplTest {
    private static final Long CUSTOMER_ID = 1L;
    private static final Long ACCOUNT_ID = 10L;
    private static final Long ADMIN_ID = 99L;
    private static final BigDecimal LIMIT = BigDecimal.valueOf(30_000_000L);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 2, 10, 30);

    @Mock private AccountMapper accountMapper;
    @Mock private AccountLogService accountLogService;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private AccountTransactionalServiceImpl service;

    @Test
    void updateLimitRejectsAmountBelowOwnUsedAndReservedAmount() {
        when(accountMapper.selectByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(account(Status.OPENED, LIMIT)));
        when(accountMapper.selectOwnUsedAndReservedAmount(ACCOUNT_ID))
                .thenReturn(BigDecimal.valueOf(20_000_000L));

        assertThatThrownBy(
                        () ->
                                service.updateLimit(
                                        ADMIN_ID,
                                        CUSTOMER_ID,
                                        LIMIT,
                                        BigDecimal.valueOf(10_000_000L),
                                        NOW))
                .isInstanceOf(AppException.class)
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_LIMIT_BELOW_USED_AMOUNT));

        verify(accountMapper, never())
                .updateLimit(
                        anyLong(), any(Status.class), any(BigDecimal.class), any(BigDecimal.class));
    }

    @Test
    void updateLimitRejectsUnsupportedAccountStateWithDedicatedError() {
        when(accountMapper.selectByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(account(Status.REJECTED, LIMIT)));

        assertThatThrownBy(
                        () ->
                                service.updateLimit(
                                        ADMIN_ID,
                                        CUSTOMER_ID,
                                        LIMIT,
                                        BigDecimal.valueOf(40_000_000L),
                                        NOW))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_STATE_NOT_ALLOWED));
    }

    @Test
    void updateLimitRejectsUnchangedLimitWithDedicatedError() {
        when(accountMapper.selectByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(account(Status.OPENED, LIMIT)));

        assertThatThrownBy(() -> service.updateLimit(ADMIN_ID, CUSTOMER_ID, LIMIT, LIMIT, NOW))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_LIMIT_UNCHANGED));
    }

    @Test
    void applyReportsApplicationSaveFailureWithDedicatedError() {
        when(accountMapper.existsByCustomerId(CUSTOMER_ID)).thenReturn(false);
        when(accountMapper.insertApplication(any(AccountDTO.class))).thenReturn(0);

        assertThatThrownBy(
                        () -> service.apply(ADMIN_ID, account(Status.APPLIED, LIMIT), NOW, false))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_APPLICATION_SAVE_FAILED));
    }

    @Test
    void updateLimitRejectsStaleExpectedCurrentLimitBeforeUpdating() {
        when(accountMapper.selectByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(account(Status.OPENED, LIMIT)));

        assertThatThrownBy(
                        () ->
                                service.updateLimit(
                                        ADMIN_ID,
                                        CUSTOMER_ID,
                                        BigDecimal.valueOf(20_000_000L),
                                        BigDecimal.valueOf(40_000_000L),
                                        NOW))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ACCOUNT_CONCURRENT_MODIFICATION.getMessage());

        verify(accountMapper, never())
                .updateLimit(
                        anyLong(), any(Status.class), any(BigDecimal.class), any(BigDecimal.class));
    }

    @Test
    void updateLimitUsesClientExpectedLimitForCompareAndSet() {
        AccountDTO current = account(Status.OPENED, LIMIT);
        AccountDTO updated = account(Status.OPENED, BigDecimal.valueOf(40_000_000L));
        when(accountMapper.selectByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(current));
        when(accountMapper.selectOwnUsedAndReservedAmount(ACCOUNT_ID)).thenReturn(BigDecimal.ZERO);
        when(accountMapper.updateLimit(
                        ACCOUNT_ID, Status.OPENED, LIMIT, BigDecimal.valueOf(40_000_000L)))
                .thenReturn(1);
        when(accountMapper.selectByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(updated));

        service.updateLimit(ADMIN_ID, CUSTOMER_ID, LIMIT, BigDecimal.valueOf(40_000_000L), NOW);

        verify(accountMapper)
                .updateLimit(ACCOUNT_ID, Status.OPENED, LIMIT, BigDecimal.valueOf(40_000_000L));
        assertAuditLog(
                "limitAmount=30000000",
                "limitAmount=40000000",
                AuditLogReasonCode.ACCOUNT_CHANGE_LIMIT_AMOUNT);
    }

    @Test
    void applyKeepsAccountAppliedWhenAutoApprovalConditionIsNotMet() {
        AccountDTO applied = account(Status.APPLIED, LIMIT);
        when(accountMapper.existsByCustomerId(CUSTOMER_ID)).thenReturn(false);
        when(accountMapper.insertApplication(any(AccountDTO.class))).thenReturn(1);
        when(accountMapper.selectByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(applied));

        AccountDTO result = service.apply(ADMIN_ID, account(Status.APPLIED, LIMIT), NOW, false);

        assertThat(result.getStatus()).isEqualTo(Status.APPLIED);
        verify(accountMapper, never()).reject(any(AccountDTO.class));
        verify(accountMapper, never()).approve(any(AccountDTO.class));
        assertAuditLog(null, "APPLIED", AuditLogReasonCode.ACCOUNT_APPLY);
    }

    @Test
    void reapplyWritesAuditLogWithStatusTransition() {
        AccountDTO rejected = account(Status.REJECTED, LIMIT);
        AccountDTO applied = account(Status.APPLIED, LIMIT);
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(rejected), Optional.of(applied));
        when(accountMapper.reapply(any(AccountDTO.class))).thenReturn(1);

        service.reapply(
                ADMIN_ID,
                ACCOUNT_ID,
                AccountReapplyRequestDTO.builder().limitAmount(LIMIT).build(),
                NOW);

        assertAuditLog("REJECTED", "APPLIED", AuditLogReasonCode.ACCOUNT_REAPPLY);
    }

    @Test
    void applyWithAutoApprovalRecordsPossibleBenefit() {
        AccountDTO applied = account(Status.APPLIED, LIMIT);
        AccountDTO opened = openedAccount();
        when(accountMapper.existsByCustomerId(CUSTOMER_ID)).thenReturn(false);
        when(accountMapper.insertApplication(any(AccountDTO.class))).thenReturn(1);
        when(accountMapper.selectByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(applied), Optional.of(opened));
        when(accountMapper.approve(any(AccountDTO.class))).thenReturn(1);

        service.apply(ADMIN_ID, account(Status.APPLIED, LIMIT), NOW, true);

        verify(accountLogService).recordBenefitChange(opened, null, NOW, "계좌 개설에 따른 세제혜택 가능");
        ArgumentCaptor<AuditLogDTO> auditLogCaptor = ArgumentCaptor.forClass(AuditLogDTO.class);
        verify(auditLogService, times(2)).log(auditLogCaptor.capture());
        assertThat(auditLogCaptor.getAllValues())
                .extracting(
                        AuditLogDTO::getBeforeValue,
                        AuditLogDTO::getAfterValue,
                        AuditLogDTO::getReasonCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                null, "APPLIED", AuditLogReasonCode.ACCOUNT_APPLY.name()),
                        org.assertj.core.groups.Tuple.tuple(
                                "APPLIED", "OPENED", AuditLogReasonCode.ACCOUNT_OPENED.name()));
    }

    @Test
    void approveUsesValidatedLimitAsOptimisticLockCondition() {
        AccountDTO applied = account(Status.APPLIED, BigDecimal.valueOf(40_000_000L));
        AccountDTO opened = openedAccount();
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(applied), Optional.of(opened));
        when(accountMapper.approve(any(AccountDTO.class))).thenReturn(1);

        service.approve(ADMIN_ID, ACCOUNT_ID, LIMIT, NOW);

        ArgumentCaptor<AccountDTO> captor = ArgumentCaptor.forClass(AccountDTO.class);
        verify(accountMapper).approve(captor.capture());
        assertThat(captor.getValue().getLimitAmount()).isEqualByComparingTo(LIMIT);

        ArgumentCaptor<AuditLogDTO> auditLogCaptor = ArgumentCaptor.forClass(AuditLogDTO.class);
        verify(auditLogService).log(auditLogCaptor.capture());
        assertThat(auditLogCaptor.getValue())
                .extracting(
                        AuditLogDTO::getAdminId,
                        AuditLogDTO::getTargetTable,
                        AuditLogDTO::getTargetPk,
                        AuditLogDTO::getBeforeValue,
                        AuditLogDTO::getAfterValue,
                        AuditLogDTO::getReasonCode)
                .containsExactly(
                        ADMIN_ID,
                        "ACCOUNT",
                        "10",
                        "APPLIED",
                        "OPENED",
                        AuditLogReasonCode.ACCOUNT_OPENED.name());
        verify(accountLogService).recordBenefitChange(opened, null, NOW, "계좌 개설에 따른 세제혜택 가능");
    }

    @Test
    void approveReportsConflictWhenValidatedLimitChangedBeforeUpdate() {
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(account(Status.APPLIED, LIMIT)));
        when(accountMapper.approve(any(AccountDTO.class))).thenReturn(0);

        assertThatThrownBy(() -> service.approve(ADMIN_ID, ACCOUNT_ID, LIMIT, NOW))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ACCOUNT_CONCURRENT_MODIFICATION.getMessage());
    }

    @Test
    void approveReportsAccountNumberGenerationFailureAfterRetryLimit() {
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(account(Status.APPLIED, LIMIT)));
        when(accountMapper.approve(any(AccountDTO.class)))
                .thenThrow(new DuplicateKeyException("duplicate account number"));

        assertThatThrownBy(() -> service.approve(ADMIN_ID, ACCOUNT_ID, LIMIT, NOW))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_NUMBER_GENERATION_FAILED));

        verify(accountMapper, times(5)).approve(any(AccountDTO.class));
    }

    @Test
    void approveReportsStatusPostconditionFailureWithDedicatedError() {
        AccountDTO applied = account(Status.APPLIED, LIMIT);
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(applied), Optional.of(applied));
        when(accountMapper.approve(any(AccountDTO.class))).thenReturn(1);

        assertThatThrownBy(() -> service.approve(ADMIN_ID, ACCOUNT_ID, LIMIT, NOW))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_STATE_UPDATE_FAILED));
    }

    @Test
    void approveReportsBenefitPostconditionFailureWithDedicatedError() {
        AccountDTO applied = account(Status.APPLIED, LIMIT);
        AccountDTO openedWithoutBenefit = account(Status.OPENED, LIMIT);
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(applied), Optional.of(openedWithoutBenefit));
        when(accountMapper.approve(any(AccountDTO.class))).thenReturn(1);

        assertThatThrownBy(() -> service.approve(ADMIN_ID, ACCOUNT_ID, LIMIT, NOW))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_BENEFIT_UPDATE_FAILED));

        verify(accountLogService)
                .recordStatusChange(openedWithoutBenefit, Status.APPLIED, NOW, "사용자 계좌 개설");
    }

    @Test
    void overrideRecordsPossibleBenefit() {
        AccountDTO rejected = account(Status.REJECTED, LIMIT);
        AccountDTO opened = openedAccount();
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(rejected), Optional.of(opened));
        when(accountMapper.overrideToOpened(any(AccountDTO.class))).thenReturn(1);

        service.override(ADMIN_ID, ACCOUNT_ID, "관리자 오버라이드 승인", NOW);

        verify(accountLogService).recordBenefitChange(opened, null, NOW, "계좌 개설에 따른 세제혜택 가능");
        assertAuditLog("REJECTED", "OPENED", AuditLogReasonCode.ACCOUNT_OVERRIDE_OPENED);
    }

    @Test
    void rejectWritesAuditLogWithStatusTransition() {
        AccountDTO applied = account(Status.APPLIED, LIMIT);
        AccountDTO rejected = account(Status.REJECTED, LIMIT);
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(applied), Optional.of(rejected));
        when(accountMapper.reject(applied)).thenReturn(1);

        service.reject(ADMIN_ID, ACCOUNT_ID, "심사 반려", NOW);

        assertAuditLog("APPLIED", "REJECTED", AuditLogReasonCode.ACCOUNT_REJECTED);
    }

    @Test
    void overrideWritesAuditLogWithStatusTransition() {
        AccountDTO rejected = account(Status.REJECTED, LIMIT);
        AccountDTO opened = openedAccount();
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(rejected), Optional.of(opened));
        when(accountMapper.overrideToOpened(any(AccountDTO.class))).thenReturn(1);

        service.override(ADMIN_ID, ACCOUNT_ID, "재심사 승인", NOW);

        assertAuditLog("REJECTED", "OPENED", AuditLogReasonCode.ACCOUNT_OVERRIDE_OPENED);
    }

    @Test
    void updateAmountPassesProvisionalDeltaToAtomicBalanceUpdate() {
        AccountDTO increase =
                AccountDTO.builder()
                        .accountId(ACCOUNT_ID)
                        .amount(BigDecimal.valueOf(267_300L))
                        .build();
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(account(Status.OPENED, LIMIT)));
        when(accountMapper.updateProvisionalAmount(increase)).thenReturn(1);

        service.updateAmount(increase);

        ArgumentCaptor<AccountDTO> captor = ArgumentCaptor.forClass(AccountDTO.class);
        verify(accountMapper).updateProvisionalAmount(captor.capture());
        assertThat(captor.getValue().getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("267300");
    }

    @Test
    void updateAmountThrowsWhenNoAccountBalanceIsUpdated() {
        AccountDTO increase =
                AccountDTO.builder().accountId(ACCOUNT_ID).amount(BigDecimal.ONE).build();
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(account(Status.OPENED, LIMIT)));
        when(accountMapper.updateProvisionalAmount(increase)).thenReturn(0);

        assertThatThrownBy(() -> service.updateAmount(increase))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ACCOUNT_AMOUNT_UPDATE_FAILED.getMessage());
    }

    @Test
    void customerApplicationRecordsCustomerAudit() {
        AccountDTO applied = account(Status.APPLIED, LIMIT);
        when(accountMapper.insertApplication(any())).thenReturn(1);
        when(accountMapper.selectByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(applied));

        assertThat(
                        service.applyAs(AuditActor.customer(CUSTOMER_ID), applied, NOW, false)
                                .getStatus())
                .isEqualTo(Status.APPLIED);

        verify(accountLogService).recordStatusChange(applied, null, NOW, "최초 개설 신청");
        ArgumentCaptor<AuditLogDTO> audit = ArgumentCaptor.forClass(AuditLogDTO.class);
        verify(auditLogService, org.mockito.Mockito.atLeastOnce()).log(audit.capture());
        assertThat(audit.getAllValues())
                .allSatisfy(
                        entry -> {
                            assertThat(entry.getAdminId()).isNull();
                            assertThat(entry.getActorType()).isEqualTo(AuditActor.Type.CUSTOMER);
                            assertThat(entry.getActorId()).isEqualTo(CUSTOMER_ID);
                        });
        verify(accountMapper, never()).approve(any());
    }

    @Test
    void customerAutoApprovalPreservesStatusAndBenefitHistory() {
        AccountDTO applied = account(Status.APPLIED, LIMIT);
        AccountDTO opened = openedAccount();
        when(accountMapper.insertApplication(any())).thenReturn(1);
        when(accountMapper.selectByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(applied), Optional.of(opened));
        when(accountMapper.approve(any())).thenReturn(1);

        assertThat(
                        service.applyAs(AuditActor.customer(CUSTOMER_ID), applied, NOW, true)
                                .getStatus())
                .isEqualTo(Status.OPENED);

        verify(accountLogService).recordStatusChange(applied, null, NOW, "최초 개설 신청");
        verify(accountLogService).recordStatusChange(opened, Status.APPLIED, NOW, "자동 판정 승인");
        verify(accountLogService).recordBenefitChange(opened, null, NOW, "계좌 개설에 따른 세제혜택 가능");
        ArgumentCaptor<AuditLogDTO> audit = ArgumentCaptor.forClass(AuditLogDTO.class);
        verify(auditLogService, org.mockito.Mockito.atLeastOnce()).log(audit.capture());
        assertThat(audit.getAllValues())
                .allSatisfy(
                        entry -> {
                            assertThat(entry.getAdminId()).isNull();
                            assertThat(entry.getActorType()).isEqualTo(AuditActor.Type.CUSTOMER);
                            assertThat(entry.getActorId()).isEqualTo(CUSTOMER_ID);
                        });
    }

    @Test
    void repeatedCustomerApplicationDoesNotSaveAgain() {
        when(accountMapper.existsByCustomerId(CUSTOMER_ID)).thenReturn(true);
        assertThatThrownBy(
                        () ->
                                service.applyAs(
                                        AuditActor.customer(CUSTOMER_ID),
                                        account(Status.APPLIED, LIMIT),
                                        NOW,
                                        false))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ACCOUNT_ALREADY_EXISTS.getMessage());
        verify(accountMapper, never()).insertApplication(any());
        org.mockito.Mockito.verifyNoInteractions(accountLogService, auditLogService);
    }

    @Test
    void concurrentDuplicateInsertBecomesExistingAccountError() {
        when(accountMapper.insertApplication(any()))
                .thenThrow(new DuplicateKeyException("customer"));
        assertThatThrownBy(
                        () ->
                                service.applyAs(
                                        AuditActor.customer(CUSTOMER_ID),
                                        account(Status.APPLIED, LIMIT),
                                        NOW,
                                        true))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ACCOUNT_ALREADY_EXISTS.getMessage());
        verify(accountMapper, never()).approve(any());
        org.mockito.Mockito.verifyNoInteractions(accountLogService, auditLogService);
    }

    @Test
    void missingAdminActorFailsBeforeWritingInsteadOfSkippingAudit() {
        assertThatThrownBy(() -> service.apply(null, account(Status.APPLIED, LIMIT), NOW, false))
                .isInstanceOf(NullPointerException.class);
        org.mockito.Mockito.verifyNoInteractions(accountMapper, auditLogService);
    }

    // ---- changeBenefits: 세액 스냅샷 배치의 청크 단위 세제혜택 전환 ----

    private static final String BULK_REASON = "일별 세액 스냅샷 배치 판정";

    private AccountDTO benefitAccount(long accountId, BenefitType benefit) {
        return AccountDTO.builder().accountId(accountId).benefit(benefit).build();
    }

    @Test
    void changeBenefitsSkipsImpossibleSameAndMissingAndLogsOnlyChanged() {
        Map<Long, BenefitType> request = new LinkedHashMap<>();
        request.put(1L, BenefitType.REDUCED); // POSSIBLE → REDUCED
        request.put(2L, BenefitType.POSSIBLE); // REDUCED → POSSIBLE
        request.put(3L, BenefitType.POSSIBLE); // IMPOSSIBLE: 복구 경로 없음
        request.put(4L, BenefitType.POSSIBLE); // 같은 상태
        request.put(5L, BenefitType.REDUCED); // 조회 안 됨
        when(accountMapper.selectBenefitsForUpdate(anyList()))
                .thenReturn(
                        List.of(
                                benefitAccount(1L, BenefitType.POSSIBLE),
                                benefitAccount(2L, BenefitType.REDUCED),
                                benefitAccount(3L, BenefitType.IMPOSSIBLE),
                                benefitAccount(4L, BenefitType.POSSIBLE)));
        when(accountMapper.updateBenefits(List.of(1L), BenefitType.REDUCED, BenefitType.POSSIBLE))
                .thenReturn(1);
        when(accountMapper.updateBenefits(List.of(2L), BenefitType.POSSIBLE, BenefitType.REDUCED))
                .thenReturn(1);

        int changed = service.changeBenefits(request, BULK_REASON, NOW);

        assertThat(changed).isEqualTo(2);
        ArgumentCaptor<List<Long>> lockedIds = ArgumentCaptor.forClass(List.class);
        verify(accountMapper).selectBenefitsForUpdate(lockedIds.capture());
        assertThat(lockedIds.getValue()).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
        verify(accountMapper, times(2)).updateBenefits(anyList(), any(), any());
        ArgumentCaptor<List<AccountBenefitLogDTO>> logs = ArgumentCaptor.forClass(List.class);
        verify(accountLogService).recordBenefitChanges(logs.capture());
        assertThat(logs.getValue())
                .extracting(
                        AccountBenefitLogDTO::getAccountId,
                        AccountBenefitLogDTO::getPrevStatus,
                        AccountBenefitLogDTO::getNewStatus,
                        AccountBenefitLogDTO::getChangedAt,
                        AccountBenefitLogDTO::getReason)
                .containsExactlyInAnyOrder(
                        tuple(1L, BenefitType.POSSIBLE, BenefitType.REDUCED, NOW, BULK_REASON),
                        tuple(2L, BenefitType.REDUCED, BenefitType.POSSIBLE, NOW, BULK_REASON));
    }

    @Test
    void changeBenefitsGroupsSameTransitionIntoOneUpdate() {
        Map<Long, BenefitType> request = new LinkedHashMap<>();
        request.put(1L, BenefitType.REDUCED);
        request.put(2L, BenefitType.REDUCED);
        when(accountMapper.selectBenefitsForUpdate(anyList()))
                .thenReturn(
                        List.of(
                                benefitAccount(1L, BenefitType.POSSIBLE),
                                benefitAccount(2L, BenefitType.POSSIBLE)));
        ArgumentCaptor<List<Long>> updatedIds = ArgumentCaptor.forClass(List.class);
        when(accountMapper.updateBenefits(
                        updatedIds.capture(), eq(BenefitType.REDUCED), eq(BenefitType.POSSIBLE)))
                .thenReturn(2);

        assertThat(service.changeBenefits(request, BULK_REASON, NOW)).isEqualTo(2);
        verify(accountMapper, times(1)).updateBenefits(anyList(), any(), any());
        assertThat(updatedIds.getValue()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void changeBenefitsUsesNullExpectedStatusWhenBenefitIsNull() {
        when(accountMapper.selectBenefitsForUpdate(anyList()))
                .thenReturn(List.of(benefitAccount(1L, null)));
        when(accountMapper.updateBenefits(List.of(1L), BenefitType.POSSIBLE, null)).thenReturn(1);

        assertThat(service.changeBenefits(Map.of(1L, BenefitType.POSSIBLE), BULK_REASON, NOW))
                .isEqualTo(1);
    }

    @Test
    void changeBenefitsThrowsWhenUpdatedRowCountDiffers() {
        when(accountMapper.selectBenefitsForUpdate(anyList()))
                .thenReturn(List.of(benefitAccount(1L, BenefitType.POSSIBLE)));
        when(accountMapper.updateBenefits(List.of(1L), BenefitType.REDUCED, BenefitType.POSSIBLE))
                .thenReturn(0);

        assertThatThrownBy(
                        () ->
                                service.changeBenefits(
                                        Map.of(1L, BenefitType.REDUCED), BULK_REASON, NOW))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_BENEFIT_UPDATE_FAILED));
        verify(accountLogService, never()).recordBenefitChanges(anyList());
    }

    @Test
    void changeBenefitsDoesNothingForEmptyRequest() {
        assertThat(service.changeBenefits(Map.of(), BULK_REASON, NOW)).isZero();

        verifyNoInteractions(accountMapper, accountLogService);
    }

    private AccountDTO account(Status status, BigDecimal limit) {
        return AccountDTO.builder()
                .accountId(ACCOUNT_ID)
                .customerId(CUSTOMER_ID)
                .status(status)
                .limitAmount(limit)
                .build();
    }

    private AccountDTO openedAccount() {
        AccountDTO account = account(Status.OPENED, LIMIT);
        account.setBenefit(BenefitType.POSSIBLE);
        return account;
    }

    private void assertAuditLog(
            String beforeValue, String afterValue, AuditLogReasonCode reasonCode) {
        ArgumentCaptor<AuditLogDTO> captor = ArgumentCaptor.forClass(AuditLogDTO.class);
        verify(auditLogService).log(captor.capture());
        AuditLogDTO auditLog = captor.getValue();
        assertThat(auditLog.getAdminId()).isEqualTo(ADMIN_ID);
        assertThat(auditLog.getTargetTable()).isEqualTo("ACCOUNT");
        assertThat(auditLog.getTargetPk()).isEqualTo(String.valueOf(ACCOUNT_ID));
        assertThat(auditLog.getBeforeValue()).isEqualTo(beforeValue);
        assertThat(auditLog.getAfterValue()).isEqualTo(afterValue);
        assertThat(auditLog.getReasonCode()).isEqualTo(reasonCode.name());
    }
}
