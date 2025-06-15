package com.fourj.orderservice.config;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class UserHeadersAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(UserHeadersAuthenticationFilter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        logger.debug("Processing request: {}", request.getRequestURI());
        
        String userId = request.getHeader("X-User-ID");
        String userInfoHeader = request.getHeader("X-Userinfo");
        String permissionsHeader = request.getHeader("X-User-Permissions");
        String originalToken = request.getHeader("Authorization");
        
        if (originalToken != null && originalToken.startsWith("Bearer ")) {
            originalToken = originalToken.substring(7);
        }
        
        if (userId != null && !userId.isEmpty()) {
            logger.debug("User ID found in headers: {}", userId);
            
            Map<String, Object> userAttributes = new HashMap<>();
            userAttributes.put("sub", userId);
            
            if (userInfoHeader != null && !userInfoHeader.isEmpty()) {
                try {
                    Map<String, Object> userInfo = objectMapper.readValue(userInfoHeader, Map.class);
                    userAttributes.putAll(userInfo);
                    logger.debug("Successfully parsed user info from headers");
                } catch (Exception e) {
                    logger.warn("Could not parse X-Userinfo header: {}", e.getMessage());
                }
            }
            
            // Tạo danh sách quyền từ header permissions
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            if (permissionsHeader != null && !permissionsHeader.isEmpty()) {
                try {
                    String[] permissions = permissionsHeader.split(",");
                    authorities = Stream.of(permissions)
                            .map(SimpleGrantedAuthority::new)
                            .collect(Collectors.toList());
                    logger.debug("Permissions extracted: {}", authorities);
                } catch (Exception e) {
                    logger.warn("Could not parse X-User-Permissions header: {}", e.getMessage());
                }
            }
            
            // Thêm phân quyền dựa trên giá trị từ thông tin userInfo
            if (userAttributes.containsKey("roles")) {
                Object roles = userAttributes.get("roles");
                if (roles instanceof List) {
                    List<SimpleGrantedAuthority> finalAuthorities = authorities;
                    ((List<?>) roles).forEach(role -> {
                        if (role != null) {
                            String roleStr = role.toString();
                            if (roleStr.equals("admin")) {
                                finalAuthorities.add(new SimpleGrantedAuthority("admin:access"));
                            } else if (roleStr.equals("seller")) {
                                finalAuthorities.add(new SimpleGrantedAuthority("seller:access"));
                            }
                        }
                    });
                }
            }
            
            // Tạo đối tượng Jwt thay vì KongUser để tương thích với @AuthenticationPrincipal Jwt
            Jwt jwt = createJwtFromUserInfo(userId, userAttributes, originalToken);
            
            // Tạo đối tượng xác thực với Jwt
            UsernamePasswordAuthenticationToken authentication = 
                    new UsernamePasswordAuthenticationToken(jwt, null, authorities);
            
            // Đặt vào SecurityContext để Spring Security sử dụng
            SecurityContextHolder.getContext().setAuthentication(authentication);
            logger.debug("Authentication set in SecurityContext for user: {}", userId);
        } else {
            logger.debug("No user ID found in headers, proceeding without authentication for non-public endpoints or if headers are missing.");
            // Không làm gì cả, để các filter khác xử lý (ví dụ: AnonymousAuthenticationFilter hoặc filter bắt buộc xác thực)
        }
        
        filterChain.doFilter(request, response);
    }
    
    /**
     * Tạo đối tượng Jwt giả từ thông tin người dùng
     */
    private Jwt createJwtFromUserInfo(String userId, Map<String, Object> userAttributes, String originalToken) {
        Map<String, Object> headers = new HashMap<>();
        headers.put("alg", "HS256");
        headers.put("typ", "JWT");
        
        // Đảm bảo các thuộc tính cần thiết có trong claims
        Map<String, Object> claims = new HashMap<>(userAttributes);
        claims.put("sub", userId);
        
        // Tạo các thời điểm cần thiết cho JWT
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(3600); // Hết hạn sau 1 giờ
        
        // Sử dụng token gốc nếu có, nếu không tạo token giả
        String tokenValue = originalToken;
        if (tokenValue == null || tokenValue.isEmpty()) {
            tokenValue = "header." + userId + ".signature";
        }
        
        // Lưu token value vào claims để các service khác có thể sử dụng
        claims.put("token_value", tokenValue);
        
        return new Jwt(tokenValue, issuedAt, expiresAt, headers, claims);
    }
} 