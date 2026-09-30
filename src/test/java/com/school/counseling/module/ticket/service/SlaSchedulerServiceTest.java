package com.school.counseling.module.ticket.service;

import com.school.counseling.module.auth.entity.Department;
import com.school.counseling.module.auth.entity.User;
import com.school.counseling.module.notification.service.EmailAsyncService;
import com.school.counseling.module.ticket.entity.Ticket;
import com.school.counseling.module.ticket.entity.TicketHistory;
import com.school.counseling.module.ticket.repository.TicketHistoryRepository;
import com.school.counseling.module.ticket.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlaSchedulerServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private TicketHistoryRepository ticketHistoryRepository;

    @Mock
    private EmailAsyncService emailAsyncService;

    @InjectMocks
    private SlaSchedulerService slaSchedulerService;

    private Department department;
    private User staff;

    @BeforeEach
    void setUp() {
        department = Department.builder()
                .id(1L)
                .name("Phòng Đào Tạo")
                .contactEmail("daotao@hcmute.edu.vn")
                .build();

        staff = User.builder()
                .id(10L)
                .fullName("ThS. Nguyễn Văn A")
                .email("staff.daotao@hcmute.edu.vn")
                .department(department)
                .build();
    }

    @Test
    @DisplayName("scanAndMarkOverdueTickets: Chuyển ticket quá hạn sang OVERDUE và gửi email cảnh báo")
    void testScanAndMarkOverdueTickets() {
        // Given
        Ticket ticket = Ticket.builder()
                .id(100L)
                .ticketCode("TK-20260905-0001")
                .title("Xin cấp bảng điểm gấp")
                .status("IN_PROGRESS")
                .priority("URGENT")
                .dueDate(LocalDateTime.now().minusHours(2)) // Đã quá hạn 2 tiếng
                .department(department)
                .assignedTo(staff)
                .build();

        when(ticketRepository.findOverdueTicketsWithRelations(any(LocalDateTime.class)))
                .thenReturn(List.of(ticket));

        // When
        int count = slaSchedulerService.scanAndMarkOverdueTickets();

        // Then
        assertThat(count).isEqualTo(1);
        assertThat(ticket.getStatus()).isEqualTo("OVERDUE");

        verify(ticketRepository, times(1)).save(ticket);

        ArgumentCaptor<TicketHistory> historyCaptor = ArgumentCaptor.forClass(TicketHistory.class);
        verify(ticketHistoryRepository, times(1)).save(historyCaptor.capture());
        TicketHistory savedHistory = historyCaptor.getValue();
        assertThat(savedHistory.getFromStatus()).isEqualTo("IN_PROGRESS");
        assertThat(savedHistory.getToStatus()).isEqualTo("OVERDUE");
        assertThat(savedHistory.getActorName()).contains("SLA DETECTOR");

        verify(emailAsyncService, times(1)).sendSlaOverdueAlertEmail(
                eq("staff.daotao@hcmute.edu.vn"),
                eq("ThS. Nguyễn Văn A"),
                eq("TK-20260905-0001"),
                eq("Xin cấp bảng điểm gấp"),
                any(LocalDateTime.class),
                eq("URGENT"),
                eq("Phòng Đào Tạo")
        );
    }

    @Test
    @DisplayName("scanAndMarkOverdueTickets: Trả về 0 khi không có ticket quá hạn")
    void testScanAndMarkOverdueTickets_NoTickets() {
        when(ticketRepository.findOverdueTicketsWithRelations(any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());

        int count = slaSchedulerService.scanAndMarkOverdueTickets();

        assertThat(count).isEqualTo(0);
        verify(ticketRepository, never()).save(any());
        verify(ticketHistoryRepository, never()).save(any());
        verifyNoInteractions(emailAsyncService);
    }

    @Test
    @DisplayName("autoCloseResolvedTickets: Tự động đóng ticket RESOLVED quá 72h và gán rating mặc định")
    void testAutoCloseResolvedTickets() {
        // Given
        Ticket ticket = Ticket.builder()
                .id(200L)
                .ticketCode("TK-20260905-0002")
                .title("Hỏi về học bổng khuyến khích")
                .status("RESOLVED")
                .resolvedAt(LocalDateTime.now().minusHours(80)) // Đã giải quyết 80 tiếng trước (>72h)
                .department(department)
                .rating(null) // Chưa đánh giá
                .build();

        when(ticketRepository.findResolvedTicketsEligibleForAutoClose(any(LocalDateTime.class)))
                .thenReturn(List.of(ticket));

        // When
        int count = slaSchedulerService.autoCloseResolvedTickets();

        // Then
        assertThat(count).isEqualTo(1);
        assertThat(ticket.getStatus()).isEqualTo("CLOSED");
        assertThat(ticket.getClosedAt()).isNotNull();
        assertThat(ticket.getRating()).isEqualTo(5); // Đánh giá mặc định

        verify(ticketRepository, times(1)).save(ticket);

        ArgumentCaptor<TicketHistory> historyCaptor = ArgumentCaptor.forClass(TicketHistory.class);
        verify(ticketHistoryRepository, times(1)).save(historyCaptor.capture());
        TicketHistory savedHistory = historyCaptor.getValue();
        assertThat(savedHistory.getFromStatus()).isEqualTo("RESOLVED");
        assertThat(savedHistory.getToStatus()).isEqualTo("CLOSED");
        assertThat(savedHistory.getActorName()).contains("SLA AUTO-CLOSE");
    }
}
