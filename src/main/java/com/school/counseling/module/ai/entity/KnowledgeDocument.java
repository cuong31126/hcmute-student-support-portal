package com.school.counseling.module.ai.entity;

import com.school.counseling.common.entity.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * Entity đại diện cho Hồ sơ Tài liệu / Công văn Quy chế PDF do Quản trị viên tải lên.
 * Quản lý vòng đời xử lý nền (@Async) và cơ chế đào thải văn bản cũ (superseded_by_id).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "knowledge_documents", indexes = {
        @Index(name = "idx_doc_status", columnList = "status"),
        @Index(name = "idx_doc_active", columnList = "is_active")
})
@SQLDelete(sql = "UPDATE knowledge_documents SET is_deleted = true WHERE id = ?")
@SQLRestriction("is_deleted = false")
public class KnowledgeDocument extends BaseEntity {

    @NotBlank(message = "Tiêu đề văn bản không được để trống")
    @Size(max = 255, message = "Tiêu đề tối đa 255 ký tự")
    @Column(name = "title", length = 255, nullable = false)
    private String title;

    @NotBlank(message = "Tên tệp không được để trống")
    @Size(max = 255, message = "Tên tệp tối đa 255 ký tự")
    @Column(name = "file_name", length = 255, nullable = false)
    private String fileName;

    @NotBlank(message = "Đường dẫn tệp không được để trống")
    @Size(max = 500, message = "Đường dẫn tối đa 500 ký tự")
    @Column(name = "file_path", length = 500, nullable = false)
    private String filePath;

    @NotNull(message = "Năm hiệu lực không được để trống")
    @Column(name = "effective_year", nullable = false)
    private Integer effectiveYear;

    @Builder.Default
    @Column(name = "status", length = 50, nullable = false)
    private String status = "PENDING"; // PENDING, PROCESSING, COMPLETED, FAILED

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Builder.Default
    @Column(name = "total_chunks")
    private Integer totalChunks = 0;

    @Size(max = 100, message = "Số hiệu công văn tối đa 100 ký tự")
    @Column(name = "document_code", length = 100)
    private String documentCode;

    @Size(max = 150, message = "Cơ quan ban hành tối đa 150 ký tự")
    @Column(name = "issuer", length = 150)
    private String issuer;

    @Size(max = 50, message = "Danh mục tối đa 50 ký tự")
    @Column(name = "category", length = 50)
    private String category;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "page_count")
    private Integer pageCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "superseded_by_id")
    private KnowledgeDocument supersededBy; // Tài liệu mới hơn thay thế cho văn bản này

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}
