import { Page, Locator, expect } from '@playwright/test';

export class ModerationPage {
  readonly page: Page;
  readonly pendingPostsRows: Locator;
  readonly approveButton: Locator;
  readonly rejectButton: Locator;
  readonly reportsTableRows: Locator;
  readonly hidePostButton: Locator;

  constructor(page: Page) {
    this.page = page;
    this.pendingPostsRows = page.locator('table#pendingPostsTable tbody tr');
    this.approveButton = page.locator('button.btn-approve-post');
    this.rejectButton = page.locator('button.btn-reject-post');
    this.reportsTableRows = page.locator('table#reportsTable tbody tr');
    this.hidePostButton = page.locator('button.btn-hide-post');
  }

  async gotoPendingPosts() {
    await this.page.goto('/moderation/posts');
  }

  async approveFirstPendingPost() {
    await this.gotoPendingPosts();
    await this.approveButton.first().click();
    await this.page.waitForLoadState('networkidle');
  }

  async gotoReports() {
    await this.page.goto('/moderation/reports');
  }

  async hideFirstReportedPost() {
    await this.gotoReports();
    await this.hidePostButton.first().click();
    await this.page.waitForLoadState('networkidle');
  }
}
