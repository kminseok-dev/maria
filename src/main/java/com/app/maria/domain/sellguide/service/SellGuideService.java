package com.app.maria.domain.sellguide.service;

import com.app.maria.domain.sellguide.dto.response.SellGuideResponseDTO;

public interface SellGuideService {

    SellGuideResponseDTO guide(Long customerId);
}
