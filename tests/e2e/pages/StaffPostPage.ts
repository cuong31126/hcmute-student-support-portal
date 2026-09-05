import { Page, Locator, expect } from '@playwright/test';

export class StaffPostPage {
  readonly page: Page;
  readonly titleInput: Locator;
  readonly contentInput: Locator;
  readonly fileUploadInput: Locator;
  readonly submitPostButton: Locator;
  readonly officialPostCards: Locator;

  constructor(page: Page) {
    this.page = page;
    this.titleInput = page.locator('#postTitle');
    this.contentInput = page.locator('#postContent');
    this.fileUploadInput = page.locator('input[type="file"]#postAttachments');
    this.submitPostButton = page.locator('button[type="submit"]:has-text("Đăng thông báo")');
    this.officialPostCards = page.locator('.official-post-card');
  }

  async createOfficialPost(title: string, content: string, filePath?: string) {
    await this.page.goto('/posts/official/create');
    await this.titleInput.fill(title);
    await this.contentInput.fill(content);
    if (filePath) {
      await this.fileUploadInput.setInputFiles(filePath);
    }
    await this.submitPostButton.click();
    await this.page.waitForLoadState('networkidle');
  }
}
