package com.app.maria.domain.sellguide.api;

import com.app.maria.domain.sellguide.dto.response.SellGuideResponseDTO;
import com.app.maria.domain.sellguide.service.SellGuideService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer")
public class SellGuideApi {

    private final SellGuideService sellGuideService;

    @GetMapping("/sell-guide")
    public ResponseEntity<ApiResponseDTO<SellGuideResponseDTO>> getSellGuide(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "매도 가이드 조회 성공",
                        sellGuideService.guide(principal.customerId())));
    }
}
