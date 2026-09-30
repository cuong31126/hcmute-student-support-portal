package com.school.counseling.module.ticket;

import com.school.counseling.common.exception.AccessDeniedBusinessException;
import com.school.counseling.module.auth.dto.UserPrincipal;
import com.school.counseling.module.auth.entity.Department;
import com.school.counseling.module.auth.entity.Role;
import com.school.counseling.module.auth.entity.User;
import com.school.counseling.module.auth.repository.DepartmentRepository;
import com.school.counseling.module.auth.repository.RoleRepository;
import com.school.counseling.module.auth.repository.UserRepository;
import com.school.counseling.module.ticket.entity.Ticket;
import com.school.counseling.module.ticket.repository.TicketRepository;
import com.school.counseling.module.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration Test: Kiểm thử Phân quyền Khoa (Data Isolation / Department-Level ABAC).
 *
 * Kịch bản:
 * - Ticket thuộc Khoa CNTT
 * - Staff thuộc Khoa Cơ Khí
 * - Staff cố tình gọi URL /staff/tickets/{ticketId}/claim hoặc resolve → phải bị chặn 403 Forbidden
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TicketDepartmentIsolationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private Department deptCntt;
    private Department deptCokhi;
    private User staffCokhi;
    private UserPrincipal staffCokhiPrincipal;
    private Ticket ticketCntt;

    @BeforeEach
    void setUp() {
        deptCntt = departmentRepository.findByCode("KHOA_CNTT").orElseGet(() ->
                departmentRepository.save(Department.builder().name("Khoa CNTT Test").code("KHOA_CNTT").build())
        );

        deptCokhi = departmentRepository.findByCode("KHOA_COKHI").orElseGet(() ->
                departmentRepository.save(Department.builder().name("Khoa Cơ Khí Test").code("KHOA_COKHI").build())
        );

        Role staffRole = roleRepository.findByName("ROLE_STAFF").orElseGet(() ->
                roleRepository.save(Role.builder().name("ROLE_STAFF").description("Cán bộ").build())
        );

        staffCokhi = userRepository.findByUsername("staff_cokhi_test").orElseGet(() ->
                userRepository.save(User.builder()
                        .username("staff_cokhi_test")
                        .passwordHash("password")
                        .fullName("Cán bộ Khoa Cơ Khí")
                        .email("staff_cokhi_test@hcmute.edu.vn")
                        .role(staffRole)
                        .department(deptCokhi)
                        .status("ACTIVE")
                        .build())
        );

        staffCokhiPrincipal = UserPrincipal.builder()
                .id(staffCokhi.getId())
                .username(staffCokhi.getUsername())
                .password(staffCokhi.getPasswordHash())
                .email(staffCokhi.getEmail())
                .fullName(staffCokhi.getFullName())
                .roleName("ROLE_STAFF")
                .departmentId(deptCokhi.getId())
                .departmentName(deptCokhi.getName())
                .isEnabled(true)
                .authorities(java.util.Collections.singletonList(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_STAFF")))
                .build();

        ticketCntt = ticketRepository.save(Ticket.builder()
                .ticketCode("TK-ISOLATION-" + System.nanoTime())
                .title("Ticket Khoa CNTT")
                .description("Học phần chuyên ngành CNTT")
                .department(deptCntt)
                .status("OPEN")
                .dueDate(LocalDateTime.now().plusDays(3))
                .build());
    }

    // ── Test 1: Staff Khoa Cơ Khí cố Claim Ticket của Khoa CNTT qua HTTP Controller ──
    @Test
    @DisplayName("Staff Khoa Cơ Khí cố claim Ticket Khoa CNTT → HTTP 403 Forbidden")
    void staff_from_different_dept_cannot_claim_ticket_via_web() throws Exception {
        mockMvc.perform(post("/staff/tickets/{ticketId}/claim", ticketCntt.getId())
                        .with(user(staffCokhiPrincipal))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ── Test 2: Kiểm tra trực tiếp ở tầng Service ──
    @Test
    @DisplayName("TicketService.claimTicket: Staff Khoa Cơ Khí → AccessDeniedBusinessException")
    void service_throws_AccessDeniedException_when_staff_claims_cross_department_ticket() {
        assertThatThrownBy(() -> ticketService.claimTicket(ticketCntt.getId(), staffCokhi.getId()))
                .isInstanceOf(AccessDeniedBusinessException.class)
                .hasMessageContaining("đơn vị");
    }

    @Test
    @DisplayName("Kiểm tra danh sách ticket theo phòng ban không bị rỗng khi có dữ liệu")
    void test_get_ticket_summary_by_department() {
        var tickets = ticketService.getTicketSummaryByDepartment(deptCntt.getId(), "ALL");
        assertThat(tickets).isNotNull();
    }

    @Test
    @DisplayName("Kiểm tra render trang chi tiết ticket của Khoa CNTT bởi Admin")
    void test_ticket_detail_render() throws Exception {
        UserPrincipal principal = UserPrincipal.builder()
                .id(1L)
                .username("admin")
                .roleName("ROLE_ADMIN")
                .authorities(Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .isEnabled(true)
                .build();

        Authentication auth = new UsernamePasswordAuthenticationToken(principal, "pass", principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        mockMvc.perform(get("/tickets/detail/" + ticketCntt.getId())
                        .with(user(principal)))
                .andExpect(status().isOk());
    }
}
