package com.school.counseling.module.ai.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.counseling.common.entity.BaseEntity;
import com.school.counseling.module.auth.entity.Department;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.util.List;

/**
 * Entity lưu trữ từng đoạn văn bản đã băm nhỏ (chunk) cùng vector nhúng 768 chiều.
 * Phục vụ tìm kiếm ngữ nghĩa theo cấp bậc (Hierarchical Retrieval) trên RAM.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "knowledge_chunks", indexes = {
        @Index(name = "idx_source_priority", columnList = "source_type, priority_level, is_active"),
        @Index(name = "idx_chunk_year", columnList = "effective_year"),
        @Index(name = "idx_chunk_dept", columnList = "department_id")
})
@SQLDelete(sql = "UPDATE knowledge_chunks SET is_deleted = true WHERE id = ?")
@SQLRestriction("is_deleted = false")
public class KnowledgeChunk extends BaseEntity {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id")
    private KnowledgeDocument document; // NULL nếu chunk thuộc về FAQ tinh tuyển

    @Size(max = 255, message = "Tiêu đề tối đa 255 ký tự")
    @Column(name = "title", length = 255)
    private String title;

    @NotBlank(message = "Nội dung chunk không được để trống")
    @Column(name = "content", columnDefinition = "LONGTEXT", nullable = false)
    private String content;

    @NotBlank(message = "Nguồn tri thức không được để trống")
    @Column(name = "source_type", length = 50, nullable = false)
    private String sourceType; // 'REGULATION' (Công văn PDF) hoặc 'FAQ_CHAT' (Hỏi đáp lịch sử)

    @NotNull(message = "Năm hiệu lực không được để trống")
    @Column(name = "effective_year", nullable = false)
    private Integer effectiveYear;

    @Builder.Default
    @Column(name = "priority_level", nullable = false)
    private Integer priorityLevel = 1; // 1: Cao nhất (PDF quy chế), 2: Tham khảo (FAQ)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @NotBlank(message = "Vector embedding không được để trống")
    @Column(name = "embedding", columnDefinition = "LONGTEXT", nullable = false)
    private String embeddingJson; // Lưu JSON mảng float: [-0.021, 0.045, ...]

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Transient
    private float[] cachedEmbedding;

    /**
     * Chuyển đổi embeddingJson thành mảng float[] để tính toán Cosine Similarity trong RAM
     */
    public float[] getEmbeddingArray() {
        if (cachedEmbedding != null) {
            return cachedEmbedding;
        }
        if (embeddingJson == null || embeddingJson.isBlank()) {
            return new float[0];
        }
        try {
            List<Double> doubles = OBJECT_MAPPER.readValue(embeddingJson, new TypeReference<>() {});
            cachedEmbedding = new float[doubles.size()];
            for (int i = 0; i < doubles.size(); i++) {
                cachedEmbedding[i] = doubles.get(i).floatValue();
            }
            return cachedEmbedding;
        } catch (Exception e) {
            return new float[0];
        }
    }

    /**
     * Gán mảng float[] và tự động serialize sang embeddingJson
     */
    public void setEmbeddingArray(float[] embedding) {
        this.cachedEmbedding = embedding;
        if (embedding == null || embedding.length == 0) {
            this.embeddingJson = "[]";
            return;
        }
        try {
            this.embeddingJson = OBJECT_MAPPER.writeValueAsString(embedding);
        } catch (Exception e) {
            this.embeddingJson = "[]";
        }
    }
}
