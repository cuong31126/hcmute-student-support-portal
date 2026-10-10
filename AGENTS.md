# 🤖 AI AGENT CODING & GIT WORKFLOW RULES (QAUTE PORTAL)

> **MỤC ĐÍCH:** Mọi AI Coding Assistant (Antigravity, Gemini, Claude, Cursor, Copilot) khi làm việc trong repository này BẮT BUỘC phải đọc và tự động tuân thủ các quy tắc dưới đây.

---

## 🚀 1. QUY ĐỊNH QUẢN LÝ GIT: KHÔNG TỰ ĐỘNG COMMIT & PUSH (MANUAL GIT WORKFLOW)

- **TUYỆT ĐỐI KHÔNG TỰ Ý COMMIT & PUSH:** AI KHÔNG ĐƯỢC PHÉP tự động chạy `git commit` hoặc `git push` lên GitHub sau khi code xong.
- **Người dùng toàn quyền quản lý Git:** Việc commit, tạo branch, và push lên repository sẽ do người dùng tự thực hiện thủ công hoặc chỉ thực hiện khi người dùng yêu cầu rõ ràng bằng lệnh trong prompt.
- **Trách nhiệm của AI:** 
  1. Tập trung tối ưu mã nguồn, giao diện, và cấu trúc hệ thống.
  2. Xác minh tính đúng đắn sau khi chỉnh sửa (ví dụ: `mvn test-compile` thành công).
  3. Báo cáo rõ ràng các file đã thay đổi để người dùng dễ dàng theo dõi và chủ động commit.

---

## 🏛️ 2. QUY CHUẨN KIẾN TRÚC & CODING RULES

> [!IMPORTANT]
> **TÀI LIỆU QUY CHUẨN KIẾN TRÚC BẮT BUỘC:**
> Mọi AI Coding Assistant BẮT BUỘC phải đọc và tuân thủ nghiêm ngặt tài liệu:
> 👉 `docs/analysis/architecture_guardrails_and_antipatterns.md`

1. **Hierarchy of Truth:** Luôn ưu tiên theo thứ tự:
   `docs/analysis/architecture_guardrails_and_antipatterns.md` > `docs/requirements/brief.md` > `docs/team/engineering-rules.md` > task files.
2. **Chống Race Condition & Atomic Update:** BẮT BUỘC dùng Atomic Update Query `@Modifying(clearAutomatically = true)` khi Cán bộ nhận vé (Claim Ticket) hoặc thay đổi trạng thái đồng thời. TUYỆT ĐỐI KHÔNG dùng Hibernate `findById` rồi `save` truyền thống gây Lost Update.
3. **Phòng Vệ Nghẽn Tomcat (Non-blocking AI & Async Email):** TUYỆT ĐỐI KHÔNG gọi LLM hoặc gửi Mail trong luồng Controller đồng bộ. Bắt buộc dùng `CompletableFuture` với `aiTaskExecutor` và `@Async("mailTaskExecutor")` để giải phóng Tomcat worker threads ngay lập tức.
4. **Chống Phụ Thuộc Vòng (Circular Dependencies):** Giao tiếp giữa các module (như Ticket $\rightarrow$ Notification/Email) BẮT BUỘC qua **Spring Domain Events** (`ApplicationEventPublisher` + `@EventListener`). CẤM inject Service chéo giữa các module.
5. **Kỷ luật DTO (OSIV = false):** Do cấu hình `spring.jpa.open-in-view: false`, 100% dữ liệu truyền sang View Thymeleaf hoặc REST API bắt buộc phải là DTO/Record được ánh xạ trong Service, kèm `@EntityGraph` trong Repository để triệt tiêu N+1 Query và `LazyInitializationException`.
6. **Bảo mật SpEL chống IDOR:** Mọi thao tác trên Ticket, Đơn vị bắt buộc kiểm tra `@PreAuthorize("@deptSecurity.canAccessTicket(#ticketId)")`, nghiêm cấm cho phép xem/sửa chéo dữ liệu Khoa/Phòng.
7. **Hibernate Collection Safety:** Tuyệt đối không gọi `entity.setList(newList)` trên quan hệ `@OneToMany(orphanRemoval = true)` của Entity đang được quản lý (managed).
8. **Giao diện chuẩn Học đường / Doanh nghiệp:** BẮT BUỘC tuân thủ tài liệu `.agents/skills/academic-ui-ux/SKILL.md`. Tối giản, trang nhã, không màu mè sặc sỡ, ưu tiên tương tác mượt mà qua AJAX / Fetch API (Zero Full-Page Refresh) và thông báo Toast góc phải.
9. **Ràng Buộc Thiết Kế UI Bắt Buộc (Strict UI Rules):**
   - **Anti-Boxed Layout:** Tuyệt đối không đóng khung lơ lửng hình ảnh nền hoặc khối visual (Hero, Login, Register) vào một thẻ bo góc lọt thỏm giữa trang. Hero và Auth bắt buộc dùng layout tràn viền Fullscreen / Split-Screen (`100vw`).
   - **Bảng Màu Cấm:** Cấm tuyệt đối màu vàng chói lóa (`#FFC107`, `#FFD700`, v.v.), cấm gradient đỏ đen dày đặc che khuất ảnh trường học. Chỉ sử dụng bộ màu chuẩn: Harvard Crimson (`#A51C30`), Deep Slate (`#111827`), Light Gray (`#F8F9FA`), và White (`#FFFFFF`).
   - **Form & Typography:** Sử dụng font `Inter` cho nội dung thông thường, `Playfair Display` cho tiêu đề trang trọng, độ tương phản chữ phải đạt chuẩn WCAG AA.

