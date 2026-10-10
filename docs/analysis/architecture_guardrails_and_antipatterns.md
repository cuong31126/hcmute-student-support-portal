# 🛡️ SỔ TAY QUY PHÒNG KIẾN TRÚC & PHÒNG CHỐNG LỖI HỆ THỐNG PHỔ BIẾN
## (ARCHITECTURE GUARDRAILS & ANTI-PATTERNS COMPENDIUM)

> **Mã tài liệu:** `docs/analysis/architecture_guardrails_and_antipatterns.md`  
> **Dự án:** QAUTE Portal - HCMUTE Student Support & Counseling Portal  
> **Áp dụng cho:** Tất cả Nhà phát triển (Dev A & Dev B) và **MỌI AI CODING ASSISTANT** (Antigravity, Gemini, Claude, Cursor, Copilot).  
> **Mục đích tối thượng:** Triệt tiêu hoàn toàn các lỗi kiến trúc kinh điển, rủi ro Race Condition, nghẽn luồng Tomcat, phụ thuộc vòng giữa các module và bẫy rò rỉ CSDL.

---

## 📑 MỤC LỤC BẮT BUỘC TUÂN THỦ

1. [QUY TẮC 1: TRIỆT TIÊU RACE CONDITION (CONCURRENCY LOCKING & ATOMIC UPDATE)](#quy-tắc-1-triệt-tiêu-race-condition)
2. [QUY TẮC 2: PHÒNG VỆ NGHẼN LUỒNG TOMCAT (TOMCAT THREAD STARVATION DEFENSE)](#quy-tắc-2-phòng-vệ-nghẽn-luồng-tomcat)
3. [QUY TẮC 3: XÓA BỎ PHỤ THUỘC VÒNG GIỮA CÁC MODULE (EVENT-DRIVEN ARCHITECTURE)](#quy-tắc-3-xóa-bỏ-phụ-thuộc-vòng-giữa-các-module)
4. [QUY TẮC 4: KỶ LUẬT DTO & TẮT OPEN-SESSION-IN-VIEW (OSIV = FALSE)](#quy-tắc-4-kỷ-luật-dto--tắt-osiv)
5. [QUY TẮC 5: PHÒNG CHỐNG LỖ HỔNG IDOR BẰNG SPEL VÀ PHÂN QUYỀN ĐƠN VỊ](#quy-tắc-5-phòng-chống-lỗ-hổng-idor)
6. [BẢNG TỔNG HỢP ANTI-PATTERNS CẤM TUYỆT ĐỐI](#bảng-tổng-hợp-anti-patterns)

---

<a name="quy-tắc-1-triệt-tiêu-race-condition"></a>
## ⚡ QUY TẮC 1: TRIỆT TIÊU RACE CONDITION (CONCURRENCY LOCKING & ATOMIC UPDATE)

### 1.1. Hiện trạng nguy cơ lỗi (Race Condition):
- **Bối cảnh:** Khi nhiều Cán bộ cùng trực trên hệ thống, có 1 Ticket mới (`OPEN`, `assigned_to = NULL`). Hai cán bộ A và B cùng bấm "Tiếp nhận" (Claim Ticket) tại cùng một tích tắc.
- **Lỗi phổ biến (Anti-Pattern):**
  ```java
  // ❌ SAI NGHIÊM TRỌNG: Read-Modify-Write trên Hibernate
  Ticket ticket = ticketRepository.findById(ticketId).orElseThrow(...);
  if (ticket.getStatus() == TicketStatus.OPEN && ticket.getAssignedTo() == null) {
      ticket.setAssignedTo(currentStaff);
      ticket.setStatus(TicketStatus.IN_PROGRESS);
      ticketRepository.save(ticket); // Cả hai luồng cùng đọc thấy OPEN và cùng ghi đè lẫn nhau!
  }
  ```
  *Hậu quả:* Bị mất dữ liệu cập nhật (`Lost Update`), 2 cán bộ đều nghĩ mình đang xử lý vé, bảng lịch sử `TicketHistory` bị loạn audit log.

### 1.2. Giải pháp bắt buộc cho AI:
- **Nguyên tắc:** Sử dụng **Atomic Update Query** tận dụng khóa cấp dòng độc quyền (Row-Level X-Lock) của InnoDB Engine trên Primary Key.
- **Mẫu code chuẩn mực:**
  ```java
  // 🟢 ĐÚNG: Atomic Query trong TicketRepository
  @Modifying(clearAutomatically = true)
  @Query("UPDATE Ticket t SET t.assignedTo = :staff, t.status = 'IN_PROGRESS' " +
         "WHERE t.id = :ticketId AND t.status = 'OPEN' AND t.assignedTo IS NULL")
  int claimTicketAtomic(@Param("ticketId") Long ticketId, @Param("staff") User staff);
  ```
- **Xử lý tại tầng Service:**
  ```java
  @Transactional
  public void claimTicket(Long ticketId, User currentStaff) {
      int updatedRows = ticketRepository.claimTicketAtomic(ticketId, currentStaff);
      if (updatedRows == 0) {
          // Vé đã bị cán bộ khác nhận trước đó trong mili-giây
          throw new TicketAlreadyClaimedException("Yêu cầu này đã được một cán bộ khác tiếp nhận xử lý!");
      }
      // Ghi nhận Audit Trail
      ticketHistoryRepository.save(TicketHistory.builder()
          .ticket(ticket)
          .action("STAFF_CLAIMED")
          .performedBy(currentStaff)
          .build());
  }
  ```
> **LƯU Ý HIBERNATE CACHE:** Luôn thêm `clearAutomatically = true` trên `@Modifying` để xóa bộ nhớ đệm cấp 1 (Persistence Context), tránh stale data cho các câu SELECT tiếp theo trong cùng transaction.

---

<a name="quy-tắc-2-phòng-vệ-nghẽn-luồng-tomcat"></a>
## 🛑 QUY TẮC 2: PHÒNG VỆ NGHẼN LUỒNG TOMCAT (TOMCAT THREAD STARVATION DEFENSE)

### 2.1. Hiện trạng nguy cơ lỗi (Tomcat Starvation):
- Máy chủ Spring Boot nhúng Tomcat mặc định chỉ có **200 worker threads** (`server.tomcat.threads.max = 200`).
- Các tác vụ I/O chậm:
  - Gọi LLM (Google Gemini API, Dify AI, HuggingFace): mất **1.5s - 3.5s**.
  - Gửi email SMTP (Gmail): mất **1.0s - 2.5s**.
  - Xử lý đọc file PDF lớn, render video: mất **3s - 10s**.
- **Lỗi phổ biến (Anti-Pattern):** Viết Controller đồng bộ chặn luồng (Synchronous Blocking):
  ```java
  // ❌ SAI NGHIÊM TRỌNG: Giữ worker thread Tomcat trong lúc chờ Google AI
  @PostMapping("/chat")
  public ResponseEntity<ApiResponse<RagResponse>> chat(@RequestBody RagRequest req) {
      RagResponse response = geminiApiClient.generateResponse(req.getQuery()); // Chờ 3 giây!
      return ResponseEntity.ok(ApiResponse.success(response));
  }
  ```
  *Hậu quả:* Chỉ cần 100 sinh viên cùng nhấn chat AI $\rightarrow$ Toàn bộ 200 Tomcat worker threads bị kẹt cứng. Người dùng khác truy cập Trang chủ, Đăng nhập hay Nộp Ticket đều bị treo cứng và nhận lỗi `504 Gateway Timeout`.

### 2.2. Giải pháp bắt buộc cho AI:
1. **Cô lập Thread Pool (Bulkhead Pattern):**
   - Không để tác vụ AI hay Mail dùng chung luồng với HTTP request của người dùng.
   - Sử dụng các `Executor` chuyên trách đã cấu hình trong `AsyncConfig.java`:
     - `aiTaskExecutor` (Core: 10, Max: 30, Queue: 200) dành cho LLM.
     - `mailTaskExecutor` (Core: 5, Max: 15, Queue: 2000) dành cho gửi mail.
2. **Controller Bất đồng bộ (Async Non-blocking Servlet):**
   ```java
   // 🟢 ĐÚNG: Trả về CompletableFuture để giải phóng Tomcat worker thread ngay lập tức
   @PostMapping("/api/v1/ai/query")
   public CompletableFuture<ResponseEntity<ApiResponse<RagResponse>>> queryAi(
           @RequestBody @Valid RagRequest req) {
       return CompletableFuture.supplyAsync(() -> {
           return ragChatbotService.processQuery(req);
       }, aiTaskExecutor)
       .thenApply(res -> ResponseEntity.ok(ApiResponse.success(res)))
       .orTimeout(10, TimeUnit.SECONDS); // Timeout bảo vệ chống treo
   }
   ```
3. **Gửi Mail Bất đồng bộ với `@Async`:**
   Mọi phương thức gửi email trong `EmailAsyncService` bắt buộc phải gắn annotation:
   ```java
   @Async("mailTaskExecutor")
   public void sendOtpEmail(String toEmail, String otpCode) { ... }
   ```

---

<a name="quy-tắc-3-xóa-bỏ-phụ-thuộc-vòng-giữa-các-module"></a>
## 🔄 QUY TẮC 3: XÓA BỎ PHỤ THUỘC VÒNG GIỮA CÁC MODULE (EVENT-DRIVEN ARCHITECTURE)

### 3.1. Hiện trạng nguy cơ lỗi (Circular Dependency & God Service):
- **Bối cảnh:** Khi `TicketService` cần gửi email thông báo $\rightarrow$ `@Autowired EmailAsyncService`. Ngược lại, khi `NotificationService` muốn tra cứu thông tin vé $\rightarrow$ `@Autowired TicketService`.
- **Lỗi phổ biến (Anti-Pattern):**
  * Gây lỗi khởi động Spring Boot: `BeanCurrentlyInCreationException` (vòng lặp phụ thuộc).
  * Vi phạm ranh giới Module (Spaghetti Code): Module này biết quá nhiều chi tiết nội bộ của module khác.

### 3.2. Giải pháp bắt buộc cho AI:
- **Áp dụng Spring Domain Events (`ApplicationEventPublisher` & `@EventListener`):**
  Module phát sinh nghiệp vụ CHỈ CẦN phát sự kiện (Publish Event), KHÔNG ĐƯỢC tiêm trực tiếp Service của module khác.
  
- **Mẫu thiết kế chuẩn:**
  1. **Định nghĩa Domain Event:**
     ```java
     public record TicketCreatedEvent(Long ticketId, String studentEmail, String title) {}
     ```
  2. **Tầng Service bắn sự kiện (Publisher):**
     ```java
     @Service
     @RequiredArgsConstructor
     public class TicketServiceImpl implements TicketService {
         private final TicketRepository ticketRepository;
         private final ApplicationEventPublisher eventPublisher; // Chỉ cần inject eventPublisher!

         @Transactional
         public TicketResponseDto createTicket(TicketCreateRequest req, User student) {
             Ticket saved = ticketRepository.save(...);
             // Bắn sự kiện ra bus hệ thống
             eventPublisher.publishEvent(new TicketCreatedEvent(saved.getId(), student.getEmail(), saved.getTitle()));
             return TicketMapper.toDto(saved);
         }
     }
     ```
  3. **Module Notification lắng nghe bất đồng bộ (Subscriber):**
     ```java
     @Component
     @RequiredArgsConstructor
     public class TicketNotificationListener {
         private final EmailAsyncService emailService;

         @Async("mailTaskExecutor")
         @EventListener
         public void handleTicketCreated(TicketCreatedEvent event) {
             emailService.sendTicketConfirmationEmail(event.studentEmail(), event.ticketId(), event.title());
         }
     }
     ```
  *Lợi ích:* `TicketService` hoàn toàn độc lập với `NotificationService`, tốc độ phản hồi cho sinh viên giảm từ 2.5s xuống chỉ còn **15 mili-giây**!

---

<a name="quy-tắc-4-kỷ-luật-dto--tắt-osiv"></a>
## 📦 QUY TẮC 4: KỶ LUẬT DTO & TẮT OPEN-SESSION-IN-VIEW (OSIV = FALSE)

### 4.1. Hiện trạng nguy cơ lỗi:
- Trong `application.yml`, dự án đã thiết lập:
  ```yaml
  spring:
    jpa:
      open-in-view: false # TẮT ĐỂ GIẢI PHÓNG KẾT NỐI DB CHO POOL
  ```
- **Lỗi phổ biến (Anti-Pattern):**
  * Truyền Entity thô ra ngoài Model của Thymeleaf hoặc Controller:
    `model.addAttribute("ticket", ticketEntity);`
  * Trên file HTML Thymeleaf duyệt `th:text="${ticket.department.name}"` hoặc `th:text="${ticket.comments}"`.
  * *Hậu quả nổ lỗi runtime:* `org.hibernate.LazyInitializationException: could not initialize proxy [Department#1] - no Session`!

### 4.2. Giải pháp bắt buộc cho AI:
1. **Kỷ luật DTO 100%:**
   - Tuyệt đối KHÔNG BAO GIỜ truyền Entity thô sang Model hoặc ApiResponse.
   - Mọi dữ liệu trả về bắt buộc phải được chuyển đổi sang DTO/Record ngay bên trong phạm vi `@Transactional` của tầng Service.
2. **Khắc phục triệt để N+1 Query bằng `@EntityGraph` hoặc `JOIN FETCH`:**
   - Trong JPA Repository, khi cần lấy dữ liệu quan hệ Lazy, BẮT BUỘC chỉ định EntityGraph:
   ```java
   // 🟢 ĐÚNG: Nạp sẵn department và creator chỉ trong 1 câu SQL duy nhất
   @EntityGraph(attributePaths = {"department", "creator", "assignedTo"})
   @Query("SELECT t FROM Ticket t WHERE t.id = :id")
   Optional<Ticket> findByIdWithDetails(@Param("id") Long id);
   ```

---

<a name="quy-tắc-5-phòng-chống-lỗ-hổng-idor"></a>
## 🔒 QUY TẮC 5: PHÒNG CHỐNG LỖ HỔNG IDOR BẰNG SPEL VÀ PHÂN QUYỀN ĐƠN VỊ

### 5.1. Hiện trạng nguy cơ lỗi (IDOR - Insecure Direct Object References):
- Hệ thống có 5 Khoa/Phòng: Phòng Đào tạo, Phòng Tuyển sinh, Khoa CNTT, v.v.
- **Lỗi phổ biến (Anti-Pattern):** Cán bộ chỉ cần đăng nhập, sau đó đổi tham số trên URL: `/staff/tickets/101` $\rightarrow$ `/staff/tickets/102`.
- Nếu Backend chỉ kiểm tra `@PreAuthorize("hasRole('STAFF')")` mà không kiểm tra đơn vị:
  * Cán bộ Khoa CNTT sẽ đọc và chỉnh sửa được toàn bộ phiếu nhạy cảm của Phòng Đào tạo!

### 5.2. Giải pháp bắt buộc cho AI:
- **Tích hợp SpEL bảo vệ trực tiếp trên Service/Controller:**
  ```java
  // 🟢 ĐÚNG: Dùng SpEL gọi trực tiếp DepartmentAccessEvaluator kiểm tra ngữ cảnh
  @PreAuthorize("@deptSecurity.canAccessTicket(#ticketId)")
  public TicketDetailDto getTicketDetail(Long ticketId) {
      ...
  }
  ```
- **Triển khai trong `DepartmentAccessEvaluator.java`:**
  - Kiểm tra xem người dùng hiện tại có phải `ROLE_ADMIN` không (Admin xem được tất cả).
  - Nếu là `ROLE_STAFF`: Bắt buộc kiểm tra `user.getDepartment().getId().equals(ticket.getDepartment().getId())`.
  - Nếu không trùng khớp: Ném ngay `AccessDeniedBusinessException("Bạn không có quyền can thiệp vào dữ liệu của Khoa/Phòng khác!")`.

---

<a name="bảng-tổng-hợp-anti-patterns"></a>
## 🚫 BẢNG TỔNG HỢP ANTI-PATTERNS CẤM TUYỆT ĐỐI

| Hành vi vi phạm (Anti-Pattern) | Hậu quả kỹ thuật | Quy tắc bắt buộc sửa đổi |
| :--- | :--- | :--- |
| **Dùng `save()` thông thường để nhận vé** | Race condition, Lost Update | Bắt buộc dùng **Atomic UPDATE Query** (`claimTicketAtomic`) |
| **Controller gọi trực tiếp LLM/API ngoài chặn luồng** | Tomcat Thread Starvation (HTTP 504) | Bắt buộc dùng **`CompletableFuture` + `aiTaskExecutor`** |
| **Gửi email đồng bộ trong luồng Request** | Lag giao diện 2-3s khi người dùng nộp form | Bắt buộc dùng **`@Async("mailTaskExecutor")`** |
| **Inject chéo Service giữa các module** | Circular dependency, nghẽn khởi động | Bắt buộc dùng **`ApplicationEventPublisher` (Domain Events)** |
| **Truyền Entity thô sang Thymeleaf khi OSIV=false** | Ném `LazyInitializationException` | Bắt buộc ánh xạ sang **DTO** bên trong Service |
| **Dùng `FetchType.EAGER` trên Entity** | N+1 Query, tràn bộ nhớ RAM | 100% đặt **`FetchType.LAZY`** + dùng **`@EntityGraph`** |
| **Bảo mật chỉ kiểm tra `hasRole('STAFF')` trên Ticket** | Lỗ hổng IDOR chéo Khoa/Phòng | Bắt buộc dùng **`@PreAuthorize("@deptSecurity.canAccessTicket(#id)")`** |
| **Tự ý thêm thư viện ngoài vào `pom.xml`** | Xung đột dependency, lỗi build Maven | Bắt buộc sử dụng các thư viện đã được phê duyệt sẵn |

---
*Tài liệu này là chuẩn mực bất biến. Mọi dòng code được sinh ra phải vượt qua toàn bộ các tiêu chí phòng vệ ở trên.*
