package com.school.counseling.module.ticket.repository;

import com.school.counseling.module.ticket.entity.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, Long> {

    Optional<Ticket> findByTicketCode(String ticketCode);

    Optional<Ticket> findByGuestToken(String guestToken);

    List<Ticket> findByCreatorIdOrderByCreatedAtDesc(Long creatorId);

    List<Ticket> findByDepartmentIdOrderByCreatedAtDesc(Long departmentId);

    List<Ticket> findByDepartmentIdAndStatusOrderByCreatedAtDesc(Long departmentId, String status);

    // Truy vấn danh sách Ticket sắp đến hạn trong vòng 24 giờ
    @Query("SELECT t FROM Ticket t WHERE t.status IN ('OPEN', 'IN_PROGRESS') AND t.dueDate BETWEEN :now AND :threshold")
    List<Ticket> findDueSoonTickets(@Param("now") LocalDateTime now, @Param("threshold") LocalDateTime threshold);

    // Truy vấn danh sách Ticket quá hạn (OVERDUE)
    @Query("SELECT t FROM Ticket t WHERE t.status IN ('OPEN', 'IN_PROGRESS') AND t.dueDate < :now")
    List<Ticket> findOverdueTickets(@Param("now") LocalDateTime now);
}
