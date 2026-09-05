import { Page, Locator, expect } from '@playwright/test';

export class StudentFeedPage {
  readonly page: Page;
  readonly forumTitleInput: Locator;
  readonly forumContentInput: Locator;
  readonly submitForumPostButton: Locator;
  readonly pendingAlert: Locator;
  readonly postCards: Locator;
  readonly reportButton: Locator;
  readonly reportReasonSelect: Locator;
  readonly submitReportButton: Locator;

  constructor(page: Page) {
    this.page = page;
    this.forumTitleInput = page.locator('#forumTitle');
    this.forumContentInput = page.locator('#forumContent');
    this.submitForumPostButton = page.locator('button[type="submit"]:has-text("Đăng bài thảo luận")');
    this.pendingAlert = page.locator('.alert-info:has-text("chờ duyệt")');
    this.postCards = page.locator('.forum-post-card');
    this.reportButton = page.locator('button.btn-report-post');
    this.reportReasonSelect = page.locator('#reportReason');
    this.submitReportButton = page.locator('#btnSubmitReport');
  }

  async gotoForum() {
    await this.page.goto('/forum');
  }

  async submitDiscussion(title: string, content: string) {
    await this.gotoForum();
    await this.forumTitleInput.fill(title);
    await this.forumContentInput.fill(content);
    await this.submitForumPostButton.click();
    await this.page.waitForLoadState('networkidle');
  }

  async reportFirstPost(reason: string) {
    await this.reportButton.first().click();
    await this.reportReasonSelect.selectOption(reason);
    await this.submitReportButton.click();
    await this.page.waitForLoadState('networkidle');
  }
}
