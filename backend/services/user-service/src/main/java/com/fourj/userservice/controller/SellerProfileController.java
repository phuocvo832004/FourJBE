package com.fourj.userservice.controller;

import com.fourj.userservice.dto.SellerRegistrationDto;
import com.fourj.userservice.dto.UserProfileDto;
import com.fourj.userservice.dto.UserProfileUpdateDto;
import com.fourj.userservice.model.UserProfile.VerificationStatus;
import com.fourj.userservice.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/seller")
@Slf4j
@PreAuthorize("hasAuthority('seller:access')")
public class SellerProfileController {

    private final UserProfileService userProfileService;

    @Autowired
    public SellerProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping("/profile")
    public ResponseEntity<UserProfileDto> getSellerProfile(@AuthenticationPrincipal Jwt jwt) {
        String auth0Id = jwt.getSubject();
        UserProfileDto profile = userProfileService.getUserProfileByAuth0Id(auth0Id);
        
        // Kiểm tra người dùng đã đăng ký làm seller chưa
        if (!profile.isSeller()) {
            throw new IllegalStateException("User is not a seller");
        }
        
        return ResponseEntity.ok(profile);
    }

    @PutMapping("/profile")
    public ResponseEntity<UserProfileDto> updateSellerProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UserProfileUpdateDto updateDto) {
        String auth0Id = jwt.getSubject();
        
        // Kiểm tra người dùng đã đăng ký làm seller chưa
        UserProfileDto currentProfile = userProfileService.getUserProfileByAuth0Id(auth0Id);
        if (!currentProfile.isSeller()) {
            throw new IllegalStateException("User is not a seller");
        }
        
        return ResponseEntity.ok(userProfileService.updateUserProfile(auth0Id, updateDto));
    }

    @PostMapping("/register")
    @PreAuthorize("isAuthenticated()")  // Bất kỳ người dùng xác thực nào cũng có thể đăng ký làm seller
    public ResponseEntity<UserProfileDto> registerAsSeller(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SellerRegistrationDto registrationDto) {
        String auth0Id = jwt.getSubject();
        log.info("User {} is registering as seller with store name: {}", auth0Id, registrationDto.getStoreName());
        return ResponseEntity.ok(userProfileService.registerAsSeller(auth0Id, registrationDto));
    }

    @GetMapping("/verification-status")
    public ResponseEntity<VerificationStatus> getSellerVerificationStatus(@AuthenticationPrincipal Jwt jwt) {
        String auth0Id = jwt.getSubject();
        UserProfileDto profile = userProfileService.getUserProfileByAuth0Id(auth0Id);
        
        // Kiểm tra người dùng đã đăng ký làm seller chưa
        if (!profile.isSeller()) {
            throw new IllegalStateException("User is not a seller");
        }
        
        return ResponseEntity.ok(profile.getVerificationStatus());
    }
} 