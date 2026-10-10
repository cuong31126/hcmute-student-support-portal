package com.school.counseling.module.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileDto {
    private Long id;
    private String username;
    private String fullName;
    private String email;
    private String phone;
    private String avatarUrl;
    private String roleName;
    private String roleDescription;
    private Long departmentId;
    private String departmentName;
    private String status;
    private LocalDateTime createdAt;
}
