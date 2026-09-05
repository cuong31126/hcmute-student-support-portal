package com.school.counseling.module.auth.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {

    private String accessToken;
    private String tokenType = "Bearer";
    private Long userId;
    private String username;
    private String fullName;
    private String email;
    private String role;
    private Long departmentId;
    private String departmentName;
}
