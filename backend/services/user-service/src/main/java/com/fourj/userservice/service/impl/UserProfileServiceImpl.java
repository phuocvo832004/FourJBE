package com.fourj.userservice.service.impl;

import com.fourj.userservice.dto.SellerRegistrationDto;
import com.fourj.userservice.dto.SellerVerificationDto;
import com.fourj.userservice.dto.UserProfileDto;
import com.fourj.userservice.dto.UserProfileUpdateDto;
import com.fourj.userservice.exception.ResourceNotFoundException;
import com.fourj.userservice.exception.UnauthorizedAccessException;
import com.fourj.userservice.model.UserProfile;
import com.fourj.userservice.model.UserProfile.VerificationStatus;
import com.fourj.userservice.repository.UserProfileRepository;
import com.fourj.userservice.service.UserProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class UserProfileServiceImpl implements UserProfileService {

    private final UserProfileRepository userProfileRepository;

    @Autowired
    public UserProfileServiceImpl(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    @Override
    @Transactional
    public UserProfileDto createUserProfile(UserProfile userProfile) {
        UserProfile savedUserProfile = userProfileRepository.save(userProfile);
        return mapToDto(savedUserProfile);
    }

    @Override
    public UserProfileDto getUserProfileByAuth0Id(String auth0Id) {
        UserProfile userProfile = userProfileRepository.findByAuth0Id(auth0Id)
                .orElseThrow(() -> new ResourceNotFoundException("User profile not found"));
        return mapToDto(userProfile);
    }

    @Override
    @Transactional
    public UserProfileDto updateUserProfile(String auth0Id, UserProfileUpdateDto updateDto) {
        UserProfile userProfile = userProfileRepository.findByAuth0Id(auth0Id)
                .orElseThrow(() -> new ResourceNotFoundException("User profile not found"));

        // Cập nhật thông tin người dùng nếu có
        if (updateDto.getFullName() != null) {
            userProfile.setFullName(updateDto.getFullName());
        }
        if (updateDto.getPhone() != null) {
            userProfile.setPhone(updateDto.getPhone());
        }
        if (updateDto.getFullAddress() != null) {
            userProfile.setFullAddress(updateDto.getFullAddress());
        }
        if (updateDto.getDateOfBirth() != null) {
            userProfile.setDateOfBirth(updateDto.getDateOfBirth());
        }
        if (updateDto.getGender() != null) {
            userProfile.setGender(updateDto.getGender());
        }
        if (updateDto.getAvatarUrl() != null) {
            userProfile.setAvatarUrl(updateDto.getAvatarUrl());
        }
        if (updateDto.getBiography() != null) {
            userProfile.setBiography(updateDto.getBiography());
        }
        
        // Cập nhật thông tin seller nếu là seller đã xác thực
        if (userProfile.isSeller() && userProfile.getVerificationStatus() == VerificationStatus.VERIFIED) {
            if (updateDto.getStoreName() != null) {
                userProfile.setStoreName(updateDto.getStoreName());
            }
            if (updateDto.getBusinessAddress() != null) {
                userProfile.setBusinessAddress(updateDto.getBusinessAddress());
            }
        }

        UserProfile updatedUserProfile = userProfileRepository.save(userProfile);
        return mapToDto(updatedUserProfile);
    }

    @Override
    public List<UserProfileDto> getAllUserProfiles() {
        return userProfileRepository.findAll().stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteUserProfile(String auth0Id) {
        UserProfile userProfile = userProfileRepository.findByAuth0Id(auth0Id)
                .orElseThrow(() -> new ResourceNotFoundException("User profile not found"));
        userProfileRepository.delete(userProfile);
    }

    @Override
    public boolean userProfileExists(String auth0Id) {
        return userProfileRepository.existsByAuth0Id(auth0Id);
    }
    
    // Triển khai các phương thức mới cho seller
    
    @Override
    @Transactional
    public UserProfileDto registerAsSeller(String auth0Id, SellerRegistrationDto registrationDto) {
        UserProfile userProfile = userProfileRepository.findByAuth0Id(auth0Id)
                .orElseThrow(() -> new ResourceNotFoundException("User profile not found"));
        
        // Kiểm tra nếu đã là seller
        if (userProfile.isSeller()) {
            throw new IllegalStateException("User is already a seller");
        }
        
        // Cập nhật thông tin seller
        userProfile.setSeller(true);
        userProfile.setStoreName(registrationDto.getStoreName());
        userProfile.setTaxId(registrationDto.getTaxId());
        userProfile.setBusinessAddress(registrationDto.getBusinessAddress());
        userProfile.setVerificationStatus(VerificationStatus.PENDING);
        
        log.info("User {} registered as seller with store name: {}", auth0Id, registrationDto.getStoreName());
        
        UserProfile updatedUserProfile = userProfileRepository.save(userProfile);
        return mapToDto(updatedUserProfile);
    }
    
    @Override
    public Page<UserProfileDto> getSellers(Pageable pageable) {
        return userProfileRepository.findBySeller(true, pageable)
                .map(this::mapToDto);
    }
    
    @Override
    public Page<UserProfileDto> getSellersByVerificationStatus(VerificationStatus status, Pageable pageable) {
        return userProfileRepository.findBySellerAndVerificationStatus(true, status, pageable)
                .map(this::mapToDto);
    }
    
    // Triển khai các phương thức mới cho admin
    
    @Override
    @Transactional
    public UserProfileDto verifySellerById(Long id, SellerVerificationDto verificationDto, String adminId) {
        UserProfile userProfile = userProfileRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User profile not found"));
        
        return verifySeller(userProfile, verificationDto, adminId);
    }
    
    @Override
    @Transactional
    public UserProfileDto verifySellerByAuth0Id(String auth0Id, SellerVerificationDto verificationDto, String adminId) {
        UserProfile userProfile = userProfileRepository.findByAuth0Id(auth0Id)
                .orElseThrow(() -> new ResourceNotFoundException("User profile not found"));
        
        return verifySeller(userProfile, verificationDto, adminId);
    }
    
    private UserProfileDto verifySeller(UserProfile userProfile, SellerVerificationDto verificationDto, String adminId) {
        // Kiểm tra nếu không phải là seller
        if (!userProfile.isSeller()) {
            throw new IllegalStateException("User is not a seller");
        }
        
        // Cập nhật trạng thái xác thực
        userProfile.setVerificationStatus(verificationDto.getVerificationStatus());
        userProfile.setVerificationDate(LocalDateTime.now());
        userProfile.setVerifiedBy(adminId);
        
        log.info("Seller {} verification status updated to {} by admin {}", 
                userProfile.getAuth0Id(), verificationDto.getVerificationStatus(), adminId);
        
        UserProfile updatedUserProfile = userProfileRepository.save(userProfile);
        return mapToDto(updatedUserProfile);
    }
    
    @Override
    public Page<UserProfileDto> searchUserProfiles(String keyword, Pageable pageable) {
        return userProfileRepository.searchUserProfiles(keyword, pageable)
                .map(this::mapToDto);
    }

    private UserProfileDto mapToDto(UserProfile userProfile) {
        return UserProfileDto.builder()
                .id(userProfile.getId())
                .auth0Id(userProfile.getAuth0Id())
                .email(userProfile.getEmail())
                .fullName(userProfile.getFullName())
                .phone(userProfile.getPhone())
                .fullAddress(userProfile.getFullAddress())
                .dateOfBirth(userProfile.getDateOfBirth())
                .gender(userProfile.getGender())
                .avatarUrl(userProfile.getAvatarUrl())
                .biography(userProfile.getBiography())
                // Thông tin seller
                .seller(userProfile.isSeller())
                .storeName(userProfile.getStoreName())
                .taxId(userProfile.getTaxId())
                .businessAddress(userProfile.getBusinessAddress())
                .verificationStatus(userProfile.getVerificationStatus())
                .verificationDate(userProfile.getVerificationDate())
                .createdAt(userProfile.getCreatedAt())
                .updatedAt(userProfile.getUpdatedAt())
                .build();
    }
}