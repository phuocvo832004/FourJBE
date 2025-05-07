package com.fourj.iamservice.service;

import com.auth0.client.auth.AuthAPI;
import com.auth0.client.mgmt.ManagementAPI;
import com.auth0.exception.Auth0Exception;
import com.auth0.json.auth.TokenHolder;
import com.auth0.net.TokenRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class Auth0Service {

    @Value("${auth0.domain}")
    private String domain;

    @Value("${auth0.clientId}")
    private String clientId;

    @Value("${auth0.clientSecret}")
    private String clientSecret;

    @Value("${auth0.audience}")
    private String audience;

    private ManagementAPI managementAPI;

    /**
     * Get Management API access token
     */
    private String getManagementApiToken() {
        try {
            AuthAPI authAPI = new AuthAPI(domain, clientId, clientSecret);
            TokenRequest request = authAPI.requestToken("https://" + domain + "/api/v2/");
            TokenHolder holder = (TokenHolder) request.execute();
            return holder.getAccessToken();
        } catch (Auth0Exception e) {
            log.error("Error getting Management API token", e);
            throw new RuntimeException("Could not get Management API token", e);
        }
    }

    /**
     * Get Management API instance with valid token
     */
    public ManagementAPI getManagementAPI() {
        if (managementAPI == null) {
            String token = getManagementApiToken();
            managementAPI = new ManagementAPI(domain, token);
        }
        return managementAPI;
    }

    /**
     * Assign a role to a user in Auth0
     */
    public void assignRoleToUser(String userId, String role) {
        // Implementation will connect to Auth0 Management API
        // This is a placeholder - implement based on Auth0 Management API docs
        log.info("Assigning role {} to user {}", role, userId);
    }
}