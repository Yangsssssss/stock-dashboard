/* QuantBoard 共享工具 & Canvas 图表 —— index.html 与 stock.html 共用
   仅放纯函数（格式化 / 颜色 / 绘图），不依赖任何页面的 DOM 结构。 */
'use strict';

const CSSV = () => getComputedStyle(document.documentElement);
const col = (n) => CSSV().getPropertyValue(n).trim();

function fmt(v, d) {
  if (v === null || v === undefined || isNaN(v)) return '—';
  return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: d, maximumFractionDigits: d });
}
function px(v) {
  if (v === null || v === undefined || isNaN(v)) return '—';
  const d = Math.abs(v) < 10 ? 3 : 2;
  return fmt(v, d);
}
function pctTxt(v) {
  if (v === null || v === undefined || isNaN(v)) return '—';
  return (v > 0 ? '+' : '') + Number(v).toFixed(2) + '%';
}
function cls(v) { return v > 0 ? 'up' : v < 0 ? 'dn' : 'flat'; }
function curSym(c) { return c === 'HKD' ? 'HK$' : c === 'USD' ? '$' : '¥'; }
function amtTxt(v) {
  if (v === null || v === undefined || !v) return '—';
  if (v >= 10000) return fmt(v / 10000, 2) + ' 万亿';
  if (v >= 1) return fmt(v, v >= 100 ? 0 : 1) + ' 亿';
  return fmt(v * 10000, 0) + ' 万';
}
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

function scoreColor(v) {
  if (v >= 72) return col('--up');
  if (v >= 58) return '#e8734a';
  if (v >= 44) return col('--txt-3');
  if (v >= 30) return '#3aa981';
  return col('--dn');
}

/* ---------------------------------------------------------------- 画布 */
function hdpi(canvas) {
  const r = window.devicePixelRatio || 1;
  const w = canvas.clientWidth, h = canvas.clientHeight;
  if (canvas.width !== w * r || canvas.height !== h * r) {
    canvas.width = w * r; canvas.height = h * r;
  }
  const ctx = canvas.getContext('2d');
  ctx.setTransform(r, 0, 0, r, 0, 0);
  return { ctx, w, h };
}

