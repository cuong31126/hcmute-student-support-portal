# 🏛️ TÀI LIỆU KIẾN TRÚC & HƯỚNG DẪN BÀN GIAO: PHÂN HỆ TICKET VÀ CHAT MODULE
**Dự án:** QAUTE Portal - Cổng Tư Vấn & Hỗ Trợ Học Vụ Sinh Viên HCMUTE  
**Vị trí tài liệu:** `docs/requirements/chat_module_and_ticketing_architecture_guide.md`  
**Ngày lập:** 10/10/2026  
**Mục đích:** Giải thích rõ kiến trúc cốt lõi của hệ thống, lý do bảng `conversations`/`messages` không có dữ liệu, và danh sách các file Prototype để người tiếp nhận dự án (Developer kế nhiệm) không mất thời gian code lại hoặc xóa nhầm file dùng chung.

---

## 1. TỔNG QUAN KIẾN TRÚC: VÌ SAO CHỌN TICKET THAY VÌ CHAT REALTIME TỰ DO?

Trong môi trường hành chính đại học (Đào tạo, Công tác sinh viên, Khảo thí, các Khoa chuyên ngành):
- **Cán bộ / Giảng viên KHÔNG THỂ trực chat thời gian thực 24/7** như nhân viên bán hàng thương mại điện tử.
- Các yêu cầu như *Xin hoãn thi, Phúc khảo điểm, Trùng lịch học, Cấp bảng điểm, Miễn giảm học phí* bắt buộc phải:
  1. Có hồ sơ chính thức, mã tra cứu duy nhất (`ticket_code`).
  2. Gắn hạn chót giải quyết cam kết (**SLA Engine**).
  3. Phân quyền chặt chẽ theo Khoa/Phòng (**Department Data Isolation**).
  4. Đánh giá chất lượng tư vấn sau khi hoàn tất (**CSAT Rating 1-5 sao**).

👉 Do đó, **Kiến trúc Trọng tâm của QAUTE Portal là Hệ Thống Helpdesk / Ticketing**, không phải ứng dụng Chat mạng xã hội.

---

## 2. PHỄU NGHIỆP VỤ 4 TẦNG THỰC TẾ ĐANG VẬN HÀNH

```
                 [Sinh viên gặp vướng mắc học vụ]
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│ TẦNG 1: TỰ PHỤ CỤ                                               │
│ Tra cứu nhanh Câu hỏi thường gặp (FAQ) & Bảng tin Công văn      │
└───────────────────────────────┬─────────────────────────────────┘
                                │ (Nếu chưa tìm được giải đáp)
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│ TẦNG 2: HỎI ĐÁP TỨC THỜI VỚI AI                                 │
│ Chatbot Widget góc phải màn hình (`/api/v1/ai/chat`)             │
│ Dùng Dify Chatflow & Gemini 2.5 Flash RAG tra cứu quy chế       │
└───────────────────────────────┬─────────────────────────────────┘
                                │ (Nếu AI không đủ tin cậy / Cần can thiệp hành chính)
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│ TẦNG 3: MỞ HỒ SƠ TƯ VẤN (TICKET SLA)                            │
│ Giao diện `/tickets/create`: Điền Form hoặc chuyển từ câu hỏi AI│
│ Tự động gắn hạn giải quyết (SLA) và gửi mã tra cứu qua Email    │
└───────────────────────────────┬─────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│ TẦNG 4: XỬ LÝ & ĐÓNG HỒ SƠ                                      │
│ Cán bộ Khoa/Phòng nhận vé (Claim Ticket bằng Atomic Query)      │
│ Trao đổi phản hồi qua Ticket History & Chấm điểm hài lòng (CSAT) │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. GIẢI THÍCH VÌ SAO 2 BẢNG `conversations` VÀ `messages` HOÀN TOÀN RỖNG

Khi kiểm tra MySQL Database (`qaute_portal`), bạn sẽ thấy:
- Bảng `tickets` có rất nhiều bản ghi với `conversation_id = NULL`.
- Bảng `conversations` và `messages` có **0 dòng dữ liệu**.

### Nguyên nhân:
1. **Khung chat nổi góc màn hình là AI Chatbot Widget (`templates/ai/chat-widget.html`):**
   * Người dùng nhắn tin trong widget này là đang hỏi AI RAG thông qua REST API non-blocking `POST /api/v1/ai/chat`.
   * Luồng này phản hồi tức thời trên bộ nhớ trình duyệt, **không lưu vào bảng `conversations`**.
   * Khi AI không chắc chắn, widget hiển thị nút *"Gửi câu hỏi này thành Ticket tới Cán bộ"*, nút này chuyển hướng (redirect) người dùng sang `http://localhost:8080/tickets/create?title=...`.
2. **Trang `http://localhost:8080/tickets/create` là Web Form trực tiếp:**
   * Sinh viên gửi form tại đây $\rightarrow$ Backend gọi `ticketService.createTicket()` ghi thẳng vào bảng `tickets`.
   * Vì không xuất phát từ phiên chat người-người, cột `conversation_id` hiển thị `NULL`.
