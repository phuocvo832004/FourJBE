package com.fourj.iamservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {
    private Long id;
    private String auth0Id;
    private String email;
    private String displayName;
    private String phone;
    private String address;
    private String avatarUrl;
    private boolean active;
    private Set<RoleDto> roles;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public UserDto(Long id, String auth0Id, String email, String displayName, boolean active, Set<RoleDto> roles) {
        this.id = id;
        this.auth0Id = auth0Id;
        this.email = email;
        this.displayName = displayName;
        this.active = active;
        this.roles = roles;
    }
    
    public UserDto(Long id, String auth0Id, String email, String displayName, String phone, String address, 
                  String avatarUrl, boolean active, Set<RoleDto> roles) {
        this.id = id;
        this.auth0Id = auth0Id;
        this.email = email;
        this.displayName = displayName;
        this.phone = phone;
        this.address = address;
        this.avatarUrl = avatarUrl;
        this.active = active;
        this.roles = roles;
    }
}