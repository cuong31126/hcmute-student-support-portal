package com.school.counseling.module.ticket.controller;

import com.school.counseling.module.auth.dto.UserPrincipal;
import com.school.counseling.module.ticket.service.TicketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class StaffTicketSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TicketService ticketService;

    @Test
    @DisplayName("Reproduction Test (IDOR): Cán bộ Khoa 1 cố tình xem Ticket của Khoa 2 -> Phải bị chặn 403 Forbidden")
    void staffFromDepartment1_cannotAccessTicketsOfDepartment2() throws Exception {
        // Cán bộ thuộc Khoa 1 (departmentId = 1L)
        UserPrincipal staffDept1 = UserPrincipal.builder()
                .id(10L)
                .username("staff_cntt")
                .password("password123")
                .email("staff@cntt.edu.vn")
                .fullName("Cán bộ Khoa CNTT")
                .roleName("ROLE_STAFF")
                .departmentId(1L)
                .departmentName("Khoa Công nghệ Thông tin")
                .isEnabled(true)
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_STAFF")))
                .build();

        // Gửi request truy vấn Ticket của Khoa 2 (?departmentId=2)
        // Hành vi kỳ vọng bảo mật: Phải trả về 403 Forbidden
        // Hành vi lỗi thực tế trên code cũ: Trả về 200 OK (Bài test sẽ FAILED - Red)
        mockMvc.perform(get("/api/v1/staff/tickets")
                        .param("departmentId", "2")
                        .with(user(staffDept1)))
                .andExpect(status().isForbidden());
    }
}
