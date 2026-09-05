package com.school.counseling.module.auth.entity;

import com.school.counseling.common.entity.BaseEntity;
import jakarta.persistence.*;
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
@Table(name = "roles")
@SQLDelete(sql = "UPDATE roles SET is_deleted = true WHERE id = ?")
@SQLRestriction("is_deleted = false")
public class Role extends BaseEntity {

    @NotBlank(message = "Tên vai trò không được để trống")
    @Size(max = 50, message = "Tên vai trò tối đa 50 ký tự")
    @Column(name = "name", length = 50, nullable = false, unique = true)
    private String name;

    @Size(max = 255, message = "Mô tả tối đa 255 ký tự")
    @Column(name = "description", length = 255)
    private String description;
}
