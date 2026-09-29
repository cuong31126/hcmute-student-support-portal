package com.school.counseling.module.ticket.repository;

import com.school.counseling.module.ticket.entity.TicketHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TicketHistoryRepository extends JpaRepository<TicketHistory, Long> {

    @Query("SELECT h FROM TicketHistory h " +
           "LEFT JOIN FETCH h.actor a " +
           "LEFT JOIN FETCH a.role " +
           "WHERE h.ticket.id = :ticketId " +
           "ORDER BY h.createdAt ASC")
    List<TicketHistory> findByTicketIdOrderByCreatedAtAsc(@Param("ticketId") Long ticketId);
}
