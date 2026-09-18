package com.school.counseling.module.ai.repository;

import com.school.counseling.module.ai.entity.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    List<KnowledgeDocument> findByIsActiveTrueOrderByCreatedAtDesc();

    List<KnowledgeDocument> findByStatusOrderByCreatedAtDesc(String status);
}
