package com.school.counseling.module.admin;

import com.school.counseling.module.admin.dto.UpdateUserAdminDto;
import com.school.counseling.module.admin.dto.UserManagementDto;
import com.school.counseling.module.admin.service.AdminUserService;
import com.school.counseling.module.auth.entity.Department;
import com.school.counseling.module.auth.entity.Role;
import com.school.counseling.module.auth.entity.User;
import com.school.counseling.module.auth.repository.DepartmentRepository;
import com.school.counseling.module.auth.repository.RoleRepository;
import com.school.counseling.module.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private DepartmentRepository departmentRepository;

    @InjectMocks
    private AdminUserService adminUserService;

    private User sampleUser;
    private Role studentRole;
    private Role staffRole;
    private Department sampleDept;

    @BeforeEach
    void setUp() {
        studentRole = Role.builder().name("ROLE_STUDENT").description("Sinh viên").build();
        staffRole = Role.builder().name("ROLE_STAFF").description("Cán bộ").build();
        sampleDept = Department.builder().name("Khoa CNTT").code("KHOA_CNTT").build();

        sampleUser = User.builder()
                .username("sv2026")
                .fullName("Sinh Vien 2026")
                .email("sv2026@hcmute.edu.vn")
                .phone("0987654321")
                .role(studentRole)
                .department(null)
                .status("ACTIVE")
                .build();
    }

    @Test
    @DisplayName("AdminUserService: Tìm kiếm và phân trang người dùng")
    void testGetUsers_shouldReturnPagedDtos() {
        when(userRepository.searchUsers(eq("sv"), isNull(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(sampleUser), PageRequest.of(0, 10), 1));

        Page<UserManagementDto> result = adminUserService.getUsers("sv", null, null, "ALL", PageRequest.of(0, 10));

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals("sv2026", result.getContent().get(0).getUsername());
        assertEquals("ROLE_STUDENT", result.getContent().get(0).getRoleName());
    }

    @Test
    @DisplayName("AdminUserService: Toggle Lock từ ACTIVE -> LOCKED")
    void testToggleUserLock_activeToLocked() {
        when(userRepository.findByIdWithRoleAndDepartment(1L)).thenReturn(Optional.of(sampleUser));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserManagementDto result = adminUserService.toggleUserLock(1L);

        assertNotNull(result);
        assertEquals("LOCKED", result.getStatus());
        verify(userRepository, times(1)).save(sampleUser);
    }

    @Test
    @DisplayName("AdminUserService: Toggle Lock từ LOCKED -> ACTIVE")
    void testToggleUserLock_lockedToActive() {
        sampleUser.setStatus("LOCKED");
        when(userRepository.findByIdWithRoleAndDepartment(1L)).thenReturn(Optional.of(sampleUser));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserManagementDto result = adminUserService.toggleUserLock(1L);

        assertNotNull(result);
        assertEquals("ACTIVE", result.getStatus());
        verify(userRepository, times(1)).save(sampleUser);
    }

    @Test
    @DisplayName("AdminUserService: Cập nhật thông tin, vai trò và gán đơn vị cho người dùng")
    void testUpdateUser_shouldUpdateFields() {
        UpdateUserAdminDto dto = UpdateUserAdminDto.builder()
                .fullName("ThS. Nguyen Van B")
                .email("nguyenvanb@hcmute.edu.vn")
                .phone("0123456789")
                .roleId(2L)
                .departmentId(1L)
                .status("ACTIVE")
                .build();

        when(userRepository.findByIdWithRoleAndDepartment(1L)).thenReturn(Optional.of(sampleUser));
        when(roleRepository.findById(2L)).thenReturn(Optional.of(staffRole));
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(sampleDept));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserManagementDto result = adminUserService.updateUser(1L, dto);

        assertNotNull(result);
        assertEquals("ThS. Nguyen Van B", result.getFullName());
        assertEquals("ROLE_STAFF", result.getRoleName());
        assertEquals("Khoa CNTT", result.getDepartmentName());
        verify(userRepository, times(1)).save(sampleUser);
    }
}
