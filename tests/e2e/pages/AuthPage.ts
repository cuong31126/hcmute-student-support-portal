import { Page, Locator, expect } from '@playwright/test';

export class AuthPage {
  readonly page: Page;
  readonly usernameInput: Locator;
  readonly passwordInput: Locator;
  readonly loginButton: Locator;
  readonly registerLink: Locator;
  readonly emailInput: Locator;
  readonly fullNameInput: Locator;
  readonly submitRegisterButton: Locator;
  readonly otpInput: Locator;
  readonly verifyOtpButton: Locator;
  readonly userDropdown: Locator;
  readonly logoutButton: Locator;

  constructor(page: Page) {
    this.page = page;
    this.usernameInput = page.locator('#username');
    this.passwordInput = page.locator('#password');
    this.loginButton = page.locator('button[type="submit"]:has-text("Đăng nhập")');
    this.registerLink = page.locator('a:has-text("Đăng ký tài khoản")');
    this.emailInput = page.locator('#email');
    this.fullNameInput = page.locator('#fullName');
    this.submitRegisterButton = page.locator('button[type="submit"]:has-text("Đăng ký")');
    this.otpInput = page.locator('#otpCode');
    this.verifyOtpButton = page.locator('button:has-text("Xác nhận OTP")');
    this.userDropdown = page.locator('#userDropdown');
    this.logoutButton = page.locator('form[action*="/auth/logout"] button, a:has-text("Đăng xuất")');
  }

  async gotoLogin() {
    await this.page.goto('/auth/login');
  }

  async login(username: string, password: string) {
    await this.usernameInput.fill(username);
    await this.passwordInput.fill(password);
    await this.loginButton.click();
    await this.page.waitForLoadState('networkidle');
  }

  async register(username: string, email: string, fullName: string, password: string) {
    await this.page.goto('/auth/register');
    await this.usernameInput.fill(username);
    await this.emailInput.fill(email);
    await this.fullNameInput.fill(fullName);
    await this.passwordInput.fill(password);
    await this.submitRegisterButton.click();
    await this.page.waitForLoadState('networkidle');
  }

  async verifyOtp(otp: string) {
    await this.otpInput.fill(otp);
    await this.verifyOtpButton.click();
    await this.page.waitForLoadState('networkidle');
  }

  async logout() {
    if (await this.userDropdown.isVisible()) {
      await this.userDropdown.click();
    }
    await this.logoutButton.click();
  }
}
