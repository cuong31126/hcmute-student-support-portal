import { test, expect } from '@playwright/test';
import { AuthPage } from '../pages/AuthPage';
import { StudentFeedPage } from '../pages/StudentFeedPage';
import { ModerationPage } from '../pages/ModerationPage';

test.describe('Scenario 2: Luồng Duyệt Bài Diễn Đàn & Xử Lý Báo Cáo Vi Phạm', () => {
  test('Sinh viên đăng bài chờ duyệt -> Cán bộ duyệt bài -> Báo cáo vi phạm -> Cán bộ ẩn bài', async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();

    const authPage = new AuthPage(page);
    const feedPage = new StudentFeedPage(page);
    const modPage = new ModerationPage(page);

    // 1. Sinh viên đăng nhập
    await authPage.gotoLogin();
    await authPage.login('student01', 'Password123@');

    // 2. Sinh viên gửi bài thảo luận
    const postTitle = `[Test] Thảo luận tài liệu ôn thi cuối kỳ ${Date.now()}`;
    await feedPage.submitDiscussion(postTitle, 'Các bạn có tài liệu ôn thi môn Kiến trúc Máy tính không ạ?');

    // 3. Kiểm tra bài viết ở trạng thái PENDING_APPROVAL
    await expect(feedPage.pendingAlert).toBeVisible();
    await authPage.logout();

    // 4. Cán bộ đăng nhập vào phê duyệt bài viết
    await authPage.login('staff_doan', 'Password123@');
    await modPage.approveFirstPendingPost();
    await authPage.logout();

    // 5. Bài viết xuất hiện trên Diễn đàn công khai
    await feedPage.gotoForum();
    await expect(page.locator(`text=${postTitle}`)).toBeVisible();

    // 6. Người dùng khác đăng nhập và gửi Báo cáo vi phạm
    await authPage.login('student02', 'Password123@');
    await feedPage.gotoForum();
    await feedPage.reportFirstPost('INAPPROPRIATE_LANGUAGE');
    await authPage.logout();

    // 7. Cán bộ vào Trung tâm Báo cáo vi phạm và Ẩn bài viết
    await authPage.login('staff_doan', 'Password123@');
    await modPage.hideFirstReportedPost();

    // 8. Xác nhận bài viết không còn hiển thị trên Diễn đàn
    await feedPage.gotoForum();
    await expect(page.locator(`text=${postTitle}`)).not.toBeVisible();
  });
});
