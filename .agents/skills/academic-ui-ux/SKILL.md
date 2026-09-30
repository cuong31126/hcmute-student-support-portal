---
name: academic-ui-ux
description: "Quy chuẩn thiết kế giao diện (UI/UX Design System) học thuật chuẩn mực cho dự án QAUTE Portal. Sử dụng bắt buộc mỗi khi chỉnh sửa giao diện, HTML, CSS hoặc Thymeleaf templates."
---

# 🎓 Academic UI/UX Design System & Ràng Buộc Giao Diện QAUTE Portal

> **MỤC ĐÍCH:** Tài liệu này là **ràng buộc tối cao về UI/UX** cho mọi AI Agent khi thực hiện bất kỳ thay đổi nào liên quan đến giao diện (HTML, CSS, Thymeleaf, JS tương tác) trong repository này. Tránh hoàn toàn việc AI "tự trình diễn" sinh ra giao diện dị hợm, màu sắc lòe loẹt hoặc bố cục đóng khung lơ lửng.

---

## 🎨 1. BẢNG MÀU CHUẨN THƯƠNG HIỆU (COLOR IDENTITY)

Hệ thống được xây dựng theo nhận diện đại học tinh hoa (Academic Prestige - tương tự Harvard / Oxford / HCMUTE):

| Token CSS | Mã Màu HEX | Ứng Dụng |
|---|---|---|
| `--crimson` | `#A51C30` | Màu thương hiệu HCMUTE: Active link, border gạch chân, icon chính. |
| `--dark-crimson` | `#8A1525` | Trạng thái hover/focus của nút Crimson, điểm nhấn chiều sâu. |
| `--light-crimson`| `#FDF2F4` | Nền icon, badge nhẹ, thông báo highlight trong suốt. |
| `--dark-slate`   | `#111827` | Nút Đăng Nhập/Hành động chính, Header/Navbar, Dark Card, Footer. |
| `--navy-accent`  | `#1E3A8A` | Điểm nhấn học thuật dịu mát, liên kết phụ hoặc icon học vụ. |
| `--body-bg`     | `#F8F9FA` | Nền tổng thể toàn trang (xám học đường thanh lịch). |
| `--card-bg`     | `#FFFFFF` | Nền thẻ bài viết, form, card nội dung. |
| `--text-main`   | `#111827` | Chữ nội dung thông thường, tiêu đề phụ. |
| `--text-muted`  | `#6B7280` | Chữ thời gian, mô tả phụ, hướng dẫn nhỏ. |

### ⛔ CÁC MÀU & HIỆU ỨNG BỊ CẤM TUYỆT ĐỐI (STRICTLY FORBIDDEN):
- **CẤM màu vàng chói lóa (`#FFC107`, `#FFD700`, v.v.):** Gây cảm giác đập vào mắt người dùng, rẻ tiền và mất chất học thuật.
- **CẤM gradient màu đỏ-đen dày đặc (opacity > 0.6 hoặc màu đỏ gắt):** Làm biến hình ảnh đẹp của trường thành một cục màu đen đỏ tù túng, mất hoàn toàn ánh sáng và màu sắc tự nhiên của cổng trường.
- **CẤM gradient tím hồng, tím xanh lộn xộn:** Tránh phong cách gamer/crypto, chỉ dùng phong cách Enterprise Academic.
- **BẮT BUỘC dùng định dạng ảnh hiện đại `.webp` (`hero1.webp`, `hero2.webp`):** Tối ưu tốc độ tải và giữ độ sắc nét chân thực. Cột visual trang Auth và Hero Banner phải có hiệu ứng slideshow tự động chuyển cảnh mềm mại (crossfade).

---

## 📐 2. BỐ CỤC & NGUYÊN TẮC PHÁ KHUNG (FULL-BLEED VS CONTAINER)