# 🏛️ SYSTEM RULES FOR AI CODE GENERATOR (STRICT MODE - ZERO HALLUCINATION)
**Project:** QAUTE Portal - Cổng Tư Vấn & Hỗ Trợ Học Vụ Sinh Viên HCMUTE
**Reference Documents:** docs/brief.md > docs/team/engineering-rules.md > dev-assignment.md > dev-tasks.md
**Precedence Rule:** Mọi xung đột logic hoặc thư viện BẮT BUỘC tuân theo thứ tự ưu tiên trên. docs/brief.md là NGUỒN SỰ THẬT TỐI THƯỢNG (Single Source of Truth).

---

## 1. PHẠM VI CÔNG NGHỆ BẮT BUỘC (HARD TECH CONSTRAINTS)
Mọi đoạn code sinh ra CHỈ ĐƯỢC PHÉP sử dụng các công nghệ và phiên bản đã chốt sau:
- **Core Framework:** Java 17 (LTS), Spring Boot 3.3+.
- **Database:** MySQL 8.x + Spring Data JPA (Hibernate 6). CẤM dùng SQL Server hay PostgreSQL.
- **Security:** Spring Security 6 (Cấu hình SecurityFilterChain Bean). TUYỆT ĐỐI KHÔNG kế thừa WebSecurityConfigurerAdapter (đã bị xóa).
- **Frontend / Template:** Thymeleaf + Thymeleaf Layout Dialect (`nz.net.ultraq.thymeleaf:thymeleaf-layout-dialect`) + Bootstrap 5.3+. CẤM dùng JSP/JSTL, CẤM inline CSS/JS rải rác.
- **Cache & Async:** Caffeine In-Memory Cache (org.springframework.boot:spring-boot-starter-cache + com.github.ben-manes.caffeine).
- **AI Integration:** Google Gemini API (`text-embedding-004`, `gemini-1.5-flash` / `gemini-2.5-flash`), Apache PDFBox 3.0.x, Apache Tika Core 2.9.x.

---

## 2. NGUYÊN TẮC CHỐNG ẢO GIÁC & TỰ BỊA ĐẶT (ANTI-HALLUCINATION RULES)
1. **Khóa cứng thư viện & Class:**
   - TUYỆT ĐỐI KHÔNG tự ý tạo ra các class Util/Helper, Service, Repository, DTO hoặc Interface mới ngoài những gì đã được định nghĩa trong tài liệu thiết kế.
   - KHÔNG import bất kỳ thư viện ngoài nào chưa có trong `pom.xml`.
2. **Không tự sinh method ảo:**
   - Chỉ được gọi các method đã tồn tại trên Repository, Service hoặc Interface. Nếu thiếu nghiệp vụ, BẮT BUỘC phải hỏi lại người dùng hoặc ném `UnsupportedOperationException`, KHÔNG ĐƯỢC tự bịa code.
3. **Giữ nguyên vẹn mã nguồn & UI (UI Integrity):**
   - Không in code tắt dạng `// ... giữ nguyên code cũ`. Nếu sửa 1 hàm, chỉ xuất đúng hàm đó hoặc file hoàn chỉnh.
   - Giữ nguyên toàn bộ cấu trúc id, class CSS Bootstrap 5, và fragment layout `layout:decorate="~{layout/main}"`. Tuyệt đối không xóa hay đổi tên các biến Model/DTO truyền sang Thymeleaf template.

---

## 3. QUY CHUẨN BACKEND & DATABASE CHUẨN MỰC
1. **Entity & Hibernate 6:**
   - Mọi quan hệ `@ManyToOne`, `@OneToMany` BẮT BUỘC đặt `fetch = FetchType.LAZY`. CẤM dùng `FetchType.EAGER`.
   - Soft Delete: Bắt buộc dùng `@SQLDelete` và `@SQLRestriction("is_deleted = false")`. CẤM dùng `@Where` (đã deprecated trong Hibernate 6).
   - Auditing: Toàn bộ Entity kế thừa `BaseEntity` (id, created_at, updated_at, is_deleted).
2. **Quản lý kết nối & Giao dịch:**
   - `spring.jpa.open-in-view: false` đã bật: TẤT CẢ các câu query lấy dữ liệu hiển thị Lazy trên Thymeleaf phải viết qua `JOIN FETCH` hoặc `@EntityGraph` trong Repository để triệt tiêu lỗi N+1 Query và LazyInitializationException.
   - Các phương thức Service thay đổi dữ liệu: BẮT BUỘC có `@Transactional`. Các hàm chỉ đọc: BẮT BUỘC `@Transactional(readOnly = true)`.
3. **Chống Concurrency & Race Condition:**
   - Khi Cán bộ nhận Ticket (Claim Ticket), BẮT BUỘC dùng Atomic Update Query trong `TicketRepository`:
     `UPDATE Ticket t SET t.assignedStaff = :staff, t.status = 'IN_PROGRESS' WHERE t.id = :id AND t.status = 'OPEN'`
     Nếu kết quả trả về = 0, ném ngay `TicketAlreadyClaimedException`.
4. **Phân quyền dữ liệu theo Khoa (Department Isolation):**
   - Cán bộ Khoa nào CHỈ ĐƯỢC XEM VÀ XỬ LÝ Ticket/Chat của Khoa đó. Bắt buộc kiểm tra `department_id` của Staff với `department_id` của Ticket trước khi thực thi nghiệp vụ. Vi phạm ném `AccessDeniedBusinessException`.

---

## 4. QUY CHUẨN XỬ LÝ LUỒNG AI RAG (CHỐNG LỆCH NGỮ CẢNH & NGHẼN TOMCAT)
AI Chatbot phục vụ tại Widget nổi góc phải màn hình (`/api/v1/ai/chat`) BẮT BUỘC tuân thủ đúng thứ tự 4 tầng sau: