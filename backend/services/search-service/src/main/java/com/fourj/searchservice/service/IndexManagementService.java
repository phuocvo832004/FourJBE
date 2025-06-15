package com.fourj.searchservice.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.analysis.TokenFilter;
import co.elastic.clients.elasticsearch._types.analysis.TokenFilterDefinition;
import co.elastic.clients.elasticsearch.indices.*;
import co.elastic.clients.util.ObjectBuilder;
import com.fourj.searchservice.config.ElasticsearchConfig;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.elasticsearch.core.TimeValue;



@Service
@RequiredArgsConstructor
@Slf4j
public class IndexManagementService {

    private final ElasticsearchClient client;
    private final ElasticsearchConfig elasticsearchConfig;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndices() {
        try {
            createProductIndexIfNotExists();
        } catch (IOException e) {
            log.error("Failed to initialize Elasticsearch indices", e);
        }
    }

    @CircuitBreaker(name = "elasticsearch")
    private void createProductIndexIfNotExists() throws IOException {
        var indexSettings = elasticsearchConfig.getIndexSettings().getProducts();
        String indexName = indexSettings.getName();

        boolean exists = client.indices().exists(ex -> ex.index(indexName)).value();

        if (!exists) {
            log.info("Creating Elasticsearch index: {}", indexName);

            Map<String, Alias> aliases = new HashMap<>();
            for (String alias : indexSettings.getAliases()) {
                aliases.put(alias, Alias.of(a -> a.isWriteIndex(true)));
            }
            
            // Tạo bộ lọc stop token cho tiếng Việt
            CreateIndexResponse response = client.indices().create(c -> c
                    .index(indexName)
                    .settings(s -> s
                            .numberOfShards(String.valueOf(indexSettings.getShards()))
                            .numberOfReplicas(String.valueOf(indexSettings.getReplicas()))
                            .refreshInterval(builder -> builder.time(
                                    TimeValue.parseTimeValue(indexSettings.getRefreshInterval(), "refreshInterval").toString()
                            ))
                            .analysis(a -> a
                                    .filter("vietnamese_stop", filterBuilder -> 
                                        filterBuilder.definition(TokenFilterDefinition.of(def -> 
                                            def.stop(stop -> 
                                                stop.stopwords("_vietnamese_")
                                            )
                                        ))
                                    )
                                    .analyzer("vietnamese_analyzer", an -> an
                                            .custom(ca -> ca
                                                    .tokenizer("standard")
                                                    .filter("lowercase", "asciifolding", "vietnamese_stop")
                                            )
                                    )
                            )
                    )


                    .mappings(m -> m
                            .properties("id", p -> p.keyword(k -> k))
                            .properties("name", p -> p.text(txt -> txt.analyzer("vietnamese_analyzer")))
                            .properties("description", p -> p.text(txt -> txt.analyzer("vietnamese_analyzer")))
                            .properties("price", p -> p.double_(d -> d))
                            .properties("stockQuantity", p -> p.integer(i -> i))
                            .properties("imageUrl", p -> p.keyword(k -> k))
                            .properties("categoryId", p -> p.long_(l -> l))
                            .properties("categoryName", p -> p.keyword(k -> k))
                            .properties("active", p -> p.boolean_(b -> b))
                            .properties("createdAt", p -> p.date(d -> d))
                            .properties("updatedAt", p -> p.date(d -> d))
                            .properties("attributes", p -> p.nested(n -> n
                                    .properties("name", np -> np.keyword(k -> k))
                                    .properties("value", np -> np.keyword(k -> k))
                                    .properties("displayName", np -> np.text(t -> t.analyzer("vietnamese_analyzer")))
                                    .properties("displayValue", np -> np.text(t -> t.analyzer("vietnamese_analyzer")))
                            ))
                            .properties("originalPrice", p -> p.double_(d -> d))
                            .properties("discountPercent", p -> p.double_(d -> d))
                            .properties("inStock", p -> p.boolean_(b -> b))
                            .properties("images", p -> p.object(o -> o))
                            .properties("rating", p -> p.float_(f -> f))
                            .properties("reviewCount", p -> p.integer(i -> i))
                            .properties("soldCount", p -> p.integer(i -> i))
                            .properties("tags", p -> p.keyword(k -> k))
                            .properties("nameSuggest", p -> p.completion(comp -> comp.analyzer("vietnamese_analyzer")))
                    )
                    .aliases(aliases)
            );

            log.info("Index created: {}, acknowledged: {}", indexName, response.acknowledged());
        } else {
            log.info("Elasticsearch index already exists: {}", indexName);
        }
    }

    public void deleteIndex(String indexName) throws IOException {
        DeleteIndexResponse response = client.indices().delete(d -> d.index(indexName));
        log.info("Index deleted: {}, acknowledged: {}", indexName, response.acknowledged());
    }

    public void recreateIndex() throws IOException {
        String indexName = elasticsearchConfig.getIndexSettings().getProducts().getName();
        boolean exists = client.indices().exists(e -> e.index(indexName)).value();

        if (exists) {
            deleteIndex(indexName);
        }

        createProductIndexIfNotExists();
    }
}
