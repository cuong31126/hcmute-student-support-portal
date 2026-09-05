package com.school.counseling.module.chat.entity;

import com.school.counseling.common.entity.BaseEntity;
import com.school.counseling.module.auth.entity.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
@Table(name = "messages", indexes = {
    @Index(name = "idx_messages_conversation_created", columnList = "conversation_id, created_at")
})
@SQLDelete(sql = "UPDATE messages SET is_deleted = true WHERE id = ?")
@SQLRestriction("is_deleted = false")
public class Message extends BaseEntity {

    @NotNull(message = "Phiên hội thoại không được để trống")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id")
    private User sender; // Null nếu là Guest hoặc AI

    @NotBlank(message = "Loại người gửi không được để trống")
    @Column(name = "sender_type", length = 20, nullable = false)
    private String senderType; // STUDENT, GUEST, STAFF, AI_BOT

    @NotBlank(message = "Nội dung tin nhắn không được để trống")
    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Builder.Default
    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Attachment> attachments = new ArrayList<>();
}
