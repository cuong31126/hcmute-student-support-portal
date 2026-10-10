package com.school.counseling.module.chat.repository;

import com.school.counseling.module.chat.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * @deprecated Repository bảng conversations (Prototype - Chưa triển khai UI).
 * Xem chi tiết tại: docs/requirements/chat_module_and_ticketing_architecture_guide.md
 */
@Deprecated
@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    List<Conversation> findByCreatorIdOrderByCreatedAtDesc(Long creatorId);
    List<Conversation> findByDepartmentIdAndStatusOrderByCreatedAtDesc(Long departmentId, String status);
}