function drawKline(canvas, d) {
  const { ctx, w, h } = hdpi(canvas);
  ctx.clearRect(0, 0, w, h);
  const bars = d.bars || [];
  if (!bars.length) { ctx.fillStyle = col('--txt-3'); ctx.fillText('暂无K线数据', 10, 20); return; }

  const up = col('--up'), dn = col('--dn'), grid = col('--grid'), t3 = col('--txt-3');
  const padL = 4, padR = 46, padT = 8;
  const axH = 16;                       // 底部时间轴预留高度
  const volH = h * 0.2, gap = 14;
  const priceH = h - padT - volH - gap - axH;
  const n = bars.length;
  const maKeys = [['ma5', '#f0a63d'], ['ma10', '#5b8def'], ['ma20', '#b26cf0'], ['ma60', '#8b93a3']];

  let lo = Infinity, hi = -Infinity;
  bars.forEach((b) => { lo = Math.min(lo, +b[4]); hi = Math.max(hi, +b[3]); });
  maKeys.forEach(([k]) => (d[k] || []).forEach((v) => { if (v != null) { lo = Math.min(lo, v); hi = Math.max(hi, v); } }));
  const rng = hi - lo || 1; lo -= rng * 0.04; hi += rng * 0.04;

  const iw = (w - padL - padR) / n;
  const X = (i) => padL + iw * (i + 0.5);
  const Y = (v) => padT + (1 - (v - lo) / (hi - lo)) * priceH;

  ctx.font = '10px -apple-system, sans-serif';
  ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
  for (let g = 0; g <= 4; g++) {
    const yy = padT + (priceH / 4) * g;
    ctx.strokeStyle = grid; ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(padL, yy + .5); ctx.lineTo(w - padR, yy + .5); ctx.stroke();
    const p = hi - ((hi - lo) / 4) * g;
    ctx.fillStyle = t3;
    ctx.fillText(p >= 1000 ? fmt(p, 0) : p >= 10 ? fmt(p, 2) : fmt(p, 3), w - padR + 5, yy);
  }

  const bw = Math.max(1, Math.min(9, iw * 0.62));
  bars.forEach((b, i) => {
    const [o, c] = [+b[1], +b[2]];
    const cUp = c >= o;
    ctx.strokeStyle = ctx.fillStyle = cUp ? up : dn;
    ctx.lineWidth = 1;
    const x = X(i);
    ctx.beginPath(); ctx.moveTo(x, Y(+b[3])); ctx.lineTo(x, Y(+b[4])); ctx.stroke();
    const y1 = Y(Math.max(o, c)), y2 = Y(Math.min(o, c));
    const bh = Math.max(1, y2 - y1);
    if (cUp) { ctx.fillStyle = up; ctx.fillRect(x - bw / 2, y1, bw, bh); }
    else { ctx.fillStyle = dn; ctx.fillRect(x - bw / 2, y1, bw, bh); }
  });

  maKeys.forEach(([k, color]) => {
    const arr = d[k] || [];
    ctx.beginPath(); let started = false;
    arr.forEach((v, i) => {
      if (v == null || i >= n) return;
      const xx = X(i), yy = Y(v);
      if (!started) { ctx.moveTo(xx, yy); started = true; } else ctx.lineTo(xx, yy);
    });
    ctx.strokeStyle = color; ctx.lineWidth = 1.15; ctx.stroke();
  });

  // 区间最高 / 最低点标注（跟随所在 K 线，防出界、防与时间轴重叠）
  let hiIdx = 0, loIdx = 0;
  bars.forEach((b, i) => {
    if (+b[3] > +bars[hiIdx][3]) hiIdx = i;
    if (+b[4] < +bars[loIdx][4]) loIdx = i;
  });
  const fpx = (v) => (v >= 1000 ? fmt(v, 0) : v >= 10 ? fmt(v, 2) : fmt(v, 3));
  ctx.textAlign = 'center';
  const markExtreme = (i, val, isHigh) => {
    const x = Math.min(w - padR - 28, Math.max(padL + 28, X(i)));
    const y = Y(val);
    ctx.fillStyle = isHigh ? up : dn;
    // 小三角指向极值点
    const ty = isHigh ? y - 5 : y + 5;
    ctx.beginPath();
    if (isHigh) { ctx.moveTo(x - 3, ty); ctx.lineTo(x + 3, ty); ctx.lineTo(x, ty + 4); }
    else { ctx.moveTo(x - 3, ty); ctx.lineTo(x + 3, ty); ctx.lineTo(x, ty - 4); }
    ctx.closePath(); ctx.fill();
    // 价格文字：最高在上方、最低在下方
    const txtY = isHigh ? Math.max(y - 9, padT + 9) : Math.min(y + 15, padT + priceH - 3);
    ctx.fillText((isHigh ? '最高 ' : '最低 ') + fpx(val), x, txtY);
  };
  markExtreme(hiIdx, +bars[hiIdx][3], true);
  markExtreme(loIdx, +bars[loIdx][4], false);

  const vmax = Math.max(...bars.map((b) => +b[5])) || 1;
  const vTop = padT + priceH + gap;
  bars.forEach((b, i) => {
    const cUp = +b[2] >= +b[1];
    const bh = (+b[5] / vmax) * volH;
    ctx.fillStyle = (cUp ? up : dn) + '66';
    ctx.fillRect(X(i) - bw / 2, vTop + volH - bh, bw, bh);
  });

  const last = +bars[n - 1][2];
  const ly = Y(last);
  const lc = +bars[n - 1][2] >= +bars[n - 1][1] ? up : dn;
  ctx.fillStyle = lc;
  ctx.fillRect(w - padR + 2, ly - 7, padR - 4, 14);
  ctx.fillStyle = '#fff'; ctx.textAlign = 'center';
  ctx.fillText(last >= 1000 ? fmt(last, 0) : last >= 10 ? fmt(last, 2) : fmt(last, 3), w - padR + 2 + (padR - 4) / 2, ly);
  ctx.textAlign = 'left';

  // 横轴时间刻度：按数据跨度自适应——跨多年标年份、跨多月的月界、其余均匀间隔
  const spanDays = n > 1 ? (new Date(bars[n - 1][0]) - new Date(bars[0][0])) / 86400000 : 0;
  const byYear = spanDays > 550, byMonth = spanDays > 150;
  const step = Math.max(1, Math.round(n / 7));
  ctx.fillStyle = t3;
  ctx.textAlign = 'center'; ctx.textBaseline = 'alphabetic';
  const y0 = vTop + volH;
  let lastX = -99;
  for (let i = 0; i < n; i++) {
    const ds = String(bars[i][0]);
    const boundary = byYear
      ? (i === 0 || ds.slice(0, 4) !== String(bars[i - 1][0]).slice(0, 4))
      : byMonth
        ? (i === 0 || ds.slice(0, 7) !== String(bars[i - 1][0]).slice(0, 7))
        : i % step === 0;
    if (!boundary) continue;
    const x = X(i);
    if (x - lastX < 36) continue;   // 防标签重叠，丢弃过密刻度
    lastX = x;
    const label = byYear ? ds.slice(0, 4) : byMonth ? ds.slice(2, 7) : ds.slice(5);
    ctx.fillText(label, x, Math.min(y0 + 12, h - 3));
    ctx.strokeStyle = grid;
    ctx.beginPath(); ctx.moveTo(x + .5, y0); ctx.lineTo(x + .5, y0 + 4); ctx.stroke();
  }
  ctx.textAlign = 'left';
}

