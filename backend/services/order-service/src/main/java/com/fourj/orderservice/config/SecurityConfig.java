package com.fourj.orderservice.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);
    
    @Value("${app.cors.allowed-origins:*}")
    private String allowedOrigins;
    
    @Autowired
    private PublicEndpointsConfig.PublicEndpointsFilter publicEndpointsFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        logger.info("Configuring security with Kong OIDC header-based authentication");
        
        // Đặt cấu hình không tạo session
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        
        // Vô hiệu hóa CSRF và cấu hình CORS
        http.csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()));
            
        // Thêm bộ lọc public endpoints trước bộ lọc OAuth2
        http.addFilterBefore(publicEndpointsFilter, BasicAuthenticationFilter.class);
        
        // Thêm filter xác thực từ header và đặt trước bất kỳ bộ lọc xác thực nào khác
        http.addFilterBefore(userHeadersAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);
        
        // Cấu hình các quy tắc yêu cầu HTTP
        http.authorizeHttpRequests(auth -> auth
            // Public endpoints
            .requestMatchers(HttpMethod.POST, "/api/payments/webhook").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/orders/export/test-batch").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/orders/export/test-single/**").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/orders/export/test-azure-connection").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/orders/export/trigger-weekly-export").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/orders/export/sync-all-pending").permitAll()
            .requestMatchers("/actuator/**", 
                           "/checkout/orders/cancel", 
                           "/checkout/orders/success",
                           "/api/orders/export/test-batch").permitAll()
            // Cho phép OPTIONS request không cần xác thực
            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
            
            // Admin chỉ có thể truy cập API quản trị
            .requestMatchers("/api/admin/**").hasAuthority("admin:access")
            
            // Seller chỉ có thể truy cập API của seller
            .requestMatchers("/api/seller/**").hasAuthority("seller:access")
            
            // Tất cả các endpoint còn lại yêu cầu xác thực
            .anyRequest().authenticated()
        );
        
        // Loại bỏ cấu hình oauth2ResourceServer
        
        return http.build();
    }
    
    @Bean
    public UserHeadersAuthenticationFilter userHeadersAuthenticationFilter() {
        return new UserHeadersAuthenticationFilter();
    }
    
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // Cấu hình các origins cụ thể thay vì dùng wildcard
        configuration.setAllowedOrigins(Arrays.asList(
            "http://localhost:5173",
            "http://localhost:80", 
            "http://localhost",
            "https://dev-vihsigx84vhnlzvg.us.auth0.com"
        ));
        
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Accept", "Origin", "X-Requested-With"));
        configuration.setExposedHeaders(Arrays.asList("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
    
    // Loại bỏ Bean JwtDecoder không cần thiết nữa
}