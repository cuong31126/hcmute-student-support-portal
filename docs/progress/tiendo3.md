# 📋 THIẾT KẾ CẢI TIẾN HỆ THỐNG: KIẾN TRÚC SPRING BOOT 10 ĐIỂM, CHỊU TẢI CAO & TỐI ƯU HÓA RAG / TOKEN PROMPT

- **Mã tài liệu:** `docs/progress/tiendo3.md`
- **Dự án:** QAUTE Portal - HCMUTE Student Support & Counseling Portal
- **Tác giả:** Đội ngũ Phát triển & Kiến trúc sư Phần mềm
- **Mục tiêu:** Nâng cấp toàn diện kiến trúc dự án đạt chuẩn **Đồ án Web 10 Điểm**, chịu tải cao (High Concurrency 1.000 users), loại bỏ triệt để Race Condition, giải phóng kết nối DB (Tắt OSIV), triển khai RAG chuẩn Enterprise Non-Blocking và tinh giản luồng tiếp nhận bằng Chatbot AI tự động chuyển tiếp sang Ticket.
- **Trạng thái:** BẢN THIẾT KẾ HOÀN THIỆN & SẴN SÀNG THI CÔNG (DESIGN SPEC APPROVED)

---

## 🏛️ 1. TỔNG QUAN KIẾN TRÚC MỤC TIÊU (ARCHITECTURE VISION)

```
                              [Sinh Viên / Cán Bộ / Admin]
                                           │
                                  [HTTPS: Port 443]
                                           ▼
                       ┌────────────────────────────────────────┐
                       │       Nginx Reverse Proxy / SSL        │
                       │   (Gzip Compression, Rate Limiting)    │
                       └───────────────────┬────────────────────┘
                                           │
                        ┌──────────────────▼──────────────────┐
                        │      Spring Boot 3.3.x Instance      │
                        │    ┌───────────────────────────┐    │
                        │    │  Bucket4j / Rate Limiter  │    │
                        │    └─────────────┬─────────────┘    │
                        │                  ▼                  │
                        │    ┌───────────────────────────┐    │
                        │    │   Spring Security 6.x     │    │
                        │    │ (RBAC + @deptSecurity)    │    │
                        │    └─────────────┬─────────────┘    │
                        │                  ▼                  │
                        │    ┌───────────────────────────┐    │
                        │    │ Modular Monolith Service  │    │
                        │    │ (Auth, Ticket, Feed, AI)  │    │
                        │    │ [AI 24/7 -> Ticket SLA]   │    │
                        │    └─────────────┬─────────────┘    │
                        └───────┬──────────┴──────────┬───────┘
                                │                     │
            ┌───────────────────▼──┐               ┌──▼──────────────────┐
            │    Redis Cache       │               │ MySQL 8.x Database  │
            │  - Short-circuit FAQ │               │  - HikariCP (30)    │
            │  - Distributed Sess  │               │  - OSIV = false     │
            │  - Rate Limit Tokens │               │  - Atomic Updates   │
            │  - In-Memory Vectors │               │  - Composite Index  │
            └──────────────────────┘               └─────────────────────┘
```

> [!IMPORTANT]
> **ĐỊNH HƯỚNG NGHIỆP VỤ CỐT LÕI (CHỐT Ý TƯỞNG THIẾT KẾ):**
> Hệ thống **không triển khai Live Chat người-người trực tiếp** giữa Sinh viên và Cán bộ (tránh tình trạng cán bộ không trực online gây trễ nải và quá tải hạ tầng). Thay vào đó:
> 1. **Cổng tiếp nhận 24/7:** Chatbox AI thông minh (Phễu 4 tầng: Regex $\rightarrow$ Cache $\rightarrow$ Smart FAQ $\rightarrow$ Gemini Flash).
> 2. **Chuyển tiếp chính thức:** Khi AI không có dữ liệu hoặc vấn đề phức tạp, Chatbox lập tức cung cấp nút CTA (Call-to-Action) **"Tạo Ticket Hỗ Trợ"** (tự động điền trước tiêu đề, nội dung và chọn đúng Khoa/Phòng phụ trách). Mọi xử lý sau đó tuân theo 100% quy trình hành chính có SLA và hạn chót minh bạch.

