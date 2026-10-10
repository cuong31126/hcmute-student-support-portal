package com.school.counseling.module.chat.repository;

import com.school.counseling.module.chat.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository bảng attachments.
 *
 * ⚠️ LƯU Ý BÀN GIAO:
 * Đang được sử dụng chính thức bởi `TicketService` để lưu & truy vấn tệp đính kèm của Ticket.
 * Tuyệt đối KHÔNG XÓA nếu chưa chuyển sang module Ticket.
 * Xem chi tiết tại: docs/requirements/chat_module_and_ticketing_architecture_guide.md
 */
@Repository
public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    @Query("SELECT a FROM Attachment a LEFT JOIN FETCH a.ticketHistory WHERE a.ticket.id = :ticketId ORDER BY a.id ASC")
    List<Attachment> findByTicketId(@Param("ticketId") Long ticketId);

    List<Attachment> findByMessageId(Long messageId);
}
