package com.fourj.orderservice.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.stream.Collectors;

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
        private static final Logger filterLogger = LoggerFactory.getLogger(PublicEndpointsFilter.class);

        private final RequestMatcher publicEndpoints;
        private final List<RequestMatcher> individualMatchers;

        private static final AnonymousAuthenticationToken ANONYMOUS_USER = new AnonymousAuthenticationToken(
                "anonymous", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        public PublicEndpointsFilter() {
            this.individualMatchers = Arrays.asList(
                    new AntPathRequestMatcher(HttpMethod.POST.name(), "/api/payments/webhook"),
                    new AntPathRequestMatcher("/checkout/orders/cancel"),
                    new AntPathRequestMatcher("/checkout/orders/success"),
                    new AntPathRequestMatcher("/actuator/**"),
                    new AntPathRequestMatcher("/api/orders/export/test-batch", HttpMethod.POST.name()),
                    new AntPathRequestMatcher("/api/orders/export/test-single/**", HttpMethod.POST.name()),
                    new AntPathRequestMatcher("/api/orders/export/test-azure-connection", HttpMethod.POST.name()),
                    new AntPathRequestMatcher("/api/orders/export/trigger-weekly-export", HttpMethod.POST.name()),
                    new AntPathRequestMatcher("/api/orders/export/sync-all-pending", HttpMethod.POST.name())
            );
            this.publicEndpoints = new OrRequestMatcher(this.individualMatchers);
            filterLogger.info("PublicEndpointsFilter initialized with matchers: {}",
                    this.individualMatchers.stream().map(Object::toString).collect(Collectors.toList()));
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            
            filterLogger.debug("PublicEndpointsFilter: --- START OF REQUEST PROCESSING ---");
            filterLogger.debug("PublicEndpointsFilter: Processing request: {} {}", request.getMethod(), request.getRequestURI());

            for (RequestMatcher matcher : individualMatchers) {
                boolean currentMatchResult = matcher.matches(request);
                filterLogger.debug("PublicEndpointsFilter: Checking request {} {} against matcher: {}. Result: {}",
                        request.getMethod(), request.getRequestURI(), matcher.toString(), currentMatchResult);
            }
            
            boolean overallMatchResult = publicEndpoints.matches(request);
            filterLogger.info("PublicEndpointsFilter: OrRequestMatcher final result for {} {}: {}",
                    request.getMethod(), request.getRequestURI(), overallMatchResult);

            if (overallMatchResult) {
                filterLogger.info("PublicEndpointsFilter: MATCHED public endpoint: {} {}. Setting AnonymousAuthenticationToken.", request.getMethod(), request.getRequestURI());
                SecurityContextHolder.getContext().setAuthentication(ANONYMOUS_USER);
            } else {
                filterLogger.info("PublicEndpointsFilter: DID NOT MATCH public endpoint: {} {}", request.getMethod(), request.getRequestURI());
            }
            filterLogger.debug("PublicEndpointsFilter: --- END OF REQUEST PROCESSING ---");
            filterChain.doFilter(request, response);
        }
    }
} 