package com.school.counseling.module.ai.repository;

import com.school.counseling.module.ai.entity.KnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    @Query("SELECT c FROM KnowledgeChunk c LEFT JOIN FETCH c.department LEFT JOIN FETCH c.document WHERE c.isActive = true")
    List<KnowledgeChunk> findAllActiveWithRelations();

    List<KnowledgeChunk> findByDocumentId(Long documentId);

    @Modifying
    @Query("UPDATE KnowledgeChunk c SET c.isActive = false WHERE c.document.id = :documentId")
    void deactivateByDocumentId(@Param("documentId") Long documentId);
}
