package com.fourj.iamservice.repository;

import com.fourj.iamservice.model.Role;
import com.fourj.iamservice.model.User;
import com.fourj.iamservice.model.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, Long> {
    List<UserRole> findByUser(User user);
    List<UserRole> findByUserAuth0Id(String auth0Id);
    boolean existsByUserAndRole(User user, Role role);
    Optional<UserRole> findByUserAndRole(User user, Role role);
}