/* 盘中分时图：1 分钟价格线 + 昨收基准虚线 + 渐变面积 + 时间刻度。 */
function drawTrend(canvas, d) {
  const { ctx, w, h } = hdpi(canvas);
  ctx.clearRect(0, 0, w, h);
  const prices = (d && d.prices) || [];
  const times = (d && d.times) || [];
  const pre = +((d && d.preClose) || 0);
  if (prices.length < 2) {
    ctx.fillStyle = col('--txt-3'); ctx.font = '12px -apple-system, sans-serif';
    ctx.fillText('暂无盘中分时（未开盘或该品种不支持）', 12, 24);
    return;
  }
  const up = col('--up'), dn = col('--dn'), grid = col('--grid'), t3 = col('--txt-3');
  const padL = 4, padR = 46, padT = 8, padB = 20;
  const fpx = (v) => (v >= 1000 ? fmt(v, 0) : v >= 10 ? fmt(v, 2) : fmt(v, 3));
  let lo = Math.min(...prices), hi = Math.max(...prices);
  if (pre > 0) { lo = Math.min(lo, pre); hi = Math.max(hi, pre); }
  const rng = hi - lo || Math.abs(hi) * 0.002 || 1;
  lo -= rng * 0.08; hi += rng * 0.08;
  const iw = w - padL - padR, ph = h - padT - padB;
  const X = (i) => padL + (i / (prices.length - 1)) * iw;
  const Y = (v) => padT + (1 - (v - lo) / (hi - lo)) * ph;
  const c = prices[prices.length - 1] >= (pre > 0 ? pre : prices[0]) ? up : dn;

  // 网格 + 右侧价格轴（高/低/昨收）
  ctx.font = '10px -apple-system, sans-serif';
  ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
  [hi, pre > 0 ? pre : (hi + lo) / 2, lo].forEach((v) => {
    const yy = Y(v);
    ctx.strokeStyle = grid; ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(padL, yy + .5); ctx.lineTo(w - padR, yy + .5); ctx.stroke();
    ctx.fillStyle = t3;
    ctx.fillText(fpx(v), w - padR + 5, yy);
  });

  // 昨收基准虚线（浅色主题下用中性色，避免与涨跌线混淆）
  if (pre > 0 && pre > lo && pre < hi) {
    ctx.setLineDash([4, 4]); ctx.strokeStyle = t3; ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(padL, Y(pre) + .5); ctx.lineTo(w - padR, Y(pre) + .5); ctx.stroke();
    ctx.setLineDash([]);
  }

  // 面积 + 价格线
  const g = ctx.createLinearGradient(0, padT, 0, padT + ph);
  g.addColorStop(0, c + '33'); g.addColorStop(1, c + '00');
  ctx.beginPath(); ctx.moveTo(X(0), Y(prices[0]));
  prices.forEach((v, i) => ctx.lineTo(X(i), Y(v)));
  ctx.lineTo(X(prices.length - 1), padT + ph); ctx.lineTo(X(0), padT + ph); ctx.closePath();
  ctx.fillStyle = g; ctx.fill();
  ctx.beginPath(); ctx.moveTo(X(0), Y(prices[0]));
  prices.forEach((v, i) => ctx.lineTo(X(i), Y(v)));
  ctx.strokeStyle = c; ctx.lineWidth = 1.3; ctx.lineJoin = 'round'; ctx.stroke();

  // 横轴时间刻度：均匀 5 档
  ctx.fillStyle = t3; ctx.textAlign = 'center'; ctx.textBaseline = 'alphabetic';
  const n = prices.length;
  [0, 0.25, 0.5, 0.75, 1].forEach((f) => {
    const i = Math.min(n - 1, Math.round(f * (n - 1)));
    const label = times[i] || '';
    if (label) ctx.fillText(label, X(i), h - 6);
  });
  ctx.textAlign = 'left';
}