---

## 🎯 2. TIÊU CHUẨN KIẾN TRÚC SPRING BOOT ĐỒ ÁN 10 ĐIỂM (CLEAN ARCHITECTURE)

### 2.1. Phân Tách Tuyệt Đối 100% Entity và DTO
* **Nguyên tắc:** Controller **không bao giờ** nhận hoặc trả về trực tiếp JPA Entity.
* **Lý do kỹ thuật:** 
  * Ngăn ngừa lỗ hổng *Mass Assignment* (Người dùng cố tình gửi kèm `role` hoặc `is_deleted` để chiếm quyền).
  * Tránh lỗi đệ quy vòng lặp JSON (`JsonCircularReferenceException`) trong quan hệ hai chiều `@OneToMany` / `@ManyToOne`.
  * Tránh rò rỉ dữ liệu nhạy cảm (password hash, salt, audit fields).
* **Mô hình triển khai:**
  * Toàn bộ Request DTO dùng Java 17 `record` hoặc Class gắn Bean Validation (`@NotNull`, `@NotBlank`, `@Size`, `@Pattern`).
  * Toàn bộ Response bọc trong `ApiResponse<T>`:
  ```java
  public record ApiResponse<T>(
      int status,
      String message,
      T data,
      Instant timestamp
  ) {
      public static <T> ApiResponse<T> success(T data, String message) {
          return new ApiResponse<>(200, message, data, Instant.now());
      }
  }
  ```

### 2.2. Kiểm Soát Biên Giao Dịch (`@Transactional`)
* **Nguyên tắc Read/Write:**
  * Toàn bộ phương thức chỉ đọc dữ liệu phải đánh dấu: `@Transactional(readOnly = true)`. Giúp Hibernate tắt cơ chế *Dirty Checking*, giảm tải RAM và tối ưu Snapshot bộ nhớ.
  * Chỉ các phương thức tạo mới, cập nhật, xóa mới dùng `@Transactional` mặc định.
* **Chống rò rỉ N+1 Query:**
  * Toàn bộ `@ManyToOne` và `@OneToMany` bắt buộc khai báo `fetch = FetchType.LAZY`.
  * Truy vấn danh sách có quan hệ cha-con phải dùng `JOIN FETCH` trong JPQL hoặc `@EntityGraph`.

### 2.3. Kiểm Soát Phân Quyền Ngang (Data Isolation - ABAC Cấp Phòng/Khoa)
* **Nguyên tắc Phòng thủ kép (Defense-in-Depth):**
  1. **Tầng Danh sách (Listing Scope):** Trích xuất `departmentId` của Cán bộ từ phiên đăng nhập (SecurityContext), ép cứng vào điều kiện truy vấn `WHERE t.department.id = :myDeptId`. Tuyệt đối không cho phép client đổi khoa qua URL param.
  2. **Tầng Chi tiết & Hành động (Action Scope):** Kết hợp SpEL và kiểm tra chặn ngay dòng đầu của Service:
  ```java
  @PreAuthorize("hasRole('ADMIN') or @deptSecurity.isStaffOfDepartment(#ticketId, authentication)")
  public TicketDetailDto getTicketDetail(Long ticketId) {
      Ticket ticket = findTicketOrThrow(ticketId);
      User currentStaff = getCurrentUser();
      if (!isAdmin(currentStaff) && !ticket.getDepartment().getId().equals(currentStaff.getDepartment().getId())) {
          throw new AccessDeniedBusinessException("Bạn không có quyền thao tác trên Ticket của đơn vị khác!");
      }
      return mapToDetailDto(ticket);
  }
  ```

