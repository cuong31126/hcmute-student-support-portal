package com.school.counseling.module.chat.entity;

import com.school.counseling.common.entity.BaseEntity;
import com.school.counseling.module.auth.entity.Department;
import com.school.counseling.module.auth.entity.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "conversations")
@SQLDelete(sql = "UPDATE conversations SET is_deleted = true WHERE id = ?")
@SQLRestriction("is_deleted = false")
public class Conversation extends BaseEntity {

    @NotBlank(message = "Tiêu đề hội thoại không được để trống")
    @Size(max = 255, message = "Tiêu đề tối đa 255 ký tự")
    @Column(name = "title", length = 255, nullable = false)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id")
    private User creator; // Null nếu là Guest

    @Size(max = 150, message = "Email guest tối đa 150 ký tự")
    @Column(name = "guest_email", length = 150)
    private String guestEmail;

    @NotNull(message = "Đơn vị tư vấn không được để trống")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @Builder.Default
    @Column(name = "status", length = 30, nullable = false)
    private String status = "ACTIVE"; // ACTIVE, CONVERTED_TO_TICKET, CLOSED

    @Builder.Default
    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    private List<Message> messages = new ArrayList<>();
}
