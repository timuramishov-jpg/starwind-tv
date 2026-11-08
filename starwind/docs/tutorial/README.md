# Исходник PDF-учебника

`tutorial.html` → `../Starwind-tutorial.pdf`. Пересобрать (нужны Node и Playwright с Chromium):

```bash
cd starwind/docs/tutorial
ln -sf ../img img
cp ../../../launcher/app/src/main/res/font/sf_*.ttf .   # шрифты SF (в git их нет)
node render.js                                           # даёт a.pdf (без номеров) и b.pdf (с номерами)
python3 -c "from pypdf import PdfReader,PdfWriter;a,b=PdfReader('a.pdf'),PdfReader('b.pdf');w=PdfWriter();w.add_page(a.pages[0]);[w.add_page(p) for p in b.pages[1:]];w.write('../Starwind-tutorial.pdf')"
```

Снимки телевизора — из `tv smoke` (`~/Documents/Claude/smoke/<время>/`), уменьшить до 1280×720 и положить в `../img`.
