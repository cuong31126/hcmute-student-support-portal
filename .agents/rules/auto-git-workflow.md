# Rule: Mandatory Auto Git Workflow

Mỗi khi AI hoàn thành và kiểm tra (test-compile / verify) xong một chức năng, module, hoặc sửa lỗi:
1. BẮT BUỘC tự động chạy lệnh Git:
   ```bash
   git add .
   git commit -m "<type>(<scope>): <mô tả ngắn gọn chức năng vừa làm>"
   git push origin <tên-nhánh-hiện-tại>
   ```
2. Commit message phải tuân thủ chuẩn Conventional Commits (ví dụ: `feat(forum): restructure to facebook feed style with ajax and top-right toast`).
3. Tuyệt đối KHÔNG dùng cờ `--force`. Nếu gặp xung đột (conflict hoặc rejected), dừng lại và thông báo ngay cho người dùng.
