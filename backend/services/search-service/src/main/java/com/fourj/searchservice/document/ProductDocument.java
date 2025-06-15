package com.fourj.searchservice.document;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;
import org.springframework.data.elasticsearch.core.suggest.Completion;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
@Document(indexName = "#{@elasticsearchConfig.indexSettings.products.name}")
public class ProductDocument {

    @Id
    private String id;

    @Field(type = FieldType.Text, analyzer = "standard_lowercase", searchAnalyzer = "standard_lowercase")
    private String name;

    @JsonIgnore
    @CompletionField(
            analyzer = "standard_lowercase",
            searchAnalyzer = "standard_lowercase",
            contexts = {
                    @CompletionContext(
                            name = "category",
                            type = CompletionContext.ContextMappingType.CATEGORY,
                            path = "categoryName"
                    )
            }
    )
    private Completion nameSuggest;

    @Field(type = FieldType.Text, analyzer = "standard_lowercase", searchAnalyzer = "standard_lowercase")
    private String description;

    @Field(type = FieldType.Double)
    private BigDecimal price;

    @Field(type = FieldType.Integer)
    private Integer stockQuantity;

    @Field(type = FieldType.Keyword)
    private String imageUrl;

    @Field(type = FieldType.Long)
    private Long categoryId;

    @Field(type = FieldType.Keyword)
    private String categoryName;

    @Field(type = FieldType.Nested)
    private List<ProductAttribute> attributes = new ArrayList<>();

    @Field(type = FieldType.Boolean)
    private boolean active;

    @Field(type = FieldType.Date)
    private LocalDateTime createdAt;

    @Field(type = FieldType.Date)
    private LocalDateTime updatedAt;

    // Các trường bổ sung cho tìm kiếm
    @Field(type = FieldType.Double)
    private BigDecimal originalPrice; // Giá gốc (nếu có khuyến mãi)

    @Field(type = FieldType.Double)
    private BigDecimal discountPercent; // Phần trăm giảm giá

    @Field(type = FieldType.Boolean)
    private Boolean inStock; // Có sẵn trong kho

    @Field(type = FieldType.Object)
    private Map<String, String> images; // Nhiều hình ảnh nếu có

    @Field(type = FieldType.Float)
    private Float rating; // Đánh giá sao

    @Field(type = FieldType.Integer)
    private Integer reviewCount; // Số lượng đánh giá

    @Field(type = FieldType.Integer)
    private Integer soldCount; // Số lượng đã bán

    @Field(type = FieldType.Keyword)
    private List<String> tags; // Các tag đặc biệt (hot, new, sale,...)

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ProductAttribute {
        @Field(type = FieldType.Keyword)
        private String name;

        @Field(type = FieldType.Keyword)
        private String value;

        @Field(type = FieldType.Text, analyzer = "standard_lowercase")
        private String displayName;

        @Field(type = FieldType.Text, analyzer = "standard_lowercase")
        private String displayValue;
    }
}