package com.fourj.searchservice.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch._types.aggregations.AggregationRange;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.Hit;
import java.util.stream.Collectors;

import co.elastic.clients.json.JsonData;
import com.fourj.searchservice.config.ElasticsearchConfig;
import com.fourj.searchservice.document.ProductDocument;
import com.fourj.searchservice.dto.SearchRequest.PriceRange;
import com.fourj.searchservice.dto.SearchResponse.FacetEntry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import co.elastic.clients.elasticsearch.indices.*;
import co.elastic.clients.elasticsearch.indices.IndexSettings;

import co.elastic.clients.elasticsearch.indices.IndexState;

import com.fourj.searchservice.dto.ProductIndexDto;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchService {
    
    private final ElasticsearchClient client;
    private final ElasticsearchConfig elasticsearchConfig;
    private final RedisTemplate<String, Object> redisTemplate;
    
    /**
     * Tìm kiếm sản phẩm theo các tiêu chí
     */
    @CircuitBreaker(name = "elasticsearch", fallbackMethod = "searchProductsFallback")
    @Retry(name = "elasticsearch")
    @Timed("search.products")
    public com.fourj.searchservice.dto.SearchResponse searchProducts(com.fourj.searchservice.dto.SearchRequest request) throws IOException {
        log.info("Search request received: query={}, categories={}", 
                request.getQuery(), request.getCategories());
        
        Instant start = Instant.now();
        
        // Tìm kiếm từ Elasticsearch
        SearchResponse<ProductIndexDto> response = client.search(s -> {
            SearchRequest.Builder builder = new SearchRequest.Builder();
            builder.index(elasticsearchConfig.getIndexSettings().getProducts().getName());
            builder.query(buildQuery(request));
            builder.from(request.getFrom());
            builder.size(request.getSize());
            
            // Highlight settings
            Map<String, HighlightField> highlightFields = new HashMap<>();
            highlightFields.put("name", HighlightField.of(h -> h));
            highlightFields.put("description", HighlightField.of(h -> h));
            
            builder.highlight(h -> h
                    .fields(highlightFields)
                    .preTags("<em>")
                    .postTags("</em>")
                    .requireFieldMatch(false));
            
            // Sorting tối giản
            if (request.getSortOption() != null) {
                switch (request.getSortOption()) {
                    case PRICE_ASC:
                        builder.sort(s1 -> s1.field(f -> f.field("price").order(SortOrder.Asc)));
                        break;
                    case PRICE_DESC:
                        builder.sort(s1 -> s1.field(f -> f.field("price").order(SortOrder.Desc)));
                        break;
                    case RELEVANCE:
                    default:
                        builder.sort(s1 -> s1.score(sc -> sc.order(SortOrder.Desc)));
                        break;
                }
            } else {
                builder.sort(s1 -> s1.score(sc -> sc.order(SortOrder.Desc)));
            }
            
            // Aggregations cơ bản
            if (request.isIncludeAggregations()) {
                // Aggregation cho danh mục
                builder.aggregations("categories", a -> a
                        .terms(t -> t.field("categoryName").size(50)));
                
                // Aggregation cho khoảng giá
                builder.aggregations("price_ranges", a -> a
                        .range(t -> t
                                .field("price")
                                .ranges(
                                        AggregationRange.of(r -> r.to(500000.0)),
                                        AggregationRange.of(r -> r.from(500000.0).to(1000000.0)),
                                        AggregationRange.of(r -> r.from(1000000.0).to(2000000.0)),
                                        AggregationRange.of(r -> r.from(2000000.0).to(5000000.0)),
                                        AggregationRange.of(r -> r.from(5000000.0))
                                )
                        )
                );
            }
            
            return builder;
        }, ProductIndexDto.class);

        assert response.hits().total() != null;
        log.info("Search returned {} hits for query: {}",
                response.hits().total().value(), request.getQuery());
        
        // Chuyển đổi kết quả 
        com.fourj.searchservice.dto.SearchResponse result = convertToSearchResponse(response, request, start);
        return result;
    }
    
    /**
     * Xây dựng query đơn giản cho tìm kiếm cơ bản
     */
    private Query buildQuery(com.fourj.searchservice.dto.SearchRequest request) {
        BoolQuery.Builder boolQuery = new BoolQuery.Builder();
        
        // Nếu không có query text, trả về tất cả sản phẩm
        if (!StringUtils.hasText(request.getQuery())) {
            boolQuery.must(MatchAllQuery.of(m -> m)._toQuery());
        } else {
            String queryText = request.getQuery().toLowerCase().trim();
            log.debug("Building query for text: {}", queryText);

            // 1. Multi-match query cho tìm kiếm trên nhiều trường
            boolQuery.should(MultiMatchQuery.of(m -> m
                    .query(queryText)
                    .fields("name^5", "description^2", "categoryName^3")
                    .type(TextQueryType.BestFields)
                    .operator(Operator.Or)
                    .fuzziness("AUTO"))
                    ._toQuery());

            // 2. Wildcard query để tìm với dấu *
            boolQuery.should(WildcardQuery.of(w -> w
                    .field("name")
                    .value("*" + queryText + "*")
                    .caseInsensitive(true))
                    ._toQuery());

            // Đặt minimum_should_match để đảm bảo ít nhất một query phải khớp
            boolQuery.minimumShouldMatch("1");
        }
        
        // Filter queries (lọc kết quả)
        // Lọc theo danh mục
        if (request.getCategories() != null && !request.getCategories().isEmpty()) {
            boolQuery.filter(TermsQuery.of(t -> t
                    .field("categoryName")
                    .terms(f -> f
                            .value(request.getCategories().stream()
                                    .map(FieldValue::of)
                                    .collect(Collectors.toList()))))
                    ._toQuery());
        }

        PriceRange priceRange = request.getPriceRange();

        if (priceRange != null && (priceRange.getMin() != null || priceRange.getMax() != null)) {
            Query rangeQuery = Query.of(q -> q
                    .range(r -> r
                            .number(n -> {
                                n.field("price");
                                if (priceRange.getMin() != null) {
                                    n.gte(priceRange.getMin().doubleValue());
                                }
                                if (priceRange.getMax() != null) {
                                    n.lte(priceRange.getMax().doubleValue());
                                }
                                return n;
                            })
                    )
            );

            boolQuery.filter(rangeQuery);
        }




        
        // Chỉ lấy các sản phẩm đang active
        boolQuery.filter(TermQuery.of(t -> t
                .field("active")
                .value(true))
                ._toQuery());
        
        return boolQuery.build()._toQuery();
    }

    /**
     * Chuyển đổi kết quả Elasticsearch sang DTO SearchResponse (đơn giản hóa)
     */
    private com.fourj.searchservice.dto.SearchResponse convertToSearchResponse(
            SearchResponse<ProductIndexDto> response,
            com.fourj.searchservice.dto.SearchRequest request,
            Instant startTime) {
        
        List<ProductIndexDto> products = response.hits().hits().stream()
                .map(Hit::source)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        
        Map<String, List<FacetEntry>> facets = new HashMap<>();
        
        // Xử lý facets nếu request yêu cầu và có kết quả aggregations
        if (request.isIncludeAggregations() && response.aggregations() != null) {
            // Xử lý category facets
            if (response.aggregations().containsKey("categories")) {
                facets.put("categories", new ArrayList<>());
                response.aggregations().get("categories").sterms().buckets().array()
                        .forEach(bucket -> facets.get("categories").add(
                                    FacetEntry.builder()
                                            .key(bucket.key().stringValue())
                                            .count(bucket.docCount())
                                        .build()));
            }
            
            // Xử lý price_ranges facets
            if (response.aggregations().containsKey("price_ranges")) {
                facets.put("price_ranges", new ArrayList<>());
                response.aggregations().get("price_ranges").range().buckets().array()
                        .forEach(bucket -> {
                            String key = (bucket.from() != null ? bucket.from() : "0") + 
                                    "-" + 
                                    (bucket.to() != null ? bucket.to() : "∞");
                            facets.get("price_ranges").add(
                                    FacetEntry.builder()
                                            .key(key)
                                            .count(bucket.docCount())
                                            .build());
                        });
            }
        }
        
        // Tính thời gian thực thi
        Duration searchDuration = Duration.between(startTime, Instant.now());
        String searchTime = searchDuration.toMillis() + "ms";
        
        assert response.hits().total() != null;
        return com.fourj.searchservice.dto.SearchResponse.builder()
                .totalHits(response.hits().total().value())
                .products(products)
                .page(request.getFrom() / request.getSize())
                .size(request.getSize())
                .facets(facets)
                .searchTime(searchTime)
                .build();
    }
    
    /**
     * Tạo cache key từ search request
     */
    private String generateCacheKey(com.fourj.searchservice.dto.SearchRequest request) {
        return "search:" + 
                Objects.hashCode(request.getQuery()) + ":" +
                Objects.hashCode(request.getCategories()) + ":" +
                Objects.hashCode(request.getBrand()) + ":" +
                Objects.hashCode(request.getPriceRange()) + ":" + 
                Objects.hashCode(request.getAttributes()) + ":" +
                request.getSortOption() + ":" +
                request.getFrom() + ":" +
                request.getSize();
    }
    
    /**
     * Fallback method khi Elasticsearch không khả dụng
     */
    public com.fourj.searchservice.dto.SearchResponse searchProductsFallback(com.fourj.searchservice.dto.SearchRequest request, Exception ex) {
        log.error("Search fallback triggered due to: {}", ex.getMessage());
        
        return com.fourj.searchservice.dto.SearchResponse.builder()
                .totalHits(0)
                .products(Collections.emptyList())
                .page(request.getFrom() / request.getSize())
                .size(request.getSize())
                .facets(Collections.emptyMap())
                .searchTime("0ms")
                .build();
    }
    
    /**
     * Lấy suggestions cho auto-complete (đơn giản hóa)
     */
    @Cacheable(value = "suggestions", key = "#prefix")
    public List<String> getSuggestions(String prefix, int size) {
        try {
            log.info("Getting suggestions for prefix: '{}'", prefix);
            String normalizedPrefix = prefix.toLowerCase().trim();
            
            // Sử dụng prefix query đơn giản
            SearchResponse<ProductIndexDto> response = client.search(s -> s
                .index(elasticsearchConfig.getIndexSettings().getProducts().getName())
                .query(q -> q
                    .prefix(p -> p
                        .field("name")
                        .value(normalizedPrefix)
                    )
                )
                .size(size),
                ProductIndexDto.class
            );
            
            List<String> suggestions = response.hits().hits().stream()
                .map(hit -> {
                    assert hit.source() != null;
                    return hit.source().getName();
                })
                .distinct()
                .collect(Collectors.toList());
                
            log.info("Found {} suggestions for prefix: '{}'", suggestions.size(), prefix);
            return suggestions;
        } catch (Exception e) {
            log.error("Error getting suggestions for prefix: '{}'", prefix, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Tạo mới hoặc xóa và tạo lại index
     */
    public boolean recreateIndex() throws IOException {
        String indexName = elasticsearchConfig.getIndexSettings().getProducts().getName();
        log.info("Recreating index: {}", indexName);
        
        try {
            // Kiểm tra xem index đã tồn tại chưa
            boolean exists = client.indices().exists(e -> e.index(indexName)).value();
            
            // Nếu đã tồn tại thì xóa đi
            if (exists) {
                log.info("Index {} exists, deleting", indexName);
                client.indices().delete(d -> d.index(indexName));
            }
            
            // Tạo index mới với mapping và settings đơn giản
            CreateIndexResponse response = client.indices().create(c -> c
                .index(indexName)
                .settings(s -> s
                    .numberOfShards("1")  // Đơn giản hóa, chỉ dùng 1 shard
                    .numberOfReplicas("0") // Không cần replica trong môi trường dev
                    .refreshInterval(t -> t.time("1s")) // Refresh nhanh hơn
                    .analysis(a -> a
                        .analyzer("standard_lowercase", sa -> sa
                            .custom(ca -> ca
                                .tokenizer("standard")
                                .filter("lowercase")
                            )
                        )
                    )
                )
                .mappings(m -> m
                    .properties("id", p -> p.keyword(k -> k))
                    .properties("name", p -> p.text(t -> t
                        .analyzer("standard_lowercase")
                        .fields("keyword", f -> f.keyword(k -> k))
                    ))
                    .properties("description", p -> p.text(t -> t
                        .analyzer("standard_lowercase")
                    ))
                    .properties("price", p -> p.double_(d -> d))
                    .properties("imageUrl", p -> p.keyword(k -> k))
                    .properties("categoryName", p -> p.keyword(k -> k))
                    .properties("active", p -> p.boolean_(b -> b))
                    .properties("inStock", p -> p.boolean_(b -> b))
                    .properties("rating", p -> p.float_(f -> f))
                )
            );
            
            log.info("Index {} created successfully: {}", indexName, response.acknowledged());
            return response.acknowledged();
        } catch (Exception e) {
            log.error("Error recreating index {}: {}", indexName, e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * Lấy thông tin về trạng thái của index
     */
    public Map<String, Object> getIndexStatus() throws IOException {
        String indexName = elasticsearchConfig.getIndexSettings().getProducts().getName();
        Map<String, Object> status = new HashMap<>();
        
        try {
            // Kiểm tra xem index đã tồn tại chưa
            boolean exists = client.indices().exists(e -> e.index(indexName)).value();
            status.put("exists", exists);
            
            if (exists) {
                // Lấy statistics về index
                GetIndexResponse indexResponse = client.indices().get(g -> g.index(indexName));
                IndexState indexState = indexResponse.get(indexName);
                
                // Lấy settings
                assert indexState != null;
                if (indexState.settings() != null) {
                    IndexSettings settings = indexState.settings();
                    Map<String, Object> settingsMap = new HashMap<>();
                    
                    settingsMap.put("number_of_shards", settings.numberOfShards());
                    settingsMap.put("number_of_replicas", settings.numberOfReplicas());
                    settingsMap.put("refresh_interval", settings.refreshInterval());
                    
                    status.put("settings", settingsMap);
                }
                
                // Lấy mappings
                if (indexState.mappings() != null) {
                    status.put("mappings", "Available");
                }
                
                // Lấy số lượng documents
                CountResponse countResponse = client.count(c -> c.index(indexName));
                status.put("document_count", countResponse.count());
            }
            
            return status;
        } catch (Exception e) {
            log.error("Error getting index status for {}: {}", indexName, e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * Index một sản phẩm vào Elasticsearch
     */
    public boolean indexProduct(ProductIndexDto product) throws IOException {
        String indexName = elasticsearchConfig.getIndexSettings().getProducts().getName();
        
        try {
            // Kiểm tra xem index đã tồn tại chưa, nếu chưa thì tạo mới
            boolean exists = client.indices().exists(e -> e.index(indexName)).value();
            if (!exists) {
                recreateIndex();
            }
            
            // Index sản phẩm
            log.info("Indexing product: {}", product.getId());
            IndexResponse response = client.index(i -> i
                .index(indexName)
                .id(product.getId())
                .document(product)
            );
            
            // Refresh index để đảm bảo dữ liệu có sẵn ngay lập tức cho tìm kiếm
            client.indices().refresh(r -> r.index(indexName));
            
            log.info("Successfully indexed product: {}, result: {}", product.getId(), response.result().toString());
            return true;
        } catch (Exception e) {
            log.error("Error indexing product {}: {}", product.getId(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Index nhiều sản phẩm vào Elasticsearch (bulk)
     */
    public int bulkIndexProducts(List<ProductIndexDto> products) throws IOException {
        String indexName = elasticsearchConfig.getIndexSettings().getProducts().getName();
        int successCount = 0;
        
        try {
            // Kiểm tra xem index đã tồn tại chưa, nếu chưa thì tạo mới
            boolean exists = client.indices().exists(e -> e.index(indexName)).value();
            if (!exists) {
                recreateIndex();
            }
            
            // Sử dụng bulk indexing để tối ưu hiệu suất
            BulkRequest.Builder bulkRequest = new BulkRequest.Builder();
            
            for (ProductIndexDto product : products) {
                bulkRequest.operations(op -> op
                    .index(idx -> idx
                        .index(indexName)
                        .id(product.getId())
                        .document(product)
                    )
                );
            }
            
            BulkResponse bulkResponse = client.bulk(bulkRequest.build());
            
            // Đếm số lượng thành công
            successCount = (int) bulkResponse.items().stream()
                .filter(item -> item.error() == null)
                .count();
            
            // Refresh index để đảm bảo dữ liệu có sẵn cho tìm kiếm
            client.indices().refresh(r -> r.index(indexName));
            
            log.info("Successfully bulk indexed {}/{} products", successCount, products.size());
            return successCount;
        } catch (Exception e) {
            log.error("Error bulk indexing products: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Xóa sản phẩm khỏi Elasticsearch
     */
    public boolean deleteProduct(String productId) throws IOException {
        String indexName = elasticsearchConfig.getIndexSettings().getProducts().getName();
        
        try {
            // Kiểm tra xem index đã tồn tại chưa
            boolean exists = client.indices().exists(e -> e.index(indexName)).value();
            if (!exists) {
                log.warn("Index {} does not exist, cannot delete product", indexName);
                return false;
            }
            
            // Xóa sản phẩm theo ID
            log.info("Deleting product: {}", productId);
            DeleteResponse response = client.delete(d -> d
                .index(indexName)
                .id(productId)
            );
            
            // Refresh index để đảm bảo thay đổi có hiệu lực ngay lập tức
            client.indices().refresh(r -> r.index(indexName));
            
            boolean success = response.result() == Result.Deleted;
            log.info("Delete product {}: {}", productId, success ? "successful" : "not found");
            return success;
        } catch (Exception e) {
            log.error("Error deleting product {}: {}", productId, e.getMessage(), e);
            return false;
        }
    }
} 
