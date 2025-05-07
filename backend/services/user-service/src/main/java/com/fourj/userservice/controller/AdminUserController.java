package com.fourj.userservice.controller;

import com.fourj.userservice.dto.SellerVerificationDto;
import com.fourj.userservice.dto.UserProfileDto;
import com.fourj.userservice.model.UserProfile.VerificationStatus;
import com.fourj.userservice.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users/admin")
@Slf4j
@PreAuthorize("hasAuthority('admin:access')")
public class AdminUserController {

    private final UserProfileService userProfileService;

    @Autowired
    public AdminUserController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping
    public ResponseEntity<Page<UserProfileDto>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {
        
        Sort sort = sortDir.equalsIgnoreCase("asc") ?
                Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);
        
        return ResponseEntity.ok(userProfileService.searchUserProfiles("", pageable));
    }
    
    @GetMapping("/search")
    public ResponseEntity<Page<UserProfileDto>> searchUsers(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {
        
        Sort sort = sortDir.equalsIgnoreCase("asc") ?
                Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);
        
        return ResponseEntity.ok(userProfileService.searchUserProfiles(keyword, pageable));
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<UserProfileDto> getUserById(@PathVariable Long id) {
        // Giả định lấy theo ID số
        List<UserProfileDto> allUsers = userProfileService.getAllUserProfiles();
        UserProfileDto foundUser = allUsers.stream()
                .filter(user -> user.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new com.fourj.userservice.exception.ResourceNotFoundException("User not found with id: " + id));
        
        return ResponseEntity.ok(foundUser);
    }
    
    @GetMapping("/auth0/{auth0Id}")
    public ResponseEntity<UserProfileDto> getUserByAuth0Id(@PathVariable String auth0Id) {
        return ResponseEntity.ok(userProfileService.getUserProfileByAuth0Id(auth0Id));
    }

    @GetMapping("/sellers")
    public ResponseEntity<Page<UserProfileDto>> getAllSellers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {
        
        Sort sort = sortDir.equalsIgnoreCase("asc") ?
                Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);
        
        return ResponseEntity.ok(userProfileService.getSellers(pageable));
    }
    
    @GetMapping("/sellers/pending-verification")
    public ResponseEntity<Page<UserProfileDto>> getPendingVerificationSellers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {
        
        Sort sort = sortDir.equalsIgnoreCase("asc") ?
                Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);
        
        return ResponseEntity.ok(userProfileService.getSellersByVerificationStatus(VerificationStatus.PENDING, pageable));
    }
    
    @PutMapping("/sellers/{id}/verify")
    public ResponseEntity<UserProfileDto> verifySellerById(
            @PathVariable Long id,
            @Valid @RequestBody SellerVerificationDto verificationDto,
            @AuthenticationPrincipal Jwt jwt) {
        
        String adminId = jwt.getSubject();
        log.info("Admin {} is verifying seller with id: {}, status: {}", 
                adminId, id, verificationDto.getVerificationStatus());
        
        return ResponseEntity.ok(userProfileService.verifySellerById(id, verificationDto, adminId));
    }
    
    @PutMapping("/sellers/auth0/{auth0Id}/verify")
    public ResponseEntity<UserProfileDto> verifySellerByAuth0Id(
            @PathVariable String auth0Id,
            @Valid @RequestBody SellerVerificationDto verificationDto,
            @AuthenticationPrincipal Jwt jwt) {
        
        String adminId = jwt.getSubject();
        log.info("Admin {} is verifying seller with auth0Id: {}, status: {}", 
                adminId, auth0Id, verificationDto.getVerificationStatus());
        
        return ResponseEntity.ok(userProfileService.verifySellerByAuth0Id(auth0Id, verificationDto, adminId));
    }
    
    @DeleteMapping("/{auth0Id}")
    public ResponseEntity<Void> deleteUser(@PathVariable String auth0Id) {
        userProfileService.deleteUserProfile(auth0Id);
        return ResponseEntity.noContent().build();
    }
} 