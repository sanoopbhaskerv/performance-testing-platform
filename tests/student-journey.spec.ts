import { test, expect } from '@playwright/test';

test('Student Web Performance Journey', async ({ page }) => {
  const startTime = Date.now();

  // Navigate to Student UI
  await page.goto('http://localhost:5174/');

  // Collect Core Web Vitals (LCP, FCP, Navigation Timing)
  const metrics = await page.evaluate(() => {
    const nav = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming;
    const paint = performance.getEntriesByType('paint');
    const fcp = paint.find(p => p.name === 'first-contentful-paint')?.startTime || 0;
    return {
      dnsTime: nav ? nav.domainLookupEnd - nav.domainLookupStart : 0,
      connectTime: nav ? nav.connectEnd - nav.connectStart : 0,
      domContentLoaded: nav ? nav.domContentLoadedEventEnd - nav.startTime : 0,
      loadEvent: nav ? nav.loadEventEnd - nav.startTime : 0,
      firstContentfulPaintMs: Math.round(fcp),
    };
  });

  // Fill Create Student form
  await page.getByRole('textbox', { name: 'First name' }).fill('Sanoop');
  await page.getByRole('textbox', { name: 'Last name' }).fill('Test');
  await page.getByRole('textbox', { name: 'Email' }).fill(`student.${Date.now()}@example.com`);
  await page.getByRole('textbox', { name: 'Program' }).fill('Computer Science');

  // Measure POST API Performance
  const postStart = Date.now();
  await Promise.all([
    page.waitForResponse(r => r.url().includes('/api/students') && (r.status() === 200 || r.status() === 201)),
    page.getByRole('button', { name: 'Create student' }).click()
  ]);
  const postDuration = Date.now() - postStart;

  // Edit Student
  await page.getByRole('button', { name: 'Edit student' }).first().click();
  await page.getByRole('textbox', { name: 'Last name' }).fill('UpdatedTest');

  // Measure PUT API Performance
  const putStart = Date.now();
  await Promise.all([
    page.waitForResponse(r => r.url().includes('/api/students') && r.status() === 200),
    page.getByRole('button', { name: 'Save changes' }).click()
  ]);
  const putDuration = Date.now() - putStart;

  const totalDuration = Date.now() - startTime;

  // Print Web Performance Report
  console.log('\n📊 --- WEB PERFORMANCE METRICS REPORT ---');
  console.log(`⏱️ DOM Content Loaded: ${Math.round(metrics.domContentLoaded)} ms`);
  console.log(`⏱️ First Contentful Paint (FCP): ${metrics.firstContentfulPaintMs} ms`);
  console.log(`⏱️ Page Load Time: ${Math.round(metrics.loadEvent)} ms`);
  console.log(`🚀 POST /api/students Latency: ${postDuration} ms`);
  console.log(`🚀 PUT /api/students Latency: ${putDuration} ms`);
  console.log(`🏁 Total End-to-End Journey Duration: ${totalDuration} ms`);
  console.log('-----------------------------------------\n');

  expect(totalDuration).toBeLessThan(10000);
});
