package com.school.counseling.module.ticket;

import com.school.counseling.common.exception.AccessDeniedBusinessException;
import com.school.counseling.module.ticket.service.TicketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration Test: Kiểm thử Phân quyền Khoa (Data Isolation / Department-Level ABAC).
 *
 * Kịch bản:
 * - Ticket #136469 thuộc Khoa CNTT (departmentId=4)
 * - Staff "can-bo-co-khi" thuộc Khoa Cơ Khí (departmentId=7)
 * - Staff cố tình gọi URL /staff/tickets/136469/claim hoặc resolve → phải bị chặn
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TicketDepartmentIsolationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TicketService ticketService;

    // ── Test 1: Staff Khoa Cơ Khí cố Claim Ticket của Khoa CNTT qua HTTP Controller ──
    @Test
    @DisplayName("Staff Khoa Cơ Khí cố claim Ticket Khoa CNTT → HTTP 302 redirect về dashboard với errorMessage")
    @WithMockUser(username = "can-bo-co-khi@hcmute.edu.vn", roles = {"STAFF"})
    void staff_from_different_dept_cannot_claim_ticket_via_web() throws Exception {
        Long ticketIdBelongsToCntt = 136469L;

        mockMvc.perform(post("/staff/tickets/{ticketId}/claim", ticketIdBelongsToCntt))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/staff/tickets"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    // ── Test 2: Kiểm tra trực tiếp ở tầng Service ──
    @Test
    @DisplayName("TicketService.claimTicket: Staff Khoa Cơ Khí → AccessDeniedBusinessException")
    void service_throws_AccessDeniedException_when_staff_claims_cross_department_ticket() {
        Long staffCokhi_Id = 999L;          // Staff thuộc Khoa Cơ Khí (departmentId=7)
        Long ticketCntt_Id = 136469L;       // Ticket thuộc Khoa CNTT (departmentId=4)

        assertThatThrownBy(() -> ticketService.claimTicket(ticketCntt_Id, staffCokhi_Id))
                .isInstanceOf(AccessDeniedBusinessException.class)
                .hasMessageContaining("đơn vị");
    }

    // ── Test 3: Staff đúng Khoa CNTT claim thành công ──
    @Test
    @DisplayName("Staff đúng Khoa CNTT claim Ticket Khoa CNTT → Thành công, không ném exception")
    @WithMockUser(username = "can-bo-cntt@hcmute.edu.vn", roles = {"STAFF"})
    void staff_from_correct_dept_can_claim_ticket() throws Exception {
        Long ticketIdBelongsToCntt = 1L; // Ticket OPEN thuộc Khoa CNTT trong DB test

        mockMvc.perform(post("/staff/tickets/{ticketId}/claim", ticketIdBelongsToCntt))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));
    }
}
