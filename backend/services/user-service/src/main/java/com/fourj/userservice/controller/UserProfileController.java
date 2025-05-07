package com.fourj.userservice.controller;

import com.fourj.userservice.config.UserHeadersAuthenticationFilter.KongUser;
import com.fourj.userservice.dto.UserProfileDto;
import com.fourj.userservice.dto.UserProfileUpdateDto;
import com.fourj.userservice.model.UserProfile;
import com.fourj.userservice.service.UserProfileService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@RestController
@RequestMapping("/api/users/profile")
@Slf4j
public class UserProfileController {

    private final UserProfileService userProfileService;

    @Autowired
    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileDto> getCurrentUserProfile(@AuthenticationPrincipal KongUser kongUser) {
        String auth0Id = kongUser.getId();

        if (!userProfileService.userProfileExists(auth0Id)) {
            // Tạo profile người dùng mới nếu chưa tồn tại
            UserProfile newUserProfile = new UserProfile();
            newUserProfile.setAuth0Id(auth0Id);

            // Lấy email từ thông tin user
            Object email = kongUser.getAttribute("email");
            if (email != null) {
                newUserProfile.setEmail(email.toString());
            }

            // Lấy fullName từ thông tin user
            Object name = kongUser.getAttribute("name");
            if (name != null) {
                newUserProfile.setFullName(name.toString());
            }

            // Lấy avatar từ thông tin user
            Object picture = kongUser.getAttribute("picture");
            if (picture != null) {
                newUserProfile.setAvatarUrl(picture.toString());
            }

            return new ResponseEntity<>(userProfileService.createUserProfile(newUserProfile), HttpStatus.CREATED);
        }

        return ResponseEntity.ok(userProfileService.getUserProfileByAuth0Id(auth0Id));
    }

    @PutMapping("/me")
    public ResponseEntity<UserProfileDto> updateCurrentUserProfile(
            @AuthenticationPrincipal KongUser kongUser,
            @Valid @RequestBody UserProfileUpdateDto updateDto) {
        String auth0Id = kongUser.getId();
        return ResponseEntity.ok(userProfileService.updateUserProfile(auth0Id, updateDto));
    }

    @GetMapping("/{auth0Id}")
    public ResponseEntity<UserProfileDto> getUserProfileByAuth0Id(@PathVariable String auth0Id) {
        return ResponseEntity.ok(userProfileService.getUserProfileByAuth0Id(auth0Id));
    }

    @GetMapping
    public ResponseEntity<List<UserProfileDto>> getAllUserProfiles() {
        return ResponseEntity.ok(userProfileService.getAllUserProfiles());
    }

    @DeleteMapping("/{auth0Id}")
    public ResponseEntity<Void> deleteUserProfile(@PathVariable String auth0Id) {
        userProfileService.deleteUserProfile(auth0Id);
        return ResponseEntity.noContent().build();
    }
}