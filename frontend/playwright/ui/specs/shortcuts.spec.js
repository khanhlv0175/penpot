import { test, expect } from "@playwright/test";
import ShortcutsPage from "../pages/ShortcutsPage";

test.beforeEach(async ({ page }) => {
  await ShortcutsPage.init(page);
});

test.describe("Shortcuts Settings Page", () => {
  test("Shortcuts page loads correctly", async ({ page }) => {
    const shortcutsPage = new ShortcutsPage(page);
    await shortcutsPage.goToShortcuts();

    await expect(shortcutsPage.shortcutsSection).toBeVisible();
    await expect(shortcutsPage.searchInput).toBeVisible();
  });

  test("Tabs are visible and clickable", async ({ page }) => {
    const shortcutsPage = new ShortcutsPage(page);
    await shortcutsPage.goToShortcuts();

    await expect(shortcutsPage.allTab).toBeVisible();
    await expect(shortcutsPage.personalizedTab).toBeVisible();
    await expect(shortcutsPage.disabledTab).toBeVisible();

    await shortcutsPage.clickTab("Personalized");
    await expect(shortcutsPage.personalizedTab).toHaveAttribute(
      "aria-selected",
      "true",
    );
    const personalizedPlacehonder = page.getByText(/Head to All to start/i);
    await expect(personalizedPlacehonder).toBeVisible();

    await shortcutsPage.clickTab("Disabled");
    await expect(shortcutsPage.disabledTab).toHaveAttribute(
      "aria-selected",
      "true",
    );
    const disabledPlaceholder = page.getByText(/There are not disabled/i);
    await expect(disabledPlaceholder).toBeVisible();

    await shortcutsPage.clickTab("All");
    await expect(shortcutsPage.allTab).toHaveAttribute("aria-selected", "true");
  });

});

test.describe("Shortcut Customization", () => {
  test("User can edit a shortcut", async ({ page }) => {
    const shortcutsPage = new ShortcutsPage(page);
    await shortcutsPage.goToShortcuts();

    // Expand subsection 
    await shortcutsPage.expandSubsection("Alignment");

    // Start edition of the shortcut
    await shortcutsPage.clickEditShortcut("Align bottom");

    // Press a new key combination
    await shortcutsPage.pressKey("Control+y");

    // Save the shortcut
    await shortcutsPage.saveShortcut();

    // Verify the shortcut is now customized
    await shortcutsPage.expectShortcutCustomized("Align bottom");
  });

});
