package com.school.counseling.module.auth.entity;

import com.school.counseling.common.entity.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "departments")
@SQLDelete(sql = "UPDATE departments SET is_deleted = true WHERE id = ?")
@SQLRestriction("is_deleted = false")
public class Department extends BaseEntity {

    @NotBlank(message = "Tên đơn vị không được để trống")
    @Size(max = 150, message = "Tên đơn vị tối đa 150 ký tự")
    @Column(name = "name", length = 150, nullable = false, unique = true)
    private String name;

    @NotBlank(message = "Mã đơn vị không được để trống")
    @Size(max = 50, message = "Mã đơn vị tối đa 50 ký tự")
    @Column(name = "code", length = 50, nullable = false, unique = true)
    private String code;

    @Size(max = 100, message = "Văn phòng tối đa 100 ký tự")
    @Column(name = "office_location", length = 100)
    private String officeLocation;

    @Email(message = "Email không đúng định dạng")
    @Size(max = 100, message = "Email tối đa 100 ký tự")
    @Column(name = "contact_email", length = 100)
    private String contactEmail;

    @Size(max = 50, message = "Số điện thoại tối đa 50 ký tự")
    @Column(name = "contact_phone", length = 50)
    private String contactPhone;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}