### 🚫 Quy Tắc Chống "Đóng Khung Lơ Lửng" (Anti-Boxed Layout):
- **LỖI NGHIÊM TRỌNG:** Nhốt các khối visual lớn (Hero Banner, Auth Cover Image, Toàn cảnh trường) vào trong một `.card` hoặc thẻ có `border-radius: 16px` lọt thỏm giữa trang với nền xám xung quanh.
- **CHUẨN MỰC:**
  1. **Hero Banner Trang Chủ:** Phải tràn toàn màn hình (`100vw`, `80vh - 85vh`), hiệu ứng crossfade nhẹ nhàng, lớp phủ trong suốt điện ảnh (`rgba(15, 23, 42, 0.4)` đến `0.65`) để lộ rõ ảnh chụp trường.
  2. **Trang Đăng Nhập / Đăng Ký (Auth Pages):** Bắt buộc dùng **Split-Screen Layout** (55% Cột Trái là visual trường học sắc nét + Sứ mạng; 45% Cột Phải là Form trắng sạch sẽ).
  3. **Kỹ thuật Breakout chuẩn Bootstrap:**
     ```css
     .fullscreen-breakout {
         width: 100vw;
         margin-left: calc(-50vw + 50%);
         margin-right: calc(-50vw + 50%);
         margin-top: -1.5rem;
         overflow: hidden;
     }
     ```
  4. Đảm bảo `body { overflow-x: hidden; }` để không bao giờ xuất hiện thanh cuộn ngang khó chịu.

### 📋 Bố cục Trang Nghiệp Vụ & Bảng Tin:
- Trang danh sách Ticket, Diễn đàn, FAQ, Admin Dashboard: **BẮT BUỘC nằm trong `.container` chuẩn** để đảm bảo tính tập trung, không kéo dài chữ quá 900px gây mỏi mắt người đọc.
- Cột tin tức/diễn đàn thiết kế theo phong cách Facebook/LinkedIn Enterprise: Card bài viết nền trắng `#ffffff`, bo góc `8px - 10px`, viền cực mảnh `1px solid #e5e7eb`, bóng đổ nhẹ `0 1px 3px rgba(0,0,0,0.05)`.

---

## 🔤 3. TYPOGRAPHY & PHONG CÁCH CHỮ

- **Font chữ tiêu đề thương hiệu / Hero / Portal Title:** `Playfair Display` (serif sang trọng, mang phong cách trường đại học lâu đời).
- **Font chữ giao diện chính & nội dung:** `Inter` (sans-serif hiện đại, độ đọc tối ưu trên mọi màn hình).
- **Quy tắc phân cấp văn bản:**
  - Tiêu đề cấp 1: `font-serif fw-bold text-dark` hoặc `text-white` có `text-shadow` nhẹ khi nằm trên ảnh.
  - Chữ nội dung: Mặc định là `#111827`, không dùng màu xám quá nhạt gây khó đọc (đảm bảo độ tương phản WCAG AA >= 4.5:1).
  - Liên kết (Links): Khi hover có gạch chân đỏ Crimson (`text-decoration-color: var(--crimson)` hoặc pseudo-element `::after`).

---

## ⚡ 4. TRẢI NGHIỆM TƯƠNG TÁC (INTERACTION & FEEDBACK)

1. **Thông Báo Phản Hồi (Feedback System):**
   - **Tuyệt đối KHÔNG dùng `alert()` mặc định của trình duyệt.**
   - Sử dụng **Toast Bootstrap góc trên cùng bên phải (`#feedToastContainer` / `#toastContainer`)** tự động ẩn sau 3-4 giây.
2. **Tương Tác Zero-Refresh (AJAX / Fetch API):**
   - Thao tác Like, Comment, Filter Tab, Tải thêm bài viết: Dùng `fetch()` hoặc AJAX, cập nhật DOM mượt mà, không bao giờ load lại toàn bộ trang.
3. **Nút Bấm & Micro-Animations:**
   - Button chính: Bo tròn pill (`rounded-pill`) hoặc góc bo mềm (`rounded-2`, 8px).
   - Hover effect: Dịch chuyển nhẹ `-2px` (`transform: translateY(-2px); transition: all 0.2s ease;`).
   - Cấm các animation giật cục, chớp nháy gây khó chịu cho người dùng.

---

## ✅ 5. CHECKLIST TRƯỚC KHI COMMIT THAY ĐỔI UI

Trước khi tạo commit về giao diện, AI BẮT BUỘC tự kiểm tra:
- [ ] 1. Trang có bị lỗi đóng khung lơ lửng hình ảnh (Boxed Hero / Boxed Auth) không?
- [ ] 2. Có dùng màu vàng chói hay màu sắc ngoài bảng màu nhận diện không?
- [ ] 3. Chữ nội dung có dễ đọc, tương phản rõ ràng không?
- [ ] 4. Giao diện trên màn hình điện thoại (mobile) có bị vỡ hay tràn ngang không?
- [ ] 5. Các thông báo đã chuyển sang Toast góc phải chưa?
