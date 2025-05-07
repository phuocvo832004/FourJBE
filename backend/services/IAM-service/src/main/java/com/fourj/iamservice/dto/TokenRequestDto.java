package com.fourj.iamservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenRequestDto {
    private String grantType;
    private String code;
    private String redirectUri;
    private String codeVerifier;
    private String refreshToken;
    private String clientId;
} 