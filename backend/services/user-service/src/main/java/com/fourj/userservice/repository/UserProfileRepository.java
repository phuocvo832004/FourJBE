package com.fourj.userservice.repository;

import com.fourj.userservice.model.UserProfile;
import com.fourj.userservice.model.UserProfile.VerificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
    Optional<UserProfile> findByAuth0Id(String auth0Id);
    Optional<UserProfile> findByEmail(String email);
    boolean existsByAuth0Id(String auth0Id);
    boolean existsByEmail(String email);
    
    // Các phương thức truy vấn cho seller
    Page<UserProfile> findBySeller(boolean isSeller, Pageable pageable);
    Page<UserProfile> findBySellerAndVerificationStatus(boolean isSeller, VerificationStatus status, Pageable pageable);
    
    // Các phương thức truy vấn cho admin
    @Query("SELECT u FROM UserProfile u WHERE " +
           "LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.storeName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.taxId) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    Page<UserProfile> searchUserProfiles(@Param("keyword") String keyword, Pageable pageable);
}