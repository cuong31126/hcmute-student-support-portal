import { test, expect } from '@playwright/test';
import { AuthPage } from '../pages/AuthPage';
import { TicketDashboardPage } from '../pages/TicketDashboardPage';

test.describe('Scenario 3: Multi-Context Ticket SLA Real-Time Flow', () => {
  test('Context 1 (Guest/Student gửi Ticket) <-> Context 2 (Staff Khoa/Phòng tiếp nhận & gắn SLA)', async ({ browser }) => {
    // Tạo 2 Browser Contexts độc lập (giả lập 2 người dùng thật trên 2 máy khác nhau)
    const guestContext = await browser.newContext();
    const staffContext = await browser.newContext();

    const guestPage = await guestContext.newPage();
    const staffPage = await staffContext.newPage();

    const guestTicket = new TicketDashboardPage(guestPage);
    const staffAuth = new AuthPage(staffPage);
    const staffTicket = new TicketDashboardPage(staffPage);

    // 1. Staff Tuyển sinh đăng nhập trên Context 2 và mở Dashboard Ticket
    await staffAuth.gotoLogin();
    await staffAuth.login('staff_tuyensinh', 'Password123@');
    await staffTicket.gotoStaffDashboard();

    // 2. Guest trên Context 1 gửi Ticket chọn Phòng Tuyển sinh mức độ URGENT
    const ticketTitle = `[SLA Test] Thắc mắc nộp hồ sơ xét tuyển học bạ ${Date.now()}`;
    await guestTicket.createGuestTicket(
      'Trần Minh Thí Sinh',
      'thisinh.sla@gmail.com',
      'Phòng Tuyển sinh & Truyền thông',
      ticketTitle,
      'Cho em hỏi hạn chót nộp hồ sơ trực tiếp là ngày mấy ạ?',
      'URGENT'
    );

    // 3. Staff trên Context 2 reload và nhìn thấy Ticket mới
    await staffPage.reload();
    await expect(staffPage.locator(`text=${ticketTitle}`)).toBeVisible();

    // 4. Staff bấm Tiếp nhận (Claim) Ticket
    await staffTicket.claimFirstTicket();

    // 5. Kiểm tra Badge SLA hiển thị đúng hạn 24h
    await staffTicket.verifySlaBadge('24h');

    // 6. Dọn dẹp session
    await guestContext.close();
    await staffContext.close();
  });
});
