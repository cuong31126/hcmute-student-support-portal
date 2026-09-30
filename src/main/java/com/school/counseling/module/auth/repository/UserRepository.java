package com.school.counseling.module.auth.repository;

import com.school.counseling.module.auth.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    List<User> findByDepartmentId(Long departmentId);
    long countByDepartmentId(Long departmentId);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.role LEFT JOIN FETCH u.department WHERE u.id = :id")
    Optional<User> findByIdWithRoleAndDepartment(@Param("id") Long id);

    @Query(
        value = """
            SELECT u FROM User u
            LEFT JOIN FETCH u.role
            LEFT JOIN FETCH u.department
            WHERE (:keyword IS NULL OR LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%'))
                                 OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                                 OR LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:roleId IS NULL OR u.role.id = :roleId)
              AND (:departmentId IS NULL OR u.department.id = :departmentId)
              AND (:status IS NULL OR u.status = :status)
            ORDER BY u.id DESC
        """,
        countQuery = """
            SELECT COUNT(u) FROM User u
            WHERE (:keyword IS NULL OR LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%'))
                                 OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                                 OR LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:roleId IS NULL OR u.role.id = :roleId)
              AND (:departmentId IS NULL OR u.department.id = :departmentId)
              AND (:status IS NULL OR u.status = :status)
        """
    )
    Page<User> searchUsers(
            @Param("keyword") String keyword,
            @Param("roleId") Long roleId,
            @Param("departmentId") Long departmentId,
            @Param("status") String status,
            Pageable pageable
    );
}
