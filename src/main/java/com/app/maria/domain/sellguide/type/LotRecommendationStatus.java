package com.app.maria.domain.sellguide.type;

public enum LotRecommendationStatus {
    /** 추천 수량 > 0 */
    RECOMMENDED,
    /** gainRate > 0이지만 한도 소진 또는 현재가 대비 잔여 한도가 너무 소액 */
    NOT_RECOMMENDED_LIMIT,
    /** 첫 FIFO lot의 gainRate <= 0, 뒤 lot 중 gainRate > 0 존재 */
    BLOCKED_BY_FIFO,
    /** 모든 lot의 gainRate <= 0 */
    EXCLUDED_NEGATIVE_GAIN,
    /** KIS 시세 또는 환율 조회 실패 */
    EXCLUDED_PRICE_FETCH_FAILED,
}
