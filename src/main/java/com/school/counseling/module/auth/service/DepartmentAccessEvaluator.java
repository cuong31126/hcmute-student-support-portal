package com.school.counseling.module.auth.service;

import com.school.counseling.module.auth.dto.UserPrincipal;
import com.school.counseling.module.ticket.dto.TicketAccessAuthInfo;
import com.school.counseling.module.ticket.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Custom Security Bean hỗ trợ SpEL Expression phân quyền theo Khoa/Phòng & chống IDOR Ticket.
 * Sử dụng:
 *   - @PreAuthorize("@deptSecurity.canAccessDepartment(#deptId)")
 *   - @PreAuthorize("@deptSecurity.canAccessTicket(#ticketId)")
 */
@Slf4j
@Component("deptSecurity")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DepartmentAccessEvaluator {

    private static final Set<String> PRIVILEGED_ROLES = Set.of(
            "ROLE_ADMIN",
            "ROLE_SUPER_ADMIN",
            "ROLE_SUPERADMIN"
    );

    private final TicketRepository ticketRepository;

    /**
     * Lấy Principal của người dùng hiện tại từ Security Context
     */
    public UserPrincipal getCurrentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UserPrincipal principal) {
            return principal;
        }
        return null;
    }

    /**
     * Kiểm tra người dùng có quyền Quản trị cấp cao (Admin / SuperAdmin) không
     */
    private boolean isPrivilegedUser(UserPrincipal user) {
        if (user == null) {
            return false;
        }

        // Kiểm tra qua roleName
        if (user.getRoleName() != null && PRIVILEGED_ROLES.contains(user.getRoleName().toUpperCase())) {
            return true;
        }

        // Kiểm tra danh sách Authorities phòng trường hợp đa quyền
        if (user.getAuthorities() != null) {
            return user.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(Objects::nonNull)
                    .map(String::toUpperCase)
                    .anyMatch(PRIVILEGED_ROLES::contains);
        }

        return false;
    }

    /**
     * Kiểm tra Cán bộ có thuộc Khoa/Phòng được yêu cầu không (Admin/SuperAdmin luôn được phép)
     */
    public boolean canAccessDepartment(Long departmentId) {
        if (departmentId == null) {
            log.warn("[Security] canAccessDepartment bị từ chối: departmentId là null");
            return false;
        }

        UserPrincipal user = getCurrentPrincipal();
        if (user == null) {
            return false;
        }

        // Admin & SuperAdmin có toàn quyền trên mọi Khoa/Phòng
        if (isPrivilegedUser(user)) {
            return true;
        }

        // Staff chỉ được truy cập nếu đúng departmentId
        if ("ROLE_STAFF".equalsIgnoreCase(user.getRoleName())) {
            boolean hasAccess = Objects.equals(user.getDepartmentId(), departmentId);
            if (!hasAccess) {
                log.warn("[Security] Staff id={} (dept={}) cố truy cập trái phép departmentId={}",
                        user.getId(), user.getDepartmentId(), departmentId);
            }
            return hasAccess;
        }

        return false;
    }

    /**
     * Kiểm tra Sinh viên có phải là người tạo Ticket hoặc Cán bộ phụ trách Khoa của Ticket đó không.
     * Chống triệt để lỗ hổng IDOR (Insecure Direct Object Reference).
     */
    public boolean canAccessTicket(Long ticketId) {
        if (ticketId == null) {
            log.warn("[Security] canAccessTicket bị từ chối: ticketId là null");
            return false;
        }

        UserPrincipal user = getCurrentPrincipal();
        if (user == null) {
            return false;
        }

        // Admin & SuperAdmin luôn có quyền truy cập
        if (isPrivilegedUser(user)) {
            return true;
        }

        // Truy vấn nhẹ qua Projection, tránh load toàn bộ Entity Ticket nặng vào Persistence Context
        Optional<TicketAccessAuthInfo> authInfoOpt = ticketRepository.findAccessAuthInfoById(ticketId);
        if (authInfoOpt.isEmpty()) {
            log.debug("[Security] Không tìm thấy ticketId={} khi thẩm định quyền", ticketId);
            return false;
        }

        TicketAccessAuthInfo authInfo = authInfoOpt.get();

        // 1. Nếu là Sinh viên -> Bắt buộc phải là người tạo Ticket (IDOR Protection)
        if ("ROLE_STUDENT".equalsIgnoreCase(user.getRoleName())) {
            boolean isOwner = authInfo.getCreatorId() != null
                    && Objects.equals(authInfo.getCreatorId(), user.getId());
            if (!isOwner) {
                log.warn("[Security-IDOR] Sinh viên id={} cố tình truy cập trái phép ticketId={}",
                        user.getId(), ticketId);
            }
            return isOwner;
        }

        // 2. Nếu là Staff -> Phải thuộc Khoa/Phòng đang xử lý Ticket đó
        if ("ROLE_STAFF".equalsIgnoreCase(user.getRoleName())) {
            boolean isSameDept = authInfo.getDepartmentId() != null
                    && Objects.equals(authInfo.getDepartmentId(), user.getDepartmentId());
            if (!isSameDept) {
                log.warn("[Security] Cán bộ id={} (dept={}) không có quyền trên ticketId={} (dept={})",
                        user.getId(), user.getDepartmentId(), ticketId, authInfo.getDepartmentId());
            }
            return isSameDept;
        }

        return false;
    }
}
