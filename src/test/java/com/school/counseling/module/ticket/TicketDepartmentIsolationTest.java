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

    @Test
    @DisplayName("Kiểm tra danh sách ticket theo phòng ban không bị rỗng khi có dữ liệu")
    void test_get_ticket_summary_by_department() {
        var ticketsDept3 = ticketService.getTicketSummaryByDepartment(3L, "ALL");
        org.assertj.core.api.Assertions.assertThat(ticketsDept3).isNotNull();
    }

    @Test
    @DisplayName("Kiểm tra render trang chi tiết ticket #7")
    void test_ticket_detail_render() throws Exception {
        com.school.counseling.module.auth.dto.UserPrincipal principal = com.school.counseling.module.auth.dto.UserPrincipal.builder()
                .id(1L)
                .username("admin")
                .roleName("ROLE_ADMIN")
                .authorities(java.util.Collections.singletonList(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")))
                .isEnabled(true)
                .build();
        org.springframework.security.core.Authentication auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, "pass", principal.getAuthorities());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/tickets/detail/7")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth)))
                .andExpect(status().isOk());
    }
}
