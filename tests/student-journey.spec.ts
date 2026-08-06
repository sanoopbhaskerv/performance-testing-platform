import { test, expect } from '@playwright/test';

test('Student CRUD Journey', async ({ page }) => {
  await page.goto('http://localhost:5174/');

  // Fill Create Student form
  await page.getByRole('textbox', { name: 'First name' }).click();
  await page.getByRole('textbox', { name: 'First name' }).fill('sanoop');
  await page.getByRole('textbox', { name: 'First name' }).press('Tab');
  await page.getByRole('textbox', { name: 'Last name' }).fill('test');
  await page.getByRole('textbox', { name: 'Last name' }).press('Tab');
  await page.getByRole('textbox', { name: 'Email' }).fill(`test.${Date.now()}@gmail.com`);
  await page.getByRole('textbox', { name: 'Program' }).click();
  await page.getByRole('textbox', { name: 'Program' }).fill('Btech');
  // Submit form and wait for POST /api/students response
  await Promise.all([
    page.waitForResponse(response => response.url().includes('/api/students') && (response.status() === 200 || response.status() === 201)),
    page.getByRole('button', { name: 'Create student' }).click()
  ]);

  // Edit Student
  await page.getByRole('button', { name: 'Edit student' }).first().click();
  await page.getByRole('textbox', { name: 'Last name' }).click();
  await page.getByRole('textbox', { name: 'Last name' }).fill('test1222');

  // Save changes and wait for PUT /api/students response
  await Promise.all([
    page.waitForResponse(response => response.url().includes('/api/students') && response.status() === 200),
    page.getByRole('button', { name: 'Save changes' }).click()
  ]);
});
