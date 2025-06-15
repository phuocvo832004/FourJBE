package com.fourj.orderservice.service.client;

import com.fourj.orderservice.dto.ProductDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class ProductClient {

    private final WebClient webClient;

    @Autowired
    public ProductClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl("http://product-service:8084").build();
    }

    public Mono<ProductDto> getProductById(String productId) {
        return webClient.get()
                .uri("/api/products/{id}", productId)
                .retrieve()
                .bodyToMono(ProductDto.class);
    }

    public Mono<List<ProductDto>> getProductsById(List<String> productIds) {
        String ids = String.join(",", productIds);
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/products/batch")
                        .queryParam("ids", ids)
                        .build())
                .retrieve()
                .bodyToFlux(ProductDto.class)
                .collectList();
    }
    
    public Mono<Boolean> updateStockQuantity(String productId, int quantity) {
        return webClient.put()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/products/{id}/stock")
                        .queryParam("quantity", quantity)
                        .build(productId))
                .retrieve()
                .toBodilessEntity()
                .map(response -> response.getStatusCode().is2xxSuccessful())
                .onErrorReturn(false);
    }
} 