package com.school.counseling.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginRequest {

    @NotBlank(message = "Tên đăng nhập hoặc Email không được để trống")
    private String usernameOrEmail;

    @NotBlank(message = "Mật khẩu không được để trống")
    private String password;
}
