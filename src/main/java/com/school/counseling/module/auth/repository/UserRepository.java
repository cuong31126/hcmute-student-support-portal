package com.school.counseling.module.auth.repository;

import com.school.counseling.module.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    List<User> findByDepartmentId(Long departmentId);

    @org.springframework.data.jpa.repository.Query("SELECT u FROM User u LEFT JOIN FETCH u.role LEFT JOIN FETCH u.department WHERE u.id = :id")
    Optional<User> findByIdWithRoleAndDepartment(@org.springframework.data.repository.query.Param("id") Long id);
}
