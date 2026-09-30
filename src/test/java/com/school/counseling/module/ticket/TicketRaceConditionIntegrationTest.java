package com.school.counseling.module.ticket;

import com.school.counseling.common.exception.TicketAlreadyClaimedException;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration Test: Kiểm thử Race Condition khi nhiều Cán bộ cùng nhận Ticket.
 *
 * Kịch bản mô phỏng: 10 Cán bộ của cùng một Khoa cùng bấm nút "Tiếp nhận" (Claim)
 * trên cùng 1 Ticket OPEN tại cùng 1 tích tắc sử dụng CountDownLatch.
 *
 * Kết quả đúng: Chỉ đúng 1 Cán bộ nhận thành công, 9 cán bộ còn lại nhận
 * TicketAlreadyClaimedException mà không làm vỡ dữ liệu.
 */
@SpringBootTest
@ActiveProfiles("test")
class TicketRaceConditionIntegrationTest {

    @Autowired
    private TicketService ticketService;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private RoleRepository roleRepository;

    private Long testTicketId;
    private List<Long> staffIds;

    @BeforeEach
    void setUp() {
        Department dept = departmentRepository.findAll().stream().findFirst().orElseGet(() ->
                departmentRepository.save(Department.builder()
                        .name("Khoa Test Concurrency")
                        .code("KHOA_CONCURRENCY")
                        .build())
        );

        Role staffRole = roleRepository.findByName("ROLE_STAFF").orElseGet(() ->
                roleRepository.save(Role.builder()
                        .name("ROLE_STAFF")
                        .description("Cán bộ")
                        .build())
        );

        staffIds = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String uname = "race_staff_" + i;
            User u = userRepository.findByUsername(uname).orElse(null);
            if (u == null) {
                u = userRepository.save(User.builder()
                        .username(uname)
                        .passwordHash("password")
                        .fullName("Cán bộ Test " + i)
                        .email(uname + "@hcmute.edu.vn")
                        .role(staffRole)
                        .department(dept)
                        .status("ACTIVE")
                        .build());
            } else {
                u.setDepartment(dept);
                u = userRepository.save(u);
            }
            staffIds.add(u.getId());
        }

        Ticket ticket = ticketRepository.save(Ticket.builder()
                .ticketCode("TK-RACE-" + System.nanoTime())
                .title("Ticket Test Race Condition")
                .description("Kiểm thử nhiều cán bộ cùng bấm nhận vé")
                .department(dept)
                .status("OPEN")
                .dueDate(LocalDateTime.now().plusDays(3))
                .build());
        testTicketId = ticket.getId();
    }

    @Test
    @DisplayName("Race Condition: 10 cán bộ cùng claim ticket → chỉ 1 người thành công, 9 người nhận exception")
    void should_only_one_staff_claim_ticket_when_concurrent_requests() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch startGate  = new CountDownLatch(1);  // Cổng xuất phát đồng loạt
        CountDownLatch doneLatch   = new CountDownLatch(threadCount);
        ExecutorService executor   = Executors.newFixedThreadPool(threadCount);

        AtomicInteger successCount    = new AtomicInteger(0);  // Đếm số lần claim thành công
        AtomicInteger conflictCount   = new AtomicInteger(0);  // Đếm số lần bị Race Condition
        AtomicInteger unexpectedErrors = new AtomicInteger(0); // Lỗi không mong muốn

        for (int i = 0; i < threadCount; i++) {
            final Long staffId = staffIds.get(i);
            executor.submit(() -> {
                try {
                    startGate.await(); // Tất cả thread chờ cùng lệnh xuất phát
                    ticketService.claimTicket(testTicketId, staffId);
                    successCount.incrementAndGet();
                } catch (TicketAlreadyClaimedException e) {
                    conflictCount.incrementAndGet(); // Đây là hành vi ĐÚNG KỲ VỌNG
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    unexpectedErrors.incrementAndGet();
                    System.err.println("Lỗi không mong đợi: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown(); // ← Mở cổng: TẤT CẢ 10 thread bắt đầu đồng thời
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // ── ASSERTIONS ──
        assertThat(successCount.get())
                .as("Chỉ đúng 1 cán bộ được phép nhận Ticket thành công")
                .isEqualTo(1);

        assertThat(conflictCount.get())
                .as("9 cán bộ còn lại phải nhận TicketAlreadyClaimedException")
                .isEqualTo(9);

        assertThat(unexpectedErrors.get())
                .as("Không được có lỗi ngoài mong đợi")
                .isZero();

        // ── Verify trạng thái trong DB ──
        ticketRepository.findByIdWithRelations(testTicketId).ifPresent(ticket -> {
            assertThat(ticket.getStatus())
                    .as("Trạng thái Ticket trong DB phải là IN_PROGRESS sau khi claim")
                    .isEqualTo("IN_PROGRESS");
            assertThat(ticket.getAssignedTo())
                    .as("Ticket phải được gán cho đúng 1 Cán bộ")
                    .isNotNull();
        });
    }
}
