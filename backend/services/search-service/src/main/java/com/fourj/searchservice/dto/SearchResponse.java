package com.fourj.searchservice.dto;

import com.fourj.searchservice.document.ProductDocument;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResponse {
    private long totalHits;
    private int page;
    private int size;
    private List<ProductIndexDto> products;
    private List<String> suggestedTerms;
    private Map<String, List<FacetEntry>> facets;
    private String searchTime;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FacetEntry {
        private String key;
        private long count;
    }
}