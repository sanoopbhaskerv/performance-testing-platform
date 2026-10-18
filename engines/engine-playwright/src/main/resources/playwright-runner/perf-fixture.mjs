/** Harness hooks. No test actions are generated: only profile control and lab measurements. */
export async function beforeBrowserJourney({ context, page }) {
  await context.addInitScript(() => {
    const values = { lcp: null, cls: null, windowScore: 0, windowStart: 0, lastShift: 0 };
    globalThis.__platformPerformance = values;
    if (PerformanceObserver.supportedEntryTypes?.includes('largest-contentful-paint')) {
      new PerformanceObserver(list => { for (const e of list.getEntries()) values.lcp = e.startTime; })
        .observe({ type: 'largest-contentful-paint', buffered: true });
    }
    if (PerformanceObserver.supportedEntryTypes?.includes('layout-shift')) {
      values.cls = 0;
      new PerformanceObserver(list => {
        for (const e of list.getEntries()) {
          if (e.hadRecentInput) continue;
          if (e.startTime - values.lastShift > 1000 || e.startTime - values.windowStart > 5000) {
            values.windowStart = e.startTime; values.windowScore = 0;
          }
          values.windowScore += e.value; values.lastShift = e.startTime;
          values.cls = Math.max(values.cls, values.windowScore);
        }
      }).observe({ type: 'layout-shift', buffered: true });
    }
  });
  const profile = JSON.parse(process.env.PERF_BROWSER_PROFILE || '{}');
  const session = await context.newCDPSession(page);
  const networks = {
    'slow-4g': { latency: 150, downloadThroughput: 200000, uploadThroughput: 93750 },
    'fast-4g': { latency: 40, downloadThroughput: 500000, uploadThroughput: 375000 },
    wifi: { latency: 2, downloadThroughput: 3750000, uploadThroughput: 1875000 },
  };
  if (networks[profile.network]) {
    await session.send('Network.enable');
    await session.send('Network.emulateNetworkConditions', { offline: false, ...networks[profile.network] });
  }
  await session.send('Emulation.setCPUThrottlingRate', { rate: Number(profile.cpuThrottle || 1) });
}

export async function afterBrowserJourney({ page }, testInfo) {
  if (page.isClosed()) return;
  const metrics = await page.evaluate(() => {
    const v = globalThis.__platformPerformance;
    const nav = performance.getEntriesByType('navigation')[0];
    return { lcp: v?.lcp ?? null, cls: v?.cls ?? null,
      ttfb: nav && nav.responseStart > 0 ? nav.responseStart - nav.requestStart : null };
  }).catch(() => null);
  if (metrics) await testInfo.attach('platform.web-metrics', { body: JSON.stringify(metrics), contentType: 'application/json' });
}
