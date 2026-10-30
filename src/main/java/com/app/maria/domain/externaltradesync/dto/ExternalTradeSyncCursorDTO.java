package com.app.maria.domain.externaltradesync.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder
public class ExternalTradeSyncCursorDTO {

    private Long cursorId;
    private Long customerId;
    private LocalDate lastSyncedTradeDate;
    private LocalDateTime updatedAt;
}
