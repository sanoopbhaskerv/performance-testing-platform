import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  workers: 10,      // 10 Concurrent Real Browser Instances
  repeatEach: 2,    // 20 Total Browser Journeys
  timeout: 30000,
  use: {
    baseURL: 'http://localhost:5174',
    headless: true,
    viewport: { width: 1280, height: 720 },
  },
  projects: [
    {
      name: 'chromium-load-cohort',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
