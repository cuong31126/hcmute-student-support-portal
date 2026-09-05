package com.school.counseling.module.ai.repository;

import com.school.counseling.module.ai.entity.Faq;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FaqRepository extends JpaRepository<Faq, Long> {

    List<Faq> findByDepartmentIdAndIsActiveTrue(Long departmentId);

    // Tìm kiếm khớp từ khóa hoặc câu hỏi tương đồng
    @Query("SELECT f FROM Faq f WHERE f.isActive = true AND " +
           "(LOWER(f.question) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(f.keywords) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<Faq> searchFaqs(@Param("query") String query);
}
