package com.app.maria.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountBenefitLogDTO;
import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.dto.AccountStatusLogDTO;
import com.app.maria.domain.account.mapper.AccountBenefitLogMapper;
import com.app.maria.domain.account.mapper.AccountStatusLogMapper;
import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.domain.account.type.Status;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountLogServiceImplTest {
    private static final Long ACCOUNT_ID = 10L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 2, 10, 30);

    @Mock private AccountStatusLogMapper accountStatusLogMapper;
    @Mock private AccountBenefitLogMapper accountBenefitLogMapper;
    @Mock private BusinessClockService businessClockService;
    @InjectMocks private AccountLogServiceImpl service;

    @Test
    void statusLogSaveFailureUsesDedicatedError() {
        when(accountStatusLogMapper.insertLog(any(AccountStatusLogDTO.class))).thenReturn(0);

        assertThatThrownBy(
                        () -> service.recordStatusChange(account(), Status.APPLIED, NOW, "상태 변경"))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_STATUS_LOG_SAVE_FAILED));
    }

    @Test
    void benefitLogSaveFailureUsesDedicatedError() {
        when(accountBenefitLogMapper.insertLog(any(AccountBenefitLogDTO.class))).thenReturn(0);

        assertThatThrownBy(
                        () ->
                                service.recordBenefitChange(
                                        account(), BenefitType.REDUCED, NOW, "혜택 변경"))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_BENEFIT_LOG_SAVE_FAILED));
    }

    @Test
    void benefitLogsBulkSaveFailureUsesDedicatedError() {
        List<AccountBenefitLogDTO> logs =
                List.of(
                        AccountBenefitLogDTO.builder().accountId(1L).build(),
                        AccountBenefitLogDTO.builder().accountId(2L).build());
        when(accountBenefitLogMapper.insertLogs(logs)).thenReturn(1);

        assertThatThrownBy(() -> service.recordBenefitChanges(logs))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_BENEFIT_LOG_SAVE_FAILED));
    }

    @Test
    void benefitLogsBulkSkipsInsertWhenEmpty() {
        service.recordBenefitChanges(List.of());

        verify(accountBenefitLogMapper, never()).insertLogs(any());
    }

    private AccountDTO account() {
        return AccountDTO.builder()
                .accountId(ACCOUNT_ID)
                .status(Status.OPENED)
                .benefit(BenefitType.POSSIBLE)
                .build();
    }
}