function drawHistory(canvas, pts) {
  const { ctx, w, h } = hdpi(canvas);
  ctx.clearRect(0, 0, w, h);
  if (!pts || pts.length < 2) return;
  const t3 = col('--txt-3'), grid = col('--grid');
  const padL = 2, padR = 26, padT = 6, padB = 6;
  const H = h - padT - padB;
  const sc = pts.map((p) => +p.score);
  const x = (i) => padL + (i / (pts.length - 1)) * (w - padL - padR);
  const y = (v) => padT + (1 - v / 100) * H;

  ctx.font = '10px -apple-system, sans-serif';
  ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
  [0, 50, 100].forEach((v) => {
    const yy = y(v);
    ctx.strokeStyle = grid; ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(padL, yy + .5); ctx.lineTo(w - padR, yy + .5); ctx.stroke();
    ctx.fillStyle = t3; ctx.fillText(String(v), w - padR + 4, yy);
  });

  const g = ctx.createLinearGradient(0, padT, 0, padT + H);
  g.addColorStop(0, col('--accent') + '33'); g.addColorStop(1, col('--accent') + '00');
  ctx.beginPath(); ctx.moveTo(x(0), y(sc[0]));
  sc.forEach((v, i) => ctx.lineTo(x(i), y(v)));
  ctx.lineTo(x(sc.length - 1), padT + H); ctx.lineTo(x(0), padT + H); ctx.closePath();
  ctx.fillStyle = g; ctx.fill();
  ctx.beginPath(); ctx.moveTo(x(0), y(sc[0]));
  sc.forEach((v, i) => ctx.lineTo(x(i), y(v)));
  ctx.strokeStyle = col('--accent'); ctx.lineWidth = 1.4; ctx.lineJoin = 'round'; ctx.stroke();

  const lc = sc.length > 1 && sc[sc.length - 1] < sc[sc.length - 2] ? col('--dn') : col('--up');
  ctx.beginPath();
  ctx.arc(x(sc.length - 1), y(sc[sc.length - 1]), 2.6, 0, Math.PI * 2);
  ctx.fillStyle = lc; ctx.fill();
}
