package com.app.maria.domain.account.mapper;

import com.app.maria.domain.account.dto.AccountBenefitLogDTO;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AccountBenefitLogMapper {

    // 세제혜택 상태 이력 생성
    int insertLog(AccountBenefitLogDTO accountBenefitLogDTO);

    // 세제혜택 상태 이력 여러 건을 INSERT 한 번으로 생성
    int insertLogs(@Param("logs") List<AccountBenefitLogDTO> logs);

    // 계좌별 세제혜택 상태 이력 조회: 오래된 순
    List<AccountBenefitLogDTO> selectByAccountId(Long accountId);

    // 가장 최근 세제혜택 상태 이력 조회
    Optional<AccountBenefitLogDTO> selectLatestByAccountId(Long accountId);
}
