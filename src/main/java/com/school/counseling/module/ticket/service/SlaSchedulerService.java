package com.school.counseling.module.ticket.service;

import com.school.counseling.module.auth.entity.Department;
import com.school.counseling.module.auth.entity.User;
import com.school.counseling.module.notification.service.EmailAsyncService;
import com.school.counseling.module.ticket.entity.Ticket;
import com.school.counseling.module.ticket.entity.TicketHistory;
import com.school.counseling.module.ticket.repository.TicketHistoryRepository;
import com.school.counseling.module.ticket.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Động Cơ Giám Sát & Tự Động Hóa SLA (SLA Engine Scheduler)
 * - Tự động phát hiện Ticket quá hạn (now > due_date) -> Chuyển OVERDUE & Bắn email cảnh báo
 * - Tự động đóng Ticket sau 72 giờ ở trạng thái RESOLVED nếu sinh viên không khiếu nại
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlaSchedulerService {

    private final TicketRepository ticketRepository;
    private final TicketHistoryRepository ticketHistoryRepository;
    private final EmailAsyncService emailAsyncService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    /**
     * Tự động quét và phát hiện các Ticket quá hạn SLA
     * Tần suất mặc định: Mỗi 15 phút
     */
    @Scheduled(cron = "${app.sla.overdue-scan-cron:0 */15 * * * *}")
    @Transactional
    public int scanAndMarkOverdueTickets() {
        LocalDateTime now = LocalDateTime.now();
        List<Ticket> overdueTickets = ticketRepository.findOverdueTicketsWithRelations(now);

        if (overdueTickets.isEmpty()) {
            log.debug("SLA Scheduler: Không có ticket nào bị vi phạm hạn chót SLA tại thời điểm {}", now);
            return 0;
        }

        log.warn("SLA Scheduler: Phát hiện {} ticket vi phạm hạn chót SLA! Tiến hành chuyển trạng thái OVERDUE...", overdueTickets.size());

        int count = 0;
        for (Ticket ticket : overdueTickets) {
            try {
                String oldStatus = ticket.getStatus();
                ticket.setStatus("OVERDUE");

                String dueStr = (ticket.getDueDate() != null) ? ticket.getDueDate().format(DATE_FORMATTER) : "Không xác định";

                // 1. Ghi nhận nhật ký lịch sử hệ thống
                TicketHistory history = TicketHistory.builder()
                        .ticket(ticket)
                        .actor(null)
                        .actorName("HỆ THỐNG (SLA DETECTOR)")
                        .fromStatus(oldStatus)
                        .toStatus("OVERDUE")
                        .actionNote(String.format("Yêu cầu đã quá hạn cam kết giải quyết (Hạn chót: %s). Hệ thống tự động chuyển sang trạng thái QUÁ HẠN (OVERDUE) và gửi cảnh báo tới đơn vị.", dueStr))
                        .build();

                ticketRepository.save(ticket);
                ticketHistoryRepository.save(history);

                // 2. Gửi email cảnh báo SLA đến Cán bộ hoặc Trưởng Khoa/Phòng
                sendOverdueNotification(ticket);

                count++;
                log.info("SLA Scheduler: Ticket #{} (ID: {}) đã đổi sang OVERDUE thành công.", ticket.getTicketCode(), ticket.getId());
            } catch (Exception e) {
                log.error("SLA Scheduler: Lỗi khi xử lý quá hạn cho ticket ID {}: {}", ticket.getId(), e.getMessage(), e);
            }
        }

        return count;
    }

    /**
     * Tự động đóng (CLOSED) các Ticket ở trạng thái RESOLVED sau 72 giờ không có khiếu nại
     * Tần suất mặc định: Mỗi 30 phút
     */
    @Scheduled(cron = "${app.sla.auto-close-cron:0 */30 * * * *}")
    @Transactional
    public int autoCloseResolvedTickets() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(72);
        List<Ticket> resolvedTickets = ticketRepository.findResolvedTicketsEligibleForAutoClose(threshold);

        if (resolvedTickets.isEmpty()) {
            log.debug("SLA Scheduler: Không có ticket RESOLVED nào vượt quá 72 giờ tại thời điểm kiểm tra.");
            return 0;
        }

        log.info("SLA Scheduler: Phát hiện {} ticket đã giải quyết quá 72 giờ không khiếu nại. Tiến hành tự động đóng...", resolvedTickets.size());

        int count = 0;
        for (Ticket ticket : resolvedTickets) {
            try {
                ticket.setStatus("CLOSED");
                ticket.setClosedAt(LocalDateTime.now());

                // Nếu sinh viên chưa đánh giá, có thể gán mặc định 5 sao (hài lòng vì không khiếu nại)
                if (ticket.getRating() == null) {
                    ticket.setRating(5);
                }

                TicketHistory history = TicketHistory.builder()
                        .ticket(ticket)
                        .actor(null)
                        .actorName("HỆ THỐNG (SLA AUTO-CLOSE)")
                        .fromStatus("RESOLVED")
                        .toStatus("CLOSED")
                        .actionNote("Yêu cầu đã được giải quyết quá 72 giờ và sinh viên không có khiếu nại bổ sung. Hệ thống tự động hoàn tất và đóng yêu cầu (CLOSED).")
                        .build();

                ticketRepository.save(ticket);
                ticketHistoryRepository.save(history);

                count++;
                log.info("SLA Scheduler: Đã tự động đóng ticket #{} (ID: {}) thành công.", ticket.getTicketCode(), ticket.getId());
            } catch (Exception e) {
                log.error("SLA Scheduler: Lỗi khi tự động đóng ticket ID {}: {}", ticket.getId(), e.getMessage(), e);
            }
        }

        return count;
    }

    /**
     * Xác định địa chỉ người nhận và gửi email cảnh báo SLA
     */
    private void sendOverdueNotification(Ticket ticket) {
        try {
            String toEmail = null;
            String recipientName = null;
            Department dept = ticket.getDepartment();
            String deptName = (dept != null) ? dept.getName() : "Khoa / Phòng ban phụ trách";

            // Nếu đã có Cán bộ tiếp nhận -> gửi trực tiếp cho Cán bộ đó
            if (ticket.getAssignedTo() != null && ticket.getAssignedTo().getEmail() != null) {
                User staff = ticket.getAssignedTo();
                toEmail = staff.getEmail();
                recipientName = staff.getFullName();
            } else if (dept != null && dept.getContactEmail() != null) {
                // Nếu chưa có Cán bộ tiếp nhận -> gửi tới email liên hệ của Khoa/Phòng
                toEmail = dept.getContactEmail();
                recipientName = "Ban Lãnh Đạo & Cán Bộ " + deptName;
            }

            if (toEmail != null && !toEmail.isBlank()) {
                emailAsyncService.sendSlaOverdueAlertEmail(
                        toEmail,
                        recipientName,
                        ticket.getTicketCode(),
                        ticket.getTitle(),
                        ticket.getDueDate(),
                        ticket.getPriority(),
                        deptName
                );
            }
        } catch (Exception e) {
            log.warn("SLA Scheduler: Không thể gửi email cảnh báo quá hạn cho ticket #{}: {}", ticket.getTicketCode(), e.getMessage());
        }
    }
}
