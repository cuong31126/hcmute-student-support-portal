import { Page, Locator, expect } from '@playwright/test';

export class AiAssistantModal {
  readonly page: Page;
  readonly chatWidgetButton: Locator;
  readonly chatInput: Locator;
  readonly sendButton: Locator;
  readonly messagesContainer: Locator;
  readonly lastAiMessage: Locator;
  readonly fallbackTicketPromptButton: Locator;
  readonly ticketModal: Locator;
  readonly guestEmailInput: Locator;
  readonly submitTicketModalButton: Locator;

  constructor(page: Page) {
    this.page = page;
    this.chatWidgetButton = page.locator('#btn-open-ai-chat, button[aria-label="Open AI Chat"]');
    this.chatInput = page.locator('#ai-chat-input');
    this.sendButton = page.locator('#btn-send-ai-message');
    this.messagesContainer = page.locator('#ai-chat-messages');
    this.lastAiMessage = page.locator('.ai-message-bubble').last();
    this.fallbackTicketPromptButton = page.locator('button.btn-convert-to-ticket');
    this.ticketModal = page.locator('#ticketCreationModal');
    this.guestEmailInput = page.locator('#modalGuestEmail');
    this.submitTicketModalButton = page.locator('#btnSubmitModalTicket');
  }

  async openChat() {
    if (!(await this.chatInput.isVisible())) {
      await this.chatWidgetButton.click();
    }
  }

  async sendMessage(question: string) {
    await this.openChat();
    await this.chatInput.fill(question);
    await this.sendButton.click();
  }

  async expectAiAnswerContaining(textSnippet: string) {
    await expect(this.lastAiMessage).toContainText(textSnippet, { timeout: 15000 });
  }

  async convertToTicket(guestEmail?: string) {
    await this.fallbackTicketPromptButton.click();
    await expect(this.ticketModal).toBeVisible();
    if (guestEmail) {
      await this.guestEmailInput.fill(guestEmail);
    }
    await this.submitTicketModalButton.click();
    await this.page.waitForLoadState('networkidle');
  }
}
