package com.school.counseling.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileRequest {

    @NotBlank(message = "Họ và tên không được để trống")
    @Size(max = 150, message = "Họ và tên tối đa 150 ký tự")
    private String fullName;

    @Size(max = 30, message = "Số điện thoại tối đa 30 ký tự")
    private String phone;

    @Size(max = 500, message = "Đường dẫn ảnh đại diện tối đa 500 ký tự")
    private String avatarUrl;
}
