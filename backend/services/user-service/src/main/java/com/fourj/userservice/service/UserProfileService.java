package com.fourj.userservice.service;

import com.fourj.userservice.dto.SellerRegistrationDto;
import com.fourj.userservice.dto.SellerVerificationDto;
import com.fourj.userservice.dto.UserProfileDto;
import com.fourj.userservice.dto.UserProfileUpdateDto;
import com.fourj.userservice.model.UserProfile;
import com.fourj.userservice.model.UserProfile.VerificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface UserProfileService {
    UserProfileDto createUserProfile(UserProfile userProfile);
    UserProfileDto getUserProfileByAuth0Id(String auth0Id);
    UserProfileDto updateUserProfile(String auth0Id, UserProfileUpdateDto userProfileUpdateDto);
    List<UserProfileDto> getAllUserProfiles();
    void deleteUserProfile(String auth0Id);
    boolean userProfileExists(String auth0Id);
    
    // Các phương thức mới cho seller
    UserProfileDto registerAsSeller(String auth0Id, SellerRegistrationDto registrationDto);
    Page<UserProfileDto> getSellers(Pageable pageable);
    Page<UserProfileDto> getSellersByVerificationStatus(VerificationStatus status, Pageable pageable);
    
    // Các phương thức mới cho admin
    UserProfileDto verifySellerById(Long id, SellerVerificationDto verificationDto, String adminId);
    UserProfileDto verifySellerByAuth0Id(String auth0Id, SellerVerificationDto verificationDto, String adminId);
    Page<UserProfileDto> searchUserProfiles(String keyword, Pageable pageable);
}