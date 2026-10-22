import {defineConfig, devices} from '@playwright/test';

const previewUrl = `http://127.0.0.1:${Number(process.env.PREVIEW_PORT) || 4173}`;

export default defineConfig({
  testDir: 'src/test/browser',
  timeout: 30_000,
  expect: {timeout: 5_000},
  fullyParallel: false,
  workers: 1,
  reporter: process.env.CI ? 'github' : 'line',
  use: {
    baseURL: previewUrl,
    ...devices['Desktop Chrome'],
    permissions: ['clipboard-read', 'clipboard-write'],
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'node tools/serve-preview.mjs',
    url: `${previewUrl}/tools/ui-preview.html`,
    reuseExistingServer: !process.env.CI && !process.env.CODEDECK_PREVIEW_BUNDLE,
    timeout: 60_000,
  },
  projects: [{name: 'chromium', use: {browserName: 'chromium'}}],
});
