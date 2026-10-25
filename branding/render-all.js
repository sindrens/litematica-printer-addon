// node render-all.js a.svg b.svg ... : each to the same name .png at 1024x1024
const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1024, height: 1024 }, deviceScaleFactor: 1 });
  for (const file of process.argv.slice(2)) {
    const svg = fs.readFileSync(file, 'utf8');
    await page.setContent(`<html><body style="margin:0;background:transparent">${svg}</body></html>`);
    await page.waitForTimeout(100);
    await page.screenshot({ path: file.replace(/\.svg$/, '.png'), omitBackground: true, clip: { x: 0, y: 0, width: 1024, height: 1024 } });
  }
  await browser.close();
  console.log('rendered', process.argv.length - 2);
})();
