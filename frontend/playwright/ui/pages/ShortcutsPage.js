import { expect } from "@playwright/test";
import { BaseWebSocketPage } from "./BaseWebSocketPage";

export class ShortcutsPage extends BaseWebSocketPage {
  static async init(page) {
    await super.init(page);

    await super.mockRPCs(page, {
      "get-profile": "logged-in-user/get-profile-logged-in.json",
      "get-teams": "logged-in-user/get-teams-default.json",
      "update-profile-props":
        "logged-in-user/update-profile-with-shortcuts.json",
    });
  }

  static async initWithShortcuts(page) {
    await super.init(page);

    await super.mockRPCs(page, {
      "get-profile": "logged-in-user/get-profile-logged-in.json",
      "get-teams": "logged-in-user/get-teams-default.json",
      "update-profile-props":
        "logged-in-user/update-profile-with-shortcuts.json",
    });
  }

  static async initWithNoShortcuts(page) {
    await super.init(page);

    await super.mockRPCs(page, {
      "get-profile": "logged-in-user/get-profile-no-shortcuts.json",
      "get-teams": "logged-in-user/get-teams-default.json",
      "update-profile-props":
        "logged-in-user/update-profile-with-shortcuts.json",
    });
  }

  constructor(page) {
    super(page);

    this.shortcutsSection = page.getByRole("region", { name: /shortcuts/i });
    this.searchInput = page.getByRole("textbox", { name: /shortcuts/i });

    this.allTab = page.getByRole("tab", { name: "All" });
    this.personalizedTab = page.getByRole("tab", { name: "Personalized" });
    this.disabledTab = page.getByRole("tab", { name: "Disabled" });

    this.restoreAllButton = page.getByRole("button", {
      name: /restore all/i,
    });

    this.importExportButton = page.getByRole("button", {
      name: /import\/export/i,
    });

    this.fileInput = page.locator('input[type="file"]');
  }

  async goToShortcuts() {
    await this.page.goto("#/settings/shortcuts");
    await expect(this.shortcutsSection).toBeVisible();
  }

  async searchForShortcut(term) {
    await this.searchInput.fill(term);
  }

  async clearSearch() {
    await this.searchInput.clear();
  }

  async clickTab(tabName) {
    const tab = this.page.getByRole("tab", { name: tabName });
    await tab.click();
  }


  async getShortcutRow(shortcutName) {
    return this.page.getByRole("button", { name: new RegExp(`Edit ${shortcutName}`, "i") });
  }

  async expandSubsection(subsectionName) {
    const subsectionButton = this.page.getByRole('button', { name: subsectionName });
    await subsectionButton.click();
  }

  async clickEditShortcut(shortcutName) {
    const button = this.page.getByRole("button", { name: new RegExp(`Edit ${shortcutName}`, "i") });
    await button.click();
  }

  async pressKey(key) {
    await this.page.keyboard.press(key);
  }

  async pressKeyCombo(keys) {
    for (const key of keys) {
      await this.page.keyboard.down(key);
    }
    for (const key of [...keys].reverse()) {
      await this.page.keyboard.up(key);
    }
  }

  async saveShortcut() {
    const saveButton = this.page.getByRole("button", { name: /save/i });
    await saveButton.click();
  }

  async cancelEdit() {
    const cancelButton = this.page.getByRole("button", { name: /cancel/i });
    await cancelButton.click();
  }

  async resetShortcut(shortcutName) {
    const row = await this.getShortcutRow(shortcutName);
    const resetButton = row.getByRole("button", { name: /reset/i });
    await resetButton.click();
  }

  async disableShortcut(shortcutName) {
    const row = await this.getShortcutRow(shortcutName);
    const disableButton = row.getByRole("button", { name: /disable/i });
    await disableButton.click();
  }

  async expectShortcutCustomized(shortcutName) {
    const row = await this.page.getByRole('listitem', { name: new RegExp(`^${shortcutName}$`, 'i') });
    await expect(row).toHaveAttribute("data-customized", "true");
  }

  async expectShortcutNotCustomized(shortcutName) {
    const row = await this.getShortcutRow(shortcutName);
    await expect(row).not.toHaveAttribute("data-customized", "true");
  }

  async expectShortcutHasConflict(shortcutName) {
    const row = await this.getShortcutRow(shortcutName);
    await expect(row).toHaveAttribute("data-conflict", "true");
  }

  async expectShortcutVisible(shortcutName) {
    const row = await this.getShortcutRow(shortcutName);
    await expect(row).toBeVisible();
  }

  async expectShortcutHidden(shortcutName) {
    const row = await this.getShortcutRow(shortcutName);
    await expect(row).not.toBeVisible();
  }

  async restoreAllShortcuts() {
    await this.restoreAllButton.click();
    const confirmButton = this.page.getByRole("button", {
      name: /confirm/i,
    });
    await confirmButton.click();
  }

  async exportShortcuts() {
    await this.importExportButton.click();
    const exportButton = this.page.getByRole("menuitem", { name: /export/i });
    await exportButton.click();
  }

  async importShortcuts(jsonData) {
    await this.importExportButton.click();
    const importButton = this.page.getByRole("menuitem", { name: /import$/i });
    await importButton.click();

    const buffer = Buffer.from(JSON.stringify(jsonData), "utf-8");
    await this.fileInput.setInputFiles({
      name: "shortcuts.json",
      mimeType: "application/json",
      buffer,
    });
  }
}

export default ShortcutsPage;
