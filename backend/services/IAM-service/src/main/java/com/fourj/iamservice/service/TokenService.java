package com.fourj.iamservice.service;

import com.auth0.client.auth.AuthAPI;
import com.auth0.exception.Auth0Exception;
import com.auth0.json.auth.TokenHolder;
import com.auth0.net.TokenRequest;
import com.fourj.iamservice.dto.TokenRequestDto;
import com.fourj.iamservice.dto.TokenResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TokenService {

    @Value("${auth0.domain}")
    private String domain;

    @Value("${auth0.clientId}")
    private String clientId;

    @Value("${auth0.clientSecret}")
    private String clientSecret;

    @Value("${auth0.audience}")
    private String audience;

    private AuthAPI authAPI;

    public TokenResponseDto getToken(TokenRequestDto request) {
        try {
            if ("authorization_code".equals(request.getGrantType())) {
                // Authorization Code + PKCE flow
                TokenRequest tokenRequest = authAPI.exchangeCode(
                        request.getCode(),
                        request.getRedirectUri()
                );
                
                TokenHolder holder = (TokenHolder) tokenRequest.execute();
                return buildTokenResponse(holder);
                
            } else if ("refresh_token".equals(request.getGrantType())) {
                // Refresh token flow
                return refreshToken(request);
            } else {
                throw new IllegalArgumentException("Unsupported grant_type: " + request.getGrantType());
            }
        } catch (Auth0Exception e) {
            log.error("Error while getting token", e);
            throw new RuntimeException("Error while getting token", e);
        }
    }
    
    public TokenResponseDto refreshToken(TokenRequestDto request) {
        try {
            TokenRequest tokenRequest = authAPI.renewAuth(request.getRefreshToken());
            TokenHolder holder = (TokenHolder) tokenRequest.execute();
            return buildTokenResponse(holder);
        } catch (Auth0Exception e) {
            log.error("Error while refreshing token", e);
            throw new RuntimeException("Error while refreshing token", e);
        }
    }
    
    private TokenResponseDto buildTokenResponse(TokenHolder holder) {
        return TokenResponseDto.builder()
                .accessToken(holder.getAccessToken())
                .refreshToken(holder.getRefreshToken())
                .idToken(holder.getIdToken())
                .tokenType(holder.getTokenType())
                .expiresIn((int) holder.getExpiresIn())
                .build();
    }
} 