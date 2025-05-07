package com.fourj.searchservice.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContexts;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import javax.net.ssl.SSLContext;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

@Configuration
@ConfigurationProperties(prefix = "elasticsearch")
@Data
@Slf4j
public class ElasticsearchConfig {

    private String host;
    private int port;
    private String username;
    private String password;
    private boolean sslEnabled;
    private int connectTimeout;
    private int socketTimeout;
    private IndexSettings indexSettings;

    @Data
    public static class IndexSettings {
        private ProductIndexSettings products;
    }

    @Data
    public static class ProductIndexSettings {
        private String name;
        private String[] aliases;
        private int shards;
        private int replicas;
        private String refreshInterval;
    }

    @Bean
    public JsonpMapper jsonpMapper() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        
        // Cấu hình để bỏ qua các trường không xác định
        om.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        
        return new JacksonJsonpMapper(om);
    }

    @Bean
    public RestClient restClient() {
        try {
            RestClientBuilder builder = RestClient.builder(
                    new HttpHost(host, port, sslEnabled ? "https" : "http"));

            // Configure connection timeouts
            builder.setRequestConfigCallback(requestConfigBuilder -> requestConfigBuilder
                    .setConnectTimeout(connectTimeout)
                    .setSocketTimeout(socketTimeout));

            // Configure authentication if credentials are provided
            if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
                final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(AuthScope.ANY,
                        new UsernamePasswordCredentials(username, password));

                builder.setHttpClientConfigCallback(httpClientBuilder -> {
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);

                    // Configure SSL if enabled
                    if (sslEnabled) {
                        try {
                            SSLContext sslContext = SSLContexts.custom()
                                    .loadTrustMaterial(null, (x509Certificates, s) -> true)
                                    .build();
                            httpClientBuilder.setSSLContext(sslContext);
                        } catch (Exception e) {
                            log.error("Error configuring SSL Context", e);
                        }
                    }

                    return httpClientBuilder;
                });
            }

            return builder.build();
        } catch (Exception e) {
            log.error("Error creating RestClient: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create Elasticsearch RestClient", e);
        }
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient, JsonpMapper jsonpMapper) {
        try {
            // Tạo transport sử dụng RestClient và mapper đã cấu hình
            ElasticsearchTransport transport = new RestClientTransport(restClient, jsonpMapper);
            
            // Tạo client sử dụng transport
            return new ElasticsearchClient(transport);
        } catch (Exception e) {
            log.error("Error creating ElasticsearchClient: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create ElasticsearchClient", e);
        }
    }
}