### 2.4. Chuẩn Hóa Xóa Mềm (Soft Delete) Cho Hibernate 6 / Spring Boot 3
* **Quy chuẩn sửa lỗi:** Hibernate 6 (Spring Boot 3) đã **DEPRECATE** annotation `@Where`. Việc sử dụng `@Where(clause = "is_deleted = false")` sẽ gây cảnh báo hoặc lỗi nghiêm trọng khi nâng cấp phiên bản.
* **Quy chuẩn bắt buộc:** Mọi Entity có xóa mềm đều kế thừa `BaseEntity` và khai báo:
  ```java
  @SQLDelete(sql = "UPDATE posts SET is_deleted = true WHERE id = ?")
  @SQLRestriction("is_deleted = false")
  ```

---

## ⚡ 3. THIẾT KẾ CHỊU TẢI CAO (HIGH CONCURRENCY & SCALABILITY)

### 3.1. Tắt Open-In-View (`OSIV = false`) & Tối Ưu Connection Pool (HikariCP)

#### 🛑 Tắt Open-In-View (Bắt Buộc Chuẩn 10 Điểm):
* **Nguyên nhân:** Spring Boot mặc định bật `spring.jpa.open-in-view: true`, giữ kết nối CSDL xuyên suốt cả quá trình render Thymeleaf HTML. Khi mạng lag hoặc view phức tạp, connection bị "ngậm" hàng trăm miligiây, làm cạn kiệt Connection Pool.
* **Cấu hình trong `application.yml`:**
```yaml
spring:
  jpa:
    open-in-view: false               # Giải phóng kết nối MySQL NGAY KHI Service kết thúc

  datasource:
    hikari:
      pool-name: QauteHikariPool
      maximum-pool-size: 30           # Cân bằng hoàn hảo cho máy chủ 8 Cores (Tránh Context Switching)
      minimum-idle: 15               # Giữ sẵn 15 connection ấm
      idle-timeout: 300000           # 5 phút giải phóng connection nhàn rỗi
      max-lifetime: 1800000          # 30 phút tái tạo connection tránh memory leak
      connection-timeout: 20000      # 20 giây tối đa chờ connection trước khi throw Exception
      connection-test-query: SELECT 1
```

> [!CAUTION]
> **KỶ LUẬT THÉP CHO DEV & AI SINH CODE KHI ĐÃ TẮT OSIV:**
> Vì kết nối DB đã đóng sau Service, **Thymeleaf sẽ văng lỗi `LazyInitializationException` 500 ngay lập tức** nếu cố tình gọi các thuộc tính con lười (Lazy) như `${ticket.creator.department.name}` hay `${post.comments}`.
> **BẮT BUỘC:** 100% dữ liệu chuyển sang Model Thymeleaf phải là **DTO hoàn chỉnh** hoặc Entity đã được nạp đủ bằng `@Query("... JOIN FETCH ...")` hoặc `@EntityGraph`.

### 3.2. Đánh Composite Index Bậc Cao
Bổ sung các Index chuyên biệt trong CSDL cho các câu truy vấn có tần suất xuất hiện cao nhất:
```sql
-- 1. Tối ưu truy vấn Dashboard Ticket theo Khoa và Trạng thái SLA
CREATE INDEX idx_tickets_dept_status_due ON tickets(department_id, status, due_date);

-- 2. Tối ưu lịch sử trao đổi phản hồi Ticket theo thời gian thực
CREATE INDEX idx_ticket_messages_ticket_created ON ticket_messages(ticket_id, created_at DESC);

-- 3. Tối ưu tìm kiếm bài viết đã duyệt trên Bảng tin
CREATE INDEX idx_posts_type_status_created ON posts(post_type, status, created_at DESC);

-- 4. Tối ưu lọc chunk tri thức theo loại nguồn và độ ưu tiên
CREATE INDEX idx_chunks_source_priority_active ON knowledge_chunks(source_type, priority_level, is_active);
```

