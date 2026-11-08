const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const p = await b.newPage();
  await p.goto('file://' + __dirname + '/tutorial.html', { waitUntil: 'networkidle' });
  await p.evaluate(() => document.fonts.ready);
  const o = { format: 'A4', printBackground: true, preferCSSPageSize: true };
  await p.pdf({ ...o, path: __dirname + '/a.pdf' });
  await p.pdf({ ...o, path: __dirname + '/b.pdf', displayHeaderFooter: true, headerTemplate: '<span></span>',
    footerTemplate: '<div style="width:100%;font-size:7.5pt;color:#8e8e93;padding:0 16mm;display:flex;justify-content:space-between;font-family:sans-serif"><span>Starwind TV — учебник с нуля</span><span class="pageNumber"></span></div>' });
  await b.close();
})();
