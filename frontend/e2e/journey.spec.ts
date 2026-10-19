import { test, expect, Page } from "@playwright/test";
async function login(p: Page, email = "operator@seatlock.local") {
  await p.goto("/");
  await p.getByLabel("Email", { exact: true }).fill(email);
  await p.getByLabel("Password", { exact: true }).fill("SeatLock-demo-2026!");
  await p.getByRole("button", { name: "Enter SeatLock" }).click();
  await expect(p.getByRole("button", { name: "Sign out" })).toBeVisible();
}
async function create(p: Page) {
  const session = await p.evaluate(() =>
    JSON.parse(sessionStorage.getItem("seatlock")!),
  );
  const res = await p.request.post("/api/ops/events", {
    headers: { Authorization: "Bearer " + session.token },
    data: {
      title: "Browser " + Date.now(),
      description: "Isolated browser verification",
      rows: 3,
      cols: 4,
      price: 2500,
    },
  });
  expect(res.ok()).toBeTruthy();
  const { id } = await res.json();
  await p.reload();
  await p.locator(".event-card").filter({ hasText: "Browser " }).last().click();
  return { id, session };
}
test("seat coordinates, hold, checkout, persistent booking, desktop and mobile", async ({
  page,
}) => {
  const consoleErrors: string[] = [];
  page.on("pageerror", (e) => consoleErrors.push(e.message));
  await login(page);
  await create(page);
  const seat = page.getByRole("button", { name: "C4 AVAILABLE", exact: true });
  await expect(seat).toBeEnabled();
  await seat.click();
  await expect(seat).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: "Hold selected seats" }).click();
  await expect(
    page.getByRole("button", { name: "C4 Your hold", exact: true }),
  ).toBeVisible();
  await page.reload();
  await page
    .locator(".event-card")
    .filter({ hasText: "Browser " })
    .last()
    .click();
  await expect(
    page.getByRole("button", { name: "C4 Your hold", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Complete simulated checkout", exact: true })
    .click();
  await expect(
    page.getByText("Booking confirmed", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "My bookings", exact: true }).click();
  await expect(
    page.locator(".booking").filter({ hasText: "C4" }),
  ).toBeVisible();
  await page.reload();
  await page.getByRole("button", { name: "My bookings", exact: true }).click();
  await expect(
    page.locator(".booking").filter({ hasText: "C4" }),
  ).toBeVisible();
  expect(consoleErrors).toEqual([]);
});
test("stale seat conflict and explicit release", async ({ page, browser }) => {
  await login(page);
  const { id } = await create(page);
  await page.getByRole("button", { name: "A1 AVAILABLE", exact: true }).click();
  const ctx = await browser.newContext();
  const other = await ctx.newPage();
  await login(other, "user@seatlock.local");
  const sess = await other.evaluate(() =>
    JSON.parse(sessionStorage.getItem("seatlock")!),
  );
  const res = await other.request.post("/api/holds", {
    headers: { Authorization: "Bearer " + sess.token },
    data: { eventId: id, seats: ["A1"] },
  });
  expect(res.ok()).toBeTruthy();
  const hold = await res.json();
  await page.getByRole("button", { name: "Hold selected seats" }).click();
  await expect(page.getByRole("alert")).toContainText("seat held");
  expect(
    (
      await other.request.delete("/api/holds/" + hold.id, {
        headers: { Authorization: "Bearer " + sess.token },
      })
    ).ok(),
  ).toBeTruthy();
  await ctx.close();
});
test("expiration UI rejects checkout", async ({ page }) => {
  test.skip(
    !process.env.SHORT_HOLD,
    "Run with HOLD_SECONDS=2 and SHORT_HOLD=1",
  );
  await login(page);
  await create(page);
  await page.getByRole("button", { name: "A1 AVAILABLE", exact: true }).click();
  await page.getByRole("button", { name: "Hold selected seats" }).click();
  await expect(
    page.getByText("Your hold has expired. Select seats again."),
  ).toBeVisible({ timeout: 10000 });
  await expect(
    page.getByRole("button", {
      name: "Complete simulated checkout",
      exact: true,
    }),
  ).toHaveCount(0);
});
test("responsive application screenshots", async ({ page }) => {
  await login(page);
  await page.setViewportSize({ width: 1600, height: 1100 });
  await expect(page.getByRole("button", { name: /A1 / }).first()).toBeVisible();
  await expect(page.locator(".connection")).toContainText("Live");
  await page.getByRole("button", { name: "C4 AVAILABLE", exact: true }).click();
  await page.getByRole("button", { name: "C5 AVAILABLE", exact: true }).click();
  await page.screenshot({
    path: "../docs/screenshots/desktop.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 768, height: 1024 });
  await page.screenshot({
    path: "../docs/screenshots/tablet.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: "../docs/screenshots/mobile.png",
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
});
test("operator lab renders verified persisted numeric results", async ({
  page,
}) => {
  await login(page);
  await page.getByRole("button", { name: "Load Test", exact: true }).click();
  await page
    .getByRole("button", { name: "Run experiment", exact: true })
    .click();
  const job = page.locator(".job").first();
  await expect(job).toContainText("VERIFIED", { timeout: 20000 });
  await expect(
    job
      .locator(".metrics>div")
      .filter({ hasText: "confirmed Bookings" })
      .locator("strong"),
  ).toHaveText("1");
  await expect(
    job
      .locator(".metrics>div")
      .filter({ hasText: "invariant Violations" })
      .locator("strong"),
  ).toHaveText("0");
});
