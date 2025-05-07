package com.fourj.productservice.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
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

    @Value("${auth0.audience}")
    private String audience;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuer;
    
    @Value("${app.cors.allowed-origins:*}")
    private String allowedOrigins;
    
    @Autowired
    private PublicEndpointsConfig.PublicEndpointsFilter publicEndpointsFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // Đặt cấu hình không tạo session
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        
        // Vô hiệu hóa CSRF và cấu hình CORS
        http.csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()));
            
        // Thêm bộ lọc public endpoints trước bộ lọc OAuth2
        http.addFilterBefore(publicEndpointsFilter, BasicAuthenticationFilter.class);
        
        // Cấu hình các quy tắc yêu cầu HTTP
        http.authorizeHttpRequests(auth -> auth
            // Các endpoint public
            .requestMatchers(HttpMethod.GET, "/api/products").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll() 
            .requestMatchers(HttpMethod.GET, "/api/categories").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/categories/**").permitAll()
            .requestMatchers("/actuator/**").permitAll()
            // Cho phép OPTIONS request không cần xác thực
            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
            
            // Các endpoint cho seller
            .requestMatchers("/api/seller/products/**").hasAuthority("seller:access")
            
            // Các endpoint cho admin
            .requestMatchers("/api/admin/**").hasAuthority("admin:access")
            
            // Các endpoint cũ cần quyền write:products
            .requestMatchers(HttpMethod.POST, "/api/products/**", "/api/categories/**").hasAnyAuthority("write:products", "admin:access")
            .requestMatchers(HttpMethod.PUT, "/api/products/**", "/api/categories/**").hasAnyAuthority("write:products", "admin:access")
            .requestMatchers(HttpMethod.DELETE, "/api/products/**", "/api/categories/**").hasAnyAuthority("write:products", "admin:access")
            
            // Tất cả các endpoint khác yêu cầu xác thực
            .anyRequest().authenticated()
        );
        
        // Cấu hình OAuth2 resource server
        http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));

        return http.build();
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
}