package com.school.counseling.module.ticket.service;

import com.school.counseling.module.auth.entity.Department;
import com.school.counseling.module.chat.repository.AttachmentRepository;
import com.school.counseling.module.ticket.dto.TicketResponseDto;
import com.school.counseling.module.ticket.entity.Ticket;
import com.school.counseling.module.ticket.repository.TicketHistoryRepository;
import com.school.counseling.module.ticket.repository.TicketRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GuestCsatRatingTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private TicketHistoryRepository ticketHistoryRepository;

    @Mock
    private AttachmentRepository attachmentRepository;

    @Mock
    private SlaCalculatorService slaCalculatorService;

    @InjectMocks
    private TicketService ticketService;

    @Test
    @DisplayName("Guest gửi đánh giá CSAT: Ticket cập nhật rating và chuyển sang CLOSED nếu đang RESOLVED")
    void testRateTicketByGuest_shouldUpdateRatingAndClose() {
        Department dept = Department.builder().name("Phòng Tuyển sinh").code("TUYEN_SINH").build();

        Ticket ticket = Ticket.builder()
                .ticketCode("TK-GUEST-001")
                .title("Tư vấn tuyển sinh 2026")
                .description("Hỏi điểm chuẩn")
                .guestName("Nguyễn Văn Thí Sinh")
                .guestEmail("thisinh@gmail.com")
                .guestToken("token-uuid-123456")
                .department(dept)
                .status("RESOLVED")
                .dueDate(LocalDateTime.now().plusDays(2))
                .attachments(new ArrayList<>())
                .build();

        when(ticketRepository.findByGuestTokenWithRelations("token-uuid-123456")).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(attachmentRepository.findByTicketId(any())).thenReturn(Collections.emptyList());
        when(slaCalculatorService.isOverdue(any(), any())).thenReturn(false);
        when(slaCalculatorService.isDueSoon(any(), any())).thenReturn(false);

        TicketResponseDto response = ticketService.rateTicketByGuest("token-uuid-123456", 5, "Cán bộ giải đáp rất nhiệt tình!");

        assertNotNull(response);
        assertEquals(5, response.getRating());
        assertEquals("CLOSED", response.getStatus());
        verify(ticketRepository, times(1)).save(ticket);
        verify(ticketHistoryRepository, times(1)).save(any());
    }
}
