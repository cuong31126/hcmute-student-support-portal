import { Page, Locator, expect } from '@playwright/test';

export class TicketDashboardPage {
  readonly page: Page;
  readonly createTicketButton: Locator;
  readonly titleInput: Locator;
  readonly departmentSelect: Locator;
  readonly prioritySelect: Locator;
  readonly descriptionInput: Locator;
  readonly guestNameInput: Locator;
  readonly guestEmailInput: Locator;
  readonly submitButton: Locator;
  readonly ticketTableRows: Locator;
  readonly claimButton: Locator;
  readonly resolveButton: Locator;
  readonly statusBadge: Locator;
  readonly slaBadge: Locator;

  constructor(page: Page) {
    this.page = page;
    this.createTicketButton = page.locator('a[href*="/tickets/create"]');
    this.titleInput = page.locator('#title');
    this.departmentSelect = page.locator('#departmentId');
    this.prioritySelect = page.locator('#priority');
    this.descriptionInput = page.locator('#description');
    this.guestNameInput = page.locator('#guestName');
    this.guestEmailInput = page.locator('#guestEmail');
    this.submitButton = page.locator('button[type="submit"]:has-text("Gửi yêu cầu")');
    this.ticketTableRows = page.locator('table#ticketsTable tbody tr');
    this.claimButton = page.locator('button:has-text("Tiếp nhận xử lý")');
    this.resolveButton = page.locator('button:has-text("Đã giải quyết")');
    this.statusBadge = page.locator('.badge-ticket-status');
    this.slaBadge = page.locator('.badge-sla-deadline');
  }

  async gotoCreateTicket() {
    await this.page.goto('/tickets/create');
  }

  async createGuestTicket(name: string, email: string, deptName: string, title: string, desc: string, priority: string = 'MEDIUM') {
    await this.gotoCreateTicket();
    if (await this.guestNameInput.isVisible()) {
      await this.guestNameInput.fill(name);
      await this.guestEmailInput.fill(email);
    }
    await this.departmentSelect.selectOption({ label: deptName });
    await this.prioritySelect.selectOption(priority);
    await this.titleInput.fill(title);
    await this.descriptionInput.fill(desc);
    await this.submitButton.click();
    await this.page.waitForLoadState('networkidle');
  }

  async gotoStaffDashboard() {
    await this.page.goto('/staff/tickets');
  }

  async claimFirstTicket() {
    await this.ticketTableRows.first().locator('a.btn-view-ticket').click();
    await this.claimButton.click();
    await this.page.waitForLoadState('networkidle');
  }

  async verifySlaBadge(expectedText: string) {
    await expect(this.slaBadge).toContainText(expectedText);
  }
}
