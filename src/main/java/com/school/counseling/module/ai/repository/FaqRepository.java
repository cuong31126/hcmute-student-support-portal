package com.school.counseling.module.ai.repository;

import com.school.counseling.module.ai.entity.Faq;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FaqRepository extends JpaRepository<Faq, Long> {

    List<Faq> findByDepartmentIdAndIsActiveTrue(Long departmentId);

    Page<Faq> findByIsActiveTrue(Pageable pageable);

    Page<Faq> findByDepartmentIdAndIsActiveTrue(Long departmentId, Pageable pageable);

    Page<Faq> findByIsActiveTrueOrderByViewCountDescCreatedAtDesc(Pageable pageable);

    Page<Faq> findByDepartmentIdAndIsActiveTrueOrderByViewCountDescCreatedAtDesc(Long departmentId, Pageable pageable);

    // Lấy toàn bộ FAQs kèm Department bằng 1 câu query duy nhất (chống Lazy và N+1)
    @Query("SELECT f FROM Faq f LEFT JOIN FETCH f.department WHERE f.isActive = true")
    List<Faq> findAllActiveWithDepartment();

    // Lấy Top FAQs tiêu biểu kèm Department
    @Query("SELECT f FROM Faq f LEFT JOIN FETCH f.department WHERE f.isActive = true ORDER BY f.viewCount DESC, f.createdAt DESC")
    List<Faq> findTopActiveWithDepartment(Pageable pageable);

    @Query("SELECT f FROM Faq f LEFT JOIN FETCH f.department WHERE f.department.id = :deptId AND f.isActive = true ORDER BY f.viewCount DESC, f.createdAt DESC")
    List<Faq> findTopByDepartmentWithDepartment(@Param("deptId") Long deptId, Pageable pageable);

    // Tìm kiếm khớp từ khóa trong câu hỏi, từ khóa hoặc câu trả lời
    @Query("SELECT f FROM Faq f LEFT JOIN FETCH f.department WHERE f.isActive = true AND " +
           "(LOWER(f.question) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(f.keywords) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(f.answer) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<Faq> searchFaqs(@Param("query") String query);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "DELETE FROM faqs WHERE is_deleted = true", nativeQuery = true)
    void hardDeleteSoftDeletedFaqs();

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "DELETE FROM faqs", nativeQuery = true)
    void hardDeleteAllFaqs();

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "ALTER TABLE faqs AUTO_INCREMENT = 1", nativeQuery = true)
    void resetAutoIncrement();
}
