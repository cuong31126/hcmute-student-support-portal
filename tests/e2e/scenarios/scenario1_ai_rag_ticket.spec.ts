import { test, expect } from '@playwright/test';
import { AiAssistantModal } from '../pages/AiAssistantModal';

test.describe('Scenario 1: Hỏi đáp Tuyển sinh với AI RAG & Fallback Tạo Ticket', () => {
  test('Guest mở web -> Chat AI tra cứu học phí/chỉ tiêu -> Fallback tạo Ticket cho chuyên viên', async ({ page }) => {
    const aiModal = new AiAssistantModal(page);

    // 1. Guest truy cập trang chủ
    await page.goto('/');
    await expect(page).toHaveTitle(/QAUTE Portal|Tư Vấn Sinh Viên/i);

    // 2. Mở Chatbot và hỏi câu hỏi tuyển sinh
    await aiModal.sendMessage('Học phí ngành Công nghệ Thông tin hệ đại trà năm 2026 là bao nhiêu?');

    // 3. AI phản hồi có trích dẫn nguồn
    await aiModal.expectAiAnswerContaining('học phí');

    // 4. Guest hỏi câu hỏi cá biệt phức tạp
    await aiModal.sendMessage('Em muốn hỏi trường hợp đặc biệt: Em là người dân tộc thiểu số nghèo có được miễn giảm 100% học phí không?');

    // 5. Chatbot gợi ý nút "Chuyển thành Ticket gửi Phòng Đào tạo & CTSV"
    await expect(aiModal.fallbackTicketPromptButton).toBeVisible();

    // 6. Guest click nút chuyển đổi và gửi Ticket
    await aiModal.convertToTicket('thisinh.tuyensinh@gmail.com');

    // 7. Kỳ vọng hiển thị thông báo gửi Ticket thành công kèm mã tra cứu
    await expect(page.locator('.alert-success, #ticketSuccessBanner')).toBeVisible();
  });
});
