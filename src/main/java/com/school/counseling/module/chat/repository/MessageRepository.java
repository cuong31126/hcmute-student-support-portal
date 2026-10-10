package com.school.counseling.module.chat.repository;

import com.school.counseling.module.chat.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * @deprecated Repository bảng messages (Prototype - Chưa triển khai UI).
 * Xem chi tiết tại: docs/requirements/chat_module_and_ticketing_architecture_guide.md
 */
@Deprecated
@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findByConversationIdOrderByCreatedAtAsc(Long conversationId);
}
