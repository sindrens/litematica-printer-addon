// node render-pages.js <dir>: renders every "<name> <w> <h>" of <dir>/pages.txt from <dir>/<name>.html to <dir>/<name>.png
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
(async () => {
  const dir = path.resolve(process.argv[2]);
  const browser = await chromium.launch();
  for (const line of fs.readFileSync(path.join(dir, 'pages.txt'), 'utf8').trim().split('\n')) {
    const [name, w, h] = line.split(' ');
    const page = await browser.newPage({ viewport: { width: +w, height: +h }, deviceScaleFactor: 1 });
    await page.goto('file://' + path.join(dir, name + '.html'));
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(150);
    await page.screenshot({ path: path.join(dir, name + '.png'), clip: { x: 0, y: 0, width: +w, height: +h } });
    await page.close();
  }
  await browser.close();
  console.log('done');
})();
