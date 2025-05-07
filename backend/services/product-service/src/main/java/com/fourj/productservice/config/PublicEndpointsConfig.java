package com.fourj.productservice.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Cấu hình để xử lý các public endpoint trước khi qua OAuth2 validation
 */
@Configuration
public class PublicEndpointsConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public PublicEndpointsFilter publicEndpointsFilter() {
        return new PublicEndpointsFilter();
    }

    /**
     * Filter xử lý các public endpoint trước khi đến các bộ lọc bảo mật khác
     * Nếu là public endpoint, đặt AnonymousAuthenticationToken để bỏ qua các bộ lọc xác thực tiếp theo
     */
    public static class PublicEndpointsFilter extends OncePerRequestFilter {
        private final RequestMatcher publicEndpoints;
        private static final AnonymousAuthenticationToken ANONYMOUS_USER = new AnonymousAuthenticationToken(
                "anonymous", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        public PublicEndpointsFilter() {
            List<RequestMatcher> matchers = Arrays.asList(
                    new AntPathRequestMatcher("/api/products", HttpMethod.GET.name()),
                    new AntPathRequestMatcher("/api/products/**", HttpMethod.GET.name()),
                    new AntPathRequestMatcher("/api/categories", HttpMethod.GET.name()),
                    new AntPathRequestMatcher("/api/categories/**", HttpMethod.GET.name()),
                    new AntPathRequestMatcher("/actuator/**")
            );
            this.publicEndpoints = new OrRequestMatcher(matchers);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            
            // Nếu yêu cầu khớp với public endpoint, đặt xác thực ẩn danh và bỏ qua xác thực OAuth2
            if (publicEndpoints.matches(request)) {
                // Đặt xác thực ẩn danh để bỏ qua bộ lọc OAuth2
                SecurityContextHolder.getContext().setAuthentication(ANONYMOUS_USER);
            }
            
            // Tiếp tục chuỗi bộ lọc
            filterChain.doFilter(request, response);
        }
    }
} 