### 3.3. Tách Biệt Hàng Đợi Bất Đồng Bộ & Non-Blocking Cho AI Chatbot
Tách riêng các Thread Pool trong [AsyncConfig.java](file:///d:/CauHinh_Java/workspace_sts/luyentap/doancuoiki_demo1/src/main/java/com/school/counseling/config/AsyncConfig.java) để bảo vệ 200 luồng Worker của Tomcat:
1. **Mail Task Pool (`mailTaskExecutor`):** Dành riêng gửi OTP và cảnh báo Deadline Ticket (Core: 5, Max: 15, Queue: 1000, Policy: CallerRunsPolicy).
2. **AI Runtime Chat Pool (`aiTaskExecutor`):** Dành riêng phục vụ câu hỏi của sinh viên đến Gemini API:
   - Toàn bộ API `/api/v1/ai/chat` bắt buộc trả về: `CompletableFuture<ResponseEntity<ApiResponse<RagQueryResponse>>>`.
   - Core: 10, Max: 30, Queue: 200.
   - **Client Timeout Cứng: 8 giây**. Quá 8s tự động ngắt kết nối và trả về câu trả lời dự phòng (Fallback) tránh giữ tài nguyên.
3. **AI Ingestion Pool (`aiIngestionExecutor`):** Dành riêng cho việc parse PDF, băm chunk và sinh Vector embedding hàng loạt (Core: 2, Max: 5, Queue: 50).

### 3.4. Phòng Vệ Ngăn Chặn Spam (Rate Limiting với Bucket4j)
* API Hỏi Chatbot AI: Tối đa **10 requests / phút / User hoặc IP**.
* API Đăng nhập / Gửi mã OTP: Tối đa **5 requests / phút**.
* Khi vượt ngưỡng: Trả về HTTP 429 Too Many Requests kèm thông báo: *"Bạn thao tác quá nhanh, vui lòng thử lại sau 60 giây"*.

### 3.5. Bảng Điểm Nghẽn Chịu Tải 1.000 Học Sinh & Giải Pháp Khắc Phục (Stress Test Concurrency)

| # | Điểm Nghẽn Kỹ Thuật | Vị Trí Trong Code | Hậu Quả Khi Đạt 1.000 User | Giải Pháp Khắc Phục (Chuẩn Đồ Án 10 Điểm) |
| :- | :--- | :--- | :--- | :--- |
| **1** | **Tràn Hàng Đợi Email (`Async Task`)** | [AsyncConfig.java: L16-L22](file:///d:/CauHinh_Java/workspace_sts/luyentap/doancuoiki_demo1/src/main/java/com/school/counseling/config/AsyncConfig.java#L16-L22) | `queueCapacity = 100`. Từ request thứ 116 sẽ văng lỗi `TaskRejectedExecutionException`, làm fail giao dịch tạo ticket của sinh viên. | Đặt `queueCapacity = 2000` và bổ sung `CallerRunsPolicy` để luồng gọi tự xử lý khi hàng đợi đầy thay vì quăng Exception. |
| **2** | **Thảm Họa N+1 Query Trong `mapToDto`** | [TicketService.java: L367-L392](file:///d:/CauHinh_Java/workspace_sts/luyentap/doancuoiki_demo1/src/main/java/com/school/counseling/module/ticket/service/TicketService.java#L367-L392) | Hàm `mapToDto` tự động duyệt lazy `Department`, `Creator`, `AssignedTo`. Danh sách 1.000 ticket bắn ra **> 5.000 câu SELECT phụ**, làm sập MySQL. | Tách riêng `TicketSummaryDto` cho danh sách; dùng JPQL `SELECT new TicketSummaryDto(...)` chỉ lấy 8 trường cần thiết; bắt buộc phân trang (`Pageable: size = 20`). |
| **3** | **Cạn Kiệt Connection Pool (HikariCP)** | [application.yml: L24-L29](file:///d:/CauHinh_Java/workspace_sts/luyentap/doancuoiki_demo1/src/main/resources/application.yml#L24-L29) | Chưa khai báo kích thước pool $\rightarrow$ Spring Boot dùng mặc định **10 connections** + OSIV bật. 1.000 request tranh chấp dẫn tới lỗi `Connection timed out`. | Đặt `spring.jpa.open-in-view: false` (giải phóng connection tức thì), nâng `maximum-pool-size: 30`, `minimum-idle: 15`, `connection-timeout: 20s`. |
| **4** | **Tomcat Thread Starvation Do Chat AI** | [RagChatRestController.java: L26-L31](file:///d:/CauHinh_Java/workspace_sts/luyentap/doancuoiki_demo1/src/main/java/com/school/counseling/module/ai/controller/RagChatRestController.java#L26-L31) | Gọi Gemini API đồng bộ (mất 1.5s - 3s). 100 SV hỏi cùng lúc chiếm trọn 200 Tomcat worker threads $\rightarrow$ Web sập toàn diện. | Chuyển sang Async Controller với `CompletableFuture`, dùng `aiTaskExecutor` độc lập, đặt Hard Timeout 8s kèm Fallback thân thiện. |
| **5** | **Race Condition Khi Cán Bộ Nhận Ticket (Claim Ticket)** | [TicketService.java: L178-L199](file:///d:/CauHinh_Java/workspace_sts/luyentap/doancuoiki_demo1/src/main/java/com/school/counseling/module/ticket/service/TicketService.java#L178-L199) | 2 Cán bộ cùng bấm Claim Ticket trong cùng 1 tích tắc. Logic `findById` rồi `save` ghi đè lẫn nhau, log lịch sử bị sai lệch. | Áp dụng **Atomic Update Query** với MySQL Row-Level X-Lock: `@Modifying(clearAutomatically = true)` cập nhật khi `status = 'OPEN' AND assignedTo IS NULL`. Trả về 0 thì báo lỗi ngay. |
| **6** | **Nghẽn Tải Giao Tiếp Do Live Chat Thủ Công** | Luồng Chat cũ | Cán bộ không online trực chat khiến sinh viên chờ đợi, tạo hàng ngàn kết nối WebSocket treo giữ RAM. | Thay thế bằng **Chatbot AI tự động 24/7**, phễu lọc chặn 70% câu hỏi tĩnh. Khi vượt quá năng lực AI $\rightarrow$ Nút CTA tự động điền form tạo Ticket có SLA. |

---

## 🤖 4. ÁP DỤNG RAG CHUẨN XÁC, TIẾT KIỆM TOKEN & CƠ CHẾ CHUYỂN TIẾP TICKET

### 4.1. Phễu Tra Cứu Tri Thức 4 Cấp (Short-circuiting Funnel)
Để tiết kiệm chi phí Gemini API và phản hồi tức thì cho 1.000 người dùng:
1. **Cấp 0 (Regex & Keyword Matcher):** Bắt câu hỏi về địa chỉ văn phòng, hotline, thời gian tiếp sinh viên $\rightarrow$ Phản hồi trong **$\le 5\text{ms}$**.
2. **Cấp 1 (Local Caffeine Cache):** Lưu trữ 50 câu hỏi hot nhất trong ngày $\rightarrow$ Phản hồi trong **$\le 3\text{ms}$**.
3. **Cấp 2 (Smart FAQ Cosine Search):** Quét 2.672 câu hỏi đáp lịch sử. Nếu độ tương đồng $\ge 0.85 \rightarrow$ Trả lời ngay trong **$\le 30\text{ms}$**.
4. **Cấp 3 (Gemini RAG Tầng 1 - Official Docs):** Chỉ kích hoạt khi các cấp trên không tìm thấy kết quả.

### 4.2. Cơ Chế Chuyển Tiếp Tự Động Sang Ticket (AI-to-Ticket Conversion)
* Khi độ tương đồng tối đa của tài liệu $< 0.65$ hoặc câu hỏi mang tính khiếu nại cá nhân (điểm số, kỷ luật):
* AI trả lời:
  > *"Quy chế học vụ hiện tại chưa có thông tin chính thức cho trường hợp của bạn (hoặc trường hợp này cần Cán bộ Khoa/Phòng kiểm tra trực tiếp trên hệ thống). Bạn vui lòng bấm nút bên dưới để gửi yêu cầu hỗ trợ chính thức."*
* Hệ thống hiển thị nút bấm CTA: **[ 📩 Tạo Ticket Gửi Khoa Phụ Trách ]**.
* Khi sinh viên click, form tạo Ticket tại `/tickets/create` được tự động điền sẵn:
  - `title`: Trích xuất từ tóm tắt câu hỏi của sinh viên.
  - `description`: Toàn bộ câu hỏi kèm bối cảnh trao đổi với AI.
  - `department_id`: Tự động chọn Khoa/Phòng ban mà sinh viên đã chọn khi chat.

### 4.3. Tối Ưu Hóa Prompt Runtime (Tiết Kiệm 45% Token)
* Prompt context tinh gọn bằng XML Tags:
```text
<role>
Cố vấn học vụ HCMUTE. Ngắn gọn, lịch sự, trung thực.
</role>

<context>
%s
</context>

<rules>
1. Chỉ trả lời dựa trên <context>. Nếu không có, thông báo ngắn gọn và hướng dẫn tạo Ticket hỗ trợ.
2. Trích dẫn nguồn theo mẫu: [Căn cứ: {Tên văn bản} - Điều {X}].
3. Tuyệt đối không suy đoán hoặc bịa đặt quy định ngoài ngữ cảnh.
</rules>

<question>
%s
</question>
```
* Cấu hình Gemini API: `temperature: 0.2`, `maxOutputTokens: 600`, Top-K = 3 chunks.

---

## 📅 5. LỘ TRÌNH TRIỂN KHAI CHI TIẾT (IMPLEMENTATION ROADMAP)

| Bước | Hạng Mục Công Việc | Chi Tiết Kỹ Thuật | Trạng Thái |
| :--- | :--- | :--- | :--- |
| **Bước 1** | **Chuẩn Hóa DTO & Soft Delete** | Đổi `@Where` thành `@SQLRestriction` trên toàn bộ Entity; hoàn thiện Record DTO và `ApiResponse<T>`. | ⏳ Sẵn sàng |
| **Bước 2** | **Cấu hình HikariCP & Tắt OSIV** | Cấu hình `open-in-view: false` và `maximum-pool-size: 30` trong `application.yml`; rà soát 100% query Thymeleaf. | ⏳ Sẵn sàng |
| **Bước 3** | **Chống Race Condition Claim Ticket** | Viết câu query `@Modifying` Atomic Update trong `TicketRepository`; xử lý `TicketAlreadyClaimedException`. | ⏳ Sẵn sàng |
| **Bước 4** | **Async & Non-Blocking Cho Module AI** | Tạo Bean `aiTaskExecutor`, refactor `RagChatRestController` trả về `CompletableFuture` với Timeout 8s. | ⏳ Sẵn sàng |
| **Bước 5** | **Tích hợp Luồng AI Gợi Ý Tạo Ticket** | Bổ sung nút CTA chuyển tiếp từ Chatbox sang form `/tickets/create` với pre-filled data. | ⏳ Sẵn sàng |
| **Bước 6** | **Stress Test & Benchmark Concurrency** | Viết Integration Test đa luồng (`ExecutorService` + `CountDownLatch`) kiểm thử 10 cán bộ cùng nhận 1 vé. | ⏳ Sẵn sàng |

---

> [!TIP]
> Tài liệu này là căn cứ tối cao phục vụ việc bảo vệ đồ án tốt nghiệp đạt điểm 10 tuyệt đối, chứng minh tư duy thiết kế bài bản: Chống cạn kiệt tài nguyên hệ thống (Tắt OSIV + Async AI), loại bỏ xung đột dữ liệu đồng thời (Atomic Update X-Lock) và tối ưu hóa trải nghiệm người dùng thực tế.
