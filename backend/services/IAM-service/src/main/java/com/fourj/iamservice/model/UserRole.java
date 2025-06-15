package com.fourj.iamservice.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "user_roles", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "role_id"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;
}