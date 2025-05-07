package com.fourj.searchservice.dto;

import com.fourj.searchservice.document.ProductDocument;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(indexName = "products")
public class ProductIndexDto {

    @Id
    private String id;
    
    @Field(type = FieldType.Text, analyzer = "standard_lowercase")
    private String name;
    
    @Field(type = FieldType.Text, analyzer = "standard_lowercase")
    private String description;
    
    @Field(type = FieldType.Double)
    private BigDecimal price;
    
    @Field(type = FieldType.Keyword)
    private String imageUrl;
    
    @Field(type = FieldType.Keyword)
    private String categoryName;
    
    @Field(type = FieldType.Boolean)
    private boolean active;
    
    @Field(type = FieldType.Boolean)
    private Boolean inStock;
    
    @Field(type = FieldType.Float)
    private Float rating;

    // Phương thức chuyển đổi từ ProductDocument
    public static ProductIndexDto fromProductDocument(ProductDocument doc) {
        return ProductIndexDto.builder()
                .id(doc.getId())
                .name(doc.getName())
                .description(doc.getDescription())
                .price(doc.getPrice())
                .imageUrl(doc.getImageUrl())
                .categoryName(doc.getCategoryName())
                .active(doc.isActive())
                .inStock(doc.getInStock())
                .rating(doc.getRating())
                .build();
    }
} 