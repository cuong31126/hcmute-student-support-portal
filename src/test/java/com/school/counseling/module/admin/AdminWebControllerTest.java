package com.school.counseling.module.admin;

import com.school.counseling.module.admin.dto.AdminSlaDashboardDto;
import com.school.counseling.module.admin.dto.DepartmentAdminDto;
import com.school.counseling.module.admin.dto.UserManagementDto;
import com.school.counseling.module.admin.service.AdminDashboardService;
import com.school.counseling.module.admin.service.AdminDepartmentService;
import com.school.counseling.module.admin.service.AdminUserService;
import com.school.counseling.module.auth.entity.Department;
import com.school.counseling.module.auth.entity.Role;
import com.school.counseling.module.auth.repository.DepartmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AdminWebControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminDashboardService adminDashboardService;

    @MockBean
    private AdminUserService adminUserService;

    @MockBean
    private AdminDepartmentService adminDepartmentService;

    @MockBean
    private DepartmentRepository departmentRepository;

    @Test
    @DisplayName("GUEST truy cập /admin/dashboard -> Chuyển hướng sang trang đăng nhập 302")
    void guestAccessAdminDashboard_shouldRedirectToLogin() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("ROLE_STUDENT truy cập /admin/dashboard -> Bị chặn 403 Forbidden")
    void studentAccessAdminDashboard_shouldReturnForbidden() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("ROLE_STAFF truy cập /admin/users -> Bị chặn 403 Forbidden")
    void staffAccessAdminUsers_shouldReturnForbidden() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ROLE_ADMIN truy cập /admin -> Chuyển hướng sang /admin/dashboard")
    void adminAccessAdminRoot_shouldRedirectToDashboard() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/dashboard"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ROLE_ADMIN truy cập /admin/dashboard -> Trả về 200 OK và view admin/dashboard")
    void adminAccessDashboard_shouldReturn200() throws Exception {
        AdminSlaDashboardDto mockStats = AdminSlaDashboardDto.builder()
                .totalTickets(10)
                .openTickets(2)
                .inProgressTickets(3)
                .resolvedTickets(5)
                .closedTickets(0)
                .overdueTickets(1)
                .slaComplianceRate(80.0)
                .priorityStats(Map.of("URGENT", 2L, "HIGH", 1L, "MEDIUM", 5L, "LOW", 2L))
                .statusStats(Map.of("OPEN", 2L, "IN_PROGRESS", 3L, "RESOLVED", 5L, "CLOSED", 0L, "OVERDUE", 1L))
                .departmentStats(Collections.emptyList())
                .overdueTicketsList(Collections.emptyList())
                .build();

        when(adminDashboardService.getSlaDashboardStats("ALL", null)).thenReturn(mockStats);
        when(departmentRepository.findAll()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(model().attributeExists("stats"))
                .andExpect(model().attributeExists("departments"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ROLE_ADMIN truy cập /admin/users -> Trả về 200 OK và view admin/users")
    void adminAccessUsersList_shouldReturn200() throws Exception {
        UserManagementDto userDto = UserManagementDto.builder()
                .id(1L)
                .username("test_user")
                .fullName("Test User")
                .email("test@hcmute.edu.vn")
                .roleName("ROLE_STUDENT")
                .status("ACTIVE")
                .build();

        when(adminUserService.getUsers(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(userDto), PageRequest.of(0, 15), 1));
        when(adminUserService.getAllRoles()).thenReturn(List.of(Role.builder().name("ROLE_STUDENT").build()));
        when(adminUserService.getAllDepartments()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/users"))
                .andExpect(model().attributeExists("usersPage"))
                .andExpect(model().attributeExists("roles"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ROLE_ADMIN gọi AJAX toggle-lock -> Trả về 200 OK JSON success")
    void adminToggleUserLock_shouldReturnSuccess() throws Exception {
        UserManagementDto userDto = UserManagementDto.builder()
                .id(2L)
                .username("student01")
                .fullName("Student 01")
                .status("LOCKED")
                .build();

        when(adminUserService.toggleUserLock(2L)).thenReturn(userDto);

        mockMvc.perform(post("/api/admin/users/2/toggle-lock").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("LOCKED"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ROLE_ADMIN truy cập /admin/departments -> Trả về 200 OK và view admin/departments")
    void adminAccessDepartmentsList_shouldReturn200() throws Exception {
        DepartmentAdminDto deptDto = DepartmentAdminDto.builder()
                .id(1L)
                .name("Khoa CNTT")
                .code("KHOA_CNTT")
                .isActive(true)
                .build();

        when(adminDepartmentService.getAllDepartmentsWithStats()).thenReturn(List.of(deptDto));

        mockMvc.perform(get("/admin/departments"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/departments"))
                .andExpect(model().attributeExists("departments"));
    }
}
