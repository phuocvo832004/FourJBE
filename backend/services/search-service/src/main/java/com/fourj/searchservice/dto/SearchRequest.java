package com.fourj.searchservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class SearchRequest {
    private String query;
    private List<String> categories;
    private String brand;
    private PriceRange priceRange;
    private Map<String, List<String>> attributes;
    private SortOption sortOption;
    private int from;
    private int size;
    private boolean includeAggregations;
    private String userId;  // For personalization

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriceRange {
        private BigDecimal min;
        private BigDecimal max;
    }

    public enum SortOption {
        RELEVANCE,
        PRICE_ASC,
        PRICE_DESC,
        NEWEST,
        BEST_SELLING,
        HIGHEST_RATED
    }
}