3. **Module Chat 1-1 Realtime (WebSocket) chưa được làm giao diện (UI):**
   * Backend đã viết đầy đủ mã nguồn cho WebSocket STOMP (`/ws-chat`), nhưng trên giao diện Web chưa có màn hình phòng chat Người-với-Người để gọi API `POST /api/v1/chat/conversations`.
   * Vì vậy 2 bảng này chưa từng nhận được dữ liệu thực tế.

---

## 4. DANH MỤC CÁC FILE "PROTOTYPE / UNUSED" (ĐÃ ĐÁNH DẤU `@Deprecated`)

Tất cả các file dưới đây thuộc phân hệ Chat Realtime Người-Người đang để ở trạng thái **Prototype chờ phát triển UI sau**, đã được gắn chú thích `@Deprecated` và Javadoc:

| STT | Đường dẫn File | Vai trò | Trạng thái |
|:---:|:---|:---|:---:|
| 1 | `com.school.counseling.config.WebSocketConfig` | Cấu hình broker Spring STOMP (`/ws-chat`) | `@Deprecated` |
| 2 | `module.chat.controller.ChatWebSocketController` | Nhận tin nhắn WebSocket (`/app/chat.sendMessage`) | `@Deprecated` |
| 3 | `module.chat.controller.ChatRestController` | REST API tạo phòng và tin nhắn chat | `@Deprecated` |
| 4 | `module.chat.service.ChatService` | Logic nghiệp vụ tạo conversation và broadcast | `@Deprecated` |
| 5 | `module.chat.repository.ConversationRepository` | Truy vấn JPA cho bảng `conversations` | `@Deprecated` |
| 6 | `module.chat.repository.MessageRepository` | Truy vấn JPA cho bảng `messages` | `@Deprecated` |
| 7 | `module.chat.entity.Conversation` | Entity bảng `conversations` | `@Deprecated` |
| 8 | `module.chat.entity.Message` | Entity bảng `messages` | `@Deprecated` |
| 9 | `module.chat.dto.ConversationDto` | DTO trao đổi cuộc hội thoại | `@Deprecated` |
| 10 | `module.chat.dto.ChatMessageDto` | DTO tin nhắn chat | `@Deprecated` |
| 11 | `module.ticket.controller.TicketRestController#convertChatToTicket` | Endpoint `POST /convert-chat/{id}` (chưa có UI gọi) | `@Deprecated` |
| 12 | `module.ticket.service.TicketService#convertConversationToTicket` | Hàm gom tin nhắn chat thành Ticket | `@Deprecated` |

---

## 5. ⚠️ CẢNH BÁO QUAN TRỌNG KHI BÀN GIAO (KHÔNG ĐƯỢC XÓA NHẦM!)

Khi bàn giao dự án hoặc tái cấu trúc (refactor), **tuyệt đối chú ý 3 điểm sau**:

1. **`Attachment.java` và `AttachmentRepository.java` ĐANG ĐƯỢC DÙNG BỞI TICKET:**
   * Dù nằm trong package `com.school.counseling.module.chat.entity`, Entity `Attachment` (bảng `attachments`) chứa trường:
     ```java
     @ManyToOne(fetch = FetchType.LAZY)
     @JoinColumn(name = "ticket_id")
     private Ticket ticket;
     ```
   * Khi người dùng upload minh chứng tại `/tickets/create`, `TicketService` trực tiếp lưu tệp vào `AttachmentRepository`.
   * **Nếu xóa class này, chức năng nộp minh chứng của Ticket sẽ lập tức bị lỗi biên dịch.**
   * *Khuyến nghị khi refactor sau này:* Di chuyển `Attachment.java` sang package `com.school.counseling.module.ticket.entity` hoặc `com.school.counseling.common.storage`.

2. **Cột `conversation_id` trong Entity `Ticket.java` và CSDL:**
   * `Ticket.java` có trường `private Conversation conversation;` (nullable).
   * Cột này hoàn toàn an toàn khi mang giá trị `NULL`. Không bắt buộc phải xóa trong Database schema.

---

## 6. LỜI KHUYÊN DÀNH CHO BÁO CÁO / THI ĐỒ ÁN VÀ PHÁT TRIỂN TIẾP

* **Khi bảo vệ trước Hội đồng chấm thi / Thầy Cô:**
  Trình bày rõ ràng:
  > *"Hệ thống được chuẩn hóa theo quy trình hành chính công của trường đại học, lấy **Kiến trúc Quản lý Ticket (SLA)** làm cốt lõi. Khung chat nổi trên màn hình là **Trợ lý AI RAG** để sinh viên tra cứu nhanh; khi vụ việc cần cán bộ thụ lý, hệ thống điều hướng mở Ticket chính thức. Phân hệ Chat WebSocket 1-1 được xây dựng ở tầng backend như một tính năng mở rộng (Phase 2), không ảnh hưởng đến luồng vận hành chính thức của hệ thống."*
* **Nếu nhóm tiếp theo muốn phát triển tiếp tính năng Chat 1-1 Người-Người:**
  Backend đã hoàn chỉnh 100%. Nhóm chỉ cần dựng thêm giao diện Thymeleaf (hoặc React/Vue) kết nối tới thư viện `stompjs` + `sockjs-client` trỏ vào endpoint `/ws-chat`.
