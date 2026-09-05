package com.school.counseling.module.chat.repository;

import com.school.counseling.module.chat.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    List<Conversation> findByCreatorIdOrderByCreatedAtDesc(Long creatorId);
    List<Conversation> findByDepartmentIdAndStatusOrderByCreatedAtDesc(Long departmentId, String status);
}
