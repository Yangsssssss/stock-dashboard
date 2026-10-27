/* QuantBoard 前端逻辑 —— 原生 JS，自绘 Canvas 图表 */
'use strict';

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];

const S = {
  data: null,
  view: 'overview',
  group: 'a',
  kind: 'all',
  sigFilter: 'all',
  sort: { key: 'score', dir: -1 },
  watch: new Set(),
  watchSel: new Set(),
  mgSel: new Set(),
  auto: true,
  timer: null,
  loading: false,
  port: null,
  ocrItems: [],
};

/* ---------------------------------------------------------------- 工具 */
/* 星标：自选状态写入 MySQL */
const starHTML = (code) =>
  `<span class="star ${S.watch.has(code) ? 'on' : ''}" data-star="${code}" title="加入 / 移出自选">★</span>`;

const delHTML = (code) =>
  `<span class="row-del" data-del="${code}" title="从看板移除该标的">✕</span>`;

/* 行级/抽屉删除共用：自定义直接删，内置标的弹样式化确认（可恢复） */
let cfPending = null;

function requestRemove(code) {
  let custom = false;
  let name = code;
  if (S.data) {
    for (const g of Object.values(S.data.groups)) {
      const it = g.items.find((x) => x.code === code);
      if (it) { custom = !!it.custom; name = it.name; break; }
    }
  }
  if (custom) { removeInstrument(code); return; }
  cfPending = code;
  $('#cfText').innerHTML = `<b>${esc(name)}</b>（${esc(code.toUpperCase())}）是内置标的，移出后可在"添加标的"弹窗中一键恢复。确定移除吗？`;
  $('#cfModal').classList.add('open');
  $('#cfMask').classList.add('show');
}

function closeConfirm() {
  cfPending = null;
  $('#cfModal').classList.remove('open');
  $('#cfMask').classList.remove('show');
}

async function toggleWatch(code, e) {
  if (e) { e.stopPropagation(); e.preventDefault(); }
  const on = S.watch.has(code);
  try {
    const r = await fetch('/api/watchlist', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code, action: on ? 'remove' : 'add' }),
    });
    const d = await r.json();
    if (d.watch) S.watch = new Set(d.watch);
    renderCurrent();
    updateDbStat();
  } catch (err) {
    console.warn('watchlist failed', err);
  }
}

/* 重新渲染当前视图（自选增删后调用） */
function renderCurrent() {
  if (!S.data) return;
  if (S.view === 'watch') renderWatch();
  else if (['a', 'hk', 'us', 'etf', 'fund'].includes(S.view)) renderMarket();
  else if (S.view === 'signals') renderSignals();
  else if (S.view === 'corr') renderCorr();
  else renderAll();
}

/* 侧栏 MySQL 状态指示 */
async function updateDbStat() {
  const el = $('#dbStat');
  if (!el) return;
  try {
    const r = await fetch('/api/db');
    const d = await r.json();
    const ok = d.ready && d.scores != null;
    el.className = 'dbstat' + (ok ? ' ok' : ' err');
    el.innerHTML = ok
      ? `<span class="dot"></span>MySQL · ${d.scores} 条评分 / ${d.klines} 根日线`
      : `<span class="dot"></span>MySQL 未连接（内存模式）`;
  } catch (e) {
    el.className = 'dbstat err';
    el.innerHTML = '<span class="dot"></span>MySQL 不可达';
  }
}

/* ---------------------------------------------------------------- 标的管理 */
let mgTimer = null;

function openManage() {
  $('#mgModal').classList.add('open');
  $('#mgMask').classList.add('show');
  $('#mgInput').value = '';
  $('#mgResults').innerHTML = '<div class="mg-empty">输入关键词搜索全球标的，点击即加入看板</div>';
  renderMgmtCustom();
  setTimeout(() => $('#mgInput').focus(), 60);
}
function closeManage() {
  $('#mgModal').classList.remove('open');
  $('#mgMask').classList.remove('show');
}

function grpLabel(g) { return { a: 'A 股', hk: '港股', us: '美股', etf: 'ETF', fund: '基金' }[g] || g; }

function renderMgmtCustom() {
  if (!S.data) return;
  const items = [];
  for (const g of Object.values(S.data.groups)) for (const i of g.items) if (i.custom) items.push(i);
  // 选择集合只保留仍存在的自定义标的
  for (const c of [...S.mgSel]) if (!items.some((i) => i.code === c)) S.mgSel.delete(c);
  $('#mgCustomHint').textContent = items.length ? `${items.length} 个` : '';
  const btn = $('#mgBatchDel');
  btn.style.display = S.mgSel.size ? '' : 'none';
  btn.textContent = `移出选中 (${S.mgSel.size})`;
  $('#mgCustomList').innerHTML = items.length ? items.map((i) => `
    <div class="mg-custom"><input type="checkbox" class="mg-chk" data-code="${i.code}"
      ${S.mgSel.has(i.code) ? 'checked' : ''}>
    <b>${esc(i.name)}</b><span>${esc(i.code.toUpperCase())}</span>
    <span class="grow">${grpLabel(i.group)}</span>
    <button class="del" data-del="${i.code}" title="移除">✕</button></div>`).join('')
    : '<div class="mg-empty">还没有自定义标的，搜索后点击"加入"</div>';
  $$('#mgCustomList .mg-chk').forEach((cb) => (cb.onchange = () => {
    if (cb.checked) S.mgSel.add(cb.dataset.code); else S.mgSel.delete(cb.dataset.code);
    btn.style.display = S.mgSel.size ? '' : 'none';
    btn.textContent = `移出选中 (${S.mgSel.size})`;
  }));
}

/** 看板自定义标的批量移出（一次请求，自定义标的会被删除）。 */
async function removeInstrumentsMany() {
  const codes = [...S.mgSel];
  if (!codes.length) return;
  if (!confirm(`确定从看板移除选中的 ${codes.length} 个自定义标的？标的及其已存数据将被删除。`)) return;
  try {
    await fetch('/api/instruments', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'remove_many', codes }),
    });
    S.mgSel.clear();
    await load(true);
    renderMgmtCustom();
    updateDbStat();
  } catch (e) {
    console.warn('batch remove failed', e);
  }
}

async function mgSearch(q) {
  const box = $('#mgResults');
  if (!q.trim()) {
    box.innerHTML = '<div class="mg-empty">输入关键词搜索全球标的，点击即加入看板</div>';
    return;
  }
  box.innerHTML = '<div class="mg-empty">搜索中…</div>';
  try {
    const r = await fetch('/api/search?q=' + encodeURIComponent(q));
    const d = await r.json();
    const items = d.items || [];
    const inBoard = new Set(S.data
      ? Object.values(S.data.groups).flatMap((g) => g.items.map((i) => i.code))
      : []);
    box.innerHTML = items.length ? items.map((i) => `
      <div class="mg-row"><b>${esc(i.name)}</b><span>${esc(i.code.toUpperCase())}</span>
      <span class="tag">${esc(i.type_label || '')}</span>
      <span class="grow">${i.kind === 'etf' ? 'ETF' : i.kind === 'index' ? '指数' : esc(grpLabel(i.grp))}</span>
      ${inBoard.has(i.code) ? '<span class="hint">已在看板</span>'
        : `<button class="btn" data-add="${i.code}">＋ 加入</button>`}</div>`).join('')
      : '<div class="mg-empty">没有匹配的标的（支持 A 股 / 港股 / 美股 / ETF / 场外基金）</div>';
  } catch (e) {
    box.innerHTML = '<div class="mg-empty">搜索失败，请稍后重试</div>';
  }
}

async function addInstrument(code) {
  try {
    const r = await fetch('/api/instruments', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code, action: 'add' }),
    });
    const d = await r.json();
    if (d.error) {
      $('#mgResults').insertAdjacentHTML('afterbegin',
        `<div class="mg-empty" style="color:var(--dn)">${esc(d.error)}</div>`);
      return;
    }
    closeManage();
    await load(true);
    updateDbStat();
  } catch (e) { console.warn('add failed', e); }
}

async function removeInstrument(code) {
  try {
    await fetch('/api/instruments', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code, action: 'remove' }),
    });
    await load(true);
    updateDbStat();
  } catch (e) { console.warn('remove failed', e); }
}


/* ---------------------------------------------------------------- 迷你走势 */
function drawSpark(canvas, data, color) {
  const { ctx, w, h } = hdpi(canvas);
  ctx.clearRect(0, 0, w, h);
  if (!data || data.length < 2) return;
  const min = Math.min(...data), max = Math.max(...data), rng = max - min || 1;
  const pad = 2;
  const x = (i) => (i / (data.length - 1)) * (w - pad * 2) + pad;
  const y = (v) => h - pad - ((v - min) / rng) * (h - pad * 2);
  const g = ctx.createLinearGradient(0, 0, 0, h);
  g.addColorStop(0, color + '38'); g.addColorStop(1, color + '00');
  ctx.beginPath(); ctx.moveTo(x(0), y(data[0]));
  data.forEach((v, i) => ctx.lineTo(x(i), y(v)));
  ctx.lineTo(x(data.length - 1), h); ctx.lineTo(x(0), h); ctx.closePath();
  ctx.fillStyle = g; ctx.fill();
  ctx.beginPath(); ctx.moveTo(x(0), y(data[0]));
  data.forEach((v, i) => ctx.lineTo(x(i), y(v)));
  ctx.strokeStyle = color; ctx.lineWidth = 1.3; ctx.lineJoin = 'round'; ctx.stroke();
}


/* ---------------------------------------------------------------- 渲染：滚动条 */
function renderTicker() {
  const idx = S.data.om.indices;
  const order = ['sh000001', 'sz399001', 'sz399006', 'sh000688', 'hkHSI', 'hkHSTECH',
                 'usDJI', 'usIXIC', 'usINX', 'sh513500', 'sh518880', 'sh000012'];
  const map = {};
  idx.forEach((i) => (map[i.code] = i));
  const etfs = S.data.groups.etf.items;
  etfs.forEach((i) => (map[i.code] = i));
  const list = order.map((c) => map[c]).filter(Boolean);
  $('#ticker').innerHTML = list.map((i) => `
    <div class="tk" data-code="${i.code}">
      <span class="tk-name">${esc(i.name)}</span>
      <span class="tk-val ${cls(i.pct)}">${i.kind === 'index' ? fmt(i.price, 2) : px(i.price)}</span>
      <span class="tk-pct ${cls(i.pct)}">${pctTxt(i.pct)}</span>
    </div>`).join('');
  $$('#ticker .tk').forEach((el) => el.onclick = (ev) => openDrawer(el.dataset.code, ev));
}

/* ---------------------------------------------------------------- 渲染：市场卡 */
function renderMktCards() {
  const gs = [['a', 'A 股'], ['hk', '港股'], ['us', '美股'], ['etf', 'ETF'], ['fund', '基金']];
  const sess = S.data.sessions;
  $('#mktCards').innerHTML = gs.map(([k, label]) => {
    const g = S.data.groups[k], s = g.stats, st = sess[k];
    const tot = s.up + s.down + s.flat || 1;
    const amount = g.items.filter((i) => i.kind !== 'index').reduce((a, b) => a + (b.amount_yi || 0), 0);
    return `<div class="mkt-card">
      <div class="mkt-top">
        <span class="mkt-name">${label}</span>
        <span class="state ${st.cls}">${st.state}</span>
      </div>
      <div class="mkt-main">
        <span class="mkt-pct ${cls(s.avg_pct)}">${pctTxt(s.avg_pct)}</span>
        <span class="mkt-sub">样本均涨跌</span>
      </div>
      <div class="mkt-bar">
        <i style="width:${(s.up / tot) * 100}%"></i>
        <i class="dn" style="width:${(s.down / tot) * 100}%"></i>
      </div>
      <div class="mkt-foot">
        <span><b class="up">${s.up}</b> 涨 / <b class="dn">${s.down}</b> 跌</span>
        <span>20日均值 <b class="${cls(s.avg_r20)}">${pctTxt(s.avg_r20)}</b></span>
      </div>
      <div class="mkt-foot" style="margin-top:4px">
        <span>样本成交额</span><span>${amtTxt(amount)}</span>
      </div>
    </div>`;
  }).join('');

  // 一句话总结
  const a = S.data.groups.a.stats, hk = S.data.groups.hk.stats, us = S.data.groups.us.stats;
  const best = [['A 股', a.avg_r20], ['港股', hk.avg_r20], ['美股', us.avg_r20]].sort((x, y) => y[1] - x[1]);
  const tone = best[0][1] > 0 ? '短期动能以' + best[0][0] + '最强' : '三大市场 20 日均值为负，整体处于调整';
  const breadth = a.up + hk.up + us.up, breadthDn = a.down + hk.down + us.down;
  $('#headline').innerHTML =
    `${tone}（20 日 ${pctTxt(best[0][1])}）；样本内 <b class="up">${breadth}</b> 涨 / <b class="dn">${breadthDn}</b> 跌。` +
    `A 股今日均涨跌 ${pctTxt(a.avg_pct)}，港股 ${pctTxt(hk.avg_pct)}，美股上一交易日 ${pctTxt(us.avg_pct)}。`;
}

/* ---------------------------------------------------------------- 渲染：核心指数 */
function renderIdx() {
  const order = ['sh000001', 'sz399006', 'sh000300', 'sh000905', 'hkHSI', 'hkHSTECH', 'usIXIC', 'usINX', 'sh000012'];
  const map = {};
  S.data.om.indices.forEach((i) => (map[i.code] = i));
  S.data.groups.etf.items.forEach((i) => (map[i.code] = i));
  const list = order.map((c) => map[c]).filter(Boolean);
  $('#idxGrid').innerHTML = list.map((i) => `
    <div class="idx-card" data-code="${i.code}">
      <div class="idx-name"><span>${esc(i.name)}</span><span>${esc(i.code)}</span></div>
      <div class="idx-price ${cls(i.pct)}">${px(i.price)}</div>
      <div class="idx-pct ${cls(i.pct)}">${pctTxt(i.pct)}</div>
      <canvas class="idx-spark" data-code="${i.code}"></canvas>
    </div>`).join('');
  $$('#idxGrid .idx-card').forEach((el) => {
    const it = list.find((x) => x.code === el.dataset.code);
    el.onclick = (ev) => openDrawer(it.code, ev);
    const cv = $('canvas', el);
    requestAnimationFrame(() => drawSpark(cv, it.spark, it.pct >= 0 ? col('--up') : col('--dn')));
  });
}

/* ---------------------------------------------------------------- 渲染：板块 */
function renderSectors() {
  const S1 = S.data.sectors;
  const renderBars = (arr, sel) => {
    const max = Math.max(...arr.map((x) => Math.abs(x.pct)), 1);
    $(sel).innerHTML = arr.map((s) => `
      <div class="bar-row">
        <span class="bar-name" title="${esc(s.name)}">${esc(s.name)}</span>
        <span class="bar-track"><i class="bar-fill" style="display:block;width:${(Math.abs(s.pct) / max) * 100}%;background:var(--${s.pct >= 0 ? 'up' : 'dn'})"></i></span>
        <span class="bar-val ${cls(s.pct)}">${pctTxt(s.pct)}</span>
      </div>`).join('') || '<div class="small">暂无板块数据</div>';
  };
  renderBars(S1.up, '#sectorUp');
  renderBars(S1.down, '#sectorDown');
  $('#sectorHint').textContent = `共 ${S1.count} 个行业 · 按涨幅排序`;
}

/* ---------------------------------------------------------------- 渲染：信号速览 */
function renderMiniRank() {
  const row = (r) => `
    <div class="mr" data-code="${r.code}">
      <span class="mr-l"><span class="mr-name">${esc(r.name)}</span><span class="mr-code">${esc(r.code)}</span></span>
      <span class="${cls(r.pct)}" style="font-weight:600">${pctTxt(r.pct)}</span>
      <span class="chip ${r.signal_cls}">${r.score}</span>
    </div>`;
  $('#miniTop').innerHTML = S.data.ranking.top.slice(0, 5).map(row).join('');
  $('#miniBottom').innerHTML = S.data.ranking.bottom.slice(0, 5).map(row).join('');
  $$('.mini-rank .mr').forEach((el) => (el.onclick = (ev) => openDrawer(el.dataset.code, ev)));
}

/* ---------------------------------------------------------------- 渲染：市场表 */
const MKT_COLS = [
  { k: 'name', t: '名称', align: 'l' },
  { k: 'price', t: '最新价' },
  { k: 'pct', t: '涨跌幅' },
  { k: 'amount_yi', t: '成交额' },
  { k: 'r20', t: '20 日' },
  { k: 'rsi', t: 'RSI' },
  { k: 'score', t: '评分' },
  { k: 'signal', t: '信号' },
  { k: 'spark', t: '45 日' },
];


/* ---------------------------------------------------------------- 详情跳转 */
/** 行点击 → 打开独立详情页（Cmd/Ctrl/中键点开新标签） */
function openDrawer(code, ev) {
  if (!code) return;
  const url = 'stock.html?code=' + encodeURIComponent(code);
  const e = ev || window.event;
  if (e && (e.metaKey || e.ctrlKey || e.shiftKey || e.button === 1)) window.open(url, '_blank');
  else location.href = url;
}

function renderMarket() {
  const g = S.data.groups[S.group];
  $('#mktTitle').textContent = g.label;
  const st = S.data.sessions[S.group];
  $('#mktDesc').innerHTML = `${g.items.length} 个标的 · 基准 ${
    (g.items.find((i) => i.code === g.benchmark) || {}).name || '—'} · 状态 ${st.state} · 数据 ${S.data.ts}`;

  const s = g.stats;
  const stock = g.items.filter((i) => i.kind !== 'index');
  const best = [...stock].sort((a, b) => b.pct - a.pct)[0] || {};
  const worst = [...stock].sort((a, b) => a.pct - b.pct)[0] || {};
  const avgScore = stock.reduce((a, b) => a + ((b.ana || {}).score || 0), 0) / (stock.length || 1);
  const strongest = [...stock].sort((a, b) => ((b.ana || {}).score || 0) - ((a.ana || {}).score || 0))[0] || {};
  $('#mktStats').innerHTML = `
    <div class="stat"><span>今日均涨跌</span><b class="${cls(s.avg_pct)}">${pctTxt(s.avg_pct)}</b></div>
    <div class="stat"><span>涨 / 跌</span><b><span class="up">${s.up}</span> / <span class="dn">${s.down}</span></b></div>
    <div class="stat"><span>涨幅第一</span><b style="font-size:14px">${esc(best.name || '—')} <span class="${cls(best.pct)}">${pctTxt(best.pct)}</span></b></div>
    <div class="stat"><span>跌幅第一</span><b style="font-size:14px">${esc(worst.name || '—')} <span class="${cls(worst.pct)}">${pctTxt(worst.pct)}</span></b></div>
    <div class="stat"><span>平均评分 / 最强</span><b style="font-size:14px">${avgScore.toFixed(0)} · ${esc(strongest.name || '—')}</b></div>`;

  // 分段控件按分组内容动态生成
  const kinds = new Set(g.items.map((i) => i.kind));
  const labels = { all: '全部', index: '指数', stock: '个股', etf: 'ETF', fund: '基金' };
  $('#kindSeg').innerHTML = ['all', ...kinds].map((k) =>
    `<button class="seg-btn ${S.kind === k ? 'active' : ''}" data-kind="${k}">${labels[k]}</button>`).join('');
  $$('#kindSeg .seg-btn').forEach((b) => (b.onclick = () => { S.kind = b.dataset.kind; renderMarket(); }));

  let items = g.items.filter((i) => S.kind === 'all' || i.kind === S.kind);
  items = sortItems(items, S.sort);

  // 国内 ETF 分组插入溢价率列（字段来自腾讯行情 f78 净值/IOPV）
  const showPrem = S.group === 'etf' && S.kind !== 'index';
  // 基金分组：净值/日涨幅/区间收益替代行情列
  const isFundGrp = S.group === 'fund';
  const cols = isFundGrp
    ? [{ k: 'name', t: '名称', align: 'l' }, { k: 'price', t: '单位净值' }, { k: 'pct', t: '日涨幅' },
       { k: 'm1', t: '近1月' }, { k: 'y1', t: '近1年' }, { k: 'r20', t: '20 日' },
       { k: 'score', t: '评分' }, { k: 'signal', t: '信号' }, { k: 'spark', t: '净值 45 日' }]
    : showPrem
      ? [...MKT_COLS.slice(0, 3), { k: 'premium', t: '溢价率' }, ...MKT_COLS.slice(3)]
      : MKT_COLS;
  const premTd = (i) => {
    if (i.premium == null) return '<td>—</td>';
    const warn = Math.abs(i.premium) >= 1;
    return `<td class="${cls(i.premium)}"${warn ? ' style="font-weight:650"' : ''} title="较净值/IOPV">${pctTxt(i.premium)}</td>`;
  };
  const retTd = (i, k) => {
    const v = (i.returns || {})[k];
    return `<td class="${cls(v)}">${pctTxt(v)}</td>`;
  };

  const rows = items.map((i) => {
    const a = i.ana || {};
    const midCells = isFundGrp
      ? retTd(i, 'm1') + retTd(i, 'y1')
      : `${showPrem ? premTd(i) : ''}<td>${amtTxt(i.amount_yi)}</td>`;
    return `<tr data-code="${i.code}">
      <td><div class="cell-name"><b>${starHTML(i.code)}${esc(i.name)}${delHTML(i.code)}</b><span>${esc(i.code)}</span></div></td>
      <td class="${cls(i.pct)}">${px(i.price)}</td>
      <td class="${cls(i.pct)}"><b>${pctTxt(i.pct)}</b></td>
      ${midCells}
      <td class="${cls(a.r20)}">${pctTxt(a.r20)}</td>
      ${isFundGrp ? '' : `<td class="${a.rsi > 75 ? 'up' : a.rsi < 30 ? 'dn' : ''}">${a.rsi != null ? a.rsi.toFixed(0) : '—'}</td>`}
      <td><div class="score-cell"><span class="small">${a.score != null ? a.score.toFixed(0) : '—'}</span>
        <span class="score-track"><i style="width:${a.score || 0}%;background:${scoreColor(a.score || 0)}"></i></span></div></td>
      <td>${a.signal ? `<span class="chip ${a.signal_cls}">${a.signal}</span>` : '—'}</td>
      <td><canvas class="spark-canvas" data-code="${i.code}"></canvas></td>
    </tr>`;
  }).join('');

  $('#mktTable').innerHTML = `<thead><tr>${cols.map((c) => {
    const sorted = S.sort.key === c.k ? 'sorted' : '';
    const arrow = S.sort.key === c.k ? (S.sort.dir < 0 ? ' ↓' : ' ↑') : '';
    return `<th class="${sorted}" data-k="${c.k}" style="text-align:${c.align === 'l' ? 'left' : 'right'}">${c.t}${arrow}</th>`;
  }).join('')}</tr></thead><tbody>${rows}</tbody>`;

  $$('#mktTable th').forEach((th) => (th.onclick = () => {
    const k = th.dataset.k;
    if (!['price', 'pct', 'amount_yi', 'r20', 'rsi', 'score', 'm1', 'y1'].includes(k) &&
        !(k === 'premium' && showPrem)) return;
    S.sort = { key: k, dir: S.sort.key === k ? -S.sort.dir : -1 };
    renderMarket();
  }));
  $$('#mktTable tbody tr').forEach((tr) => (tr.onclick = (e) => {
    const st = e.target.closest('.star');
    if (st) { toggleWatch(st.dataset.star, e); return; }
    const del = e.target.closest('.row-del');
    if (del) { e.stopPropagation(); requestRemove(del.dataset.del); return; }
    openDrawer(tr.dataset.code, e);
  }));
  bindSparks('#mktTable');
}

function sortItems(items, sort) {
  const get = (i) => {
    if (sort.key === 'score') return (i.ana || {}).score ?? -1;
    if (sort.key === 'rsi') return (i.ana || {}).rsi ?? -1;
    if (sort.key === 'r20') return (i.ana || {}).r20 ?? -999;
    if (sort.key === 'm1' || sort.key === 'y1') return (i.returns || {})[sort.key] ?? -999;
    if (sort.key === 'amount_yi') return i.amount_yi || 0;
    return i[sort.key] ?? 0;
  };
  return [...items].sort((a, b) => (get(a) - get(b)) * (sort.dir < 0 ? -1 : 1));
}

function bindSparks(scope) {
  $$(scope + ' canvas.spark-canvas').forEach((cv) => {
    const code = cv.dataset.code;
    let it = null;
    for (const g of Object.values(S.data.groups)) {
      it = g.items.find((x) => x.code === code);
      if (it) break;
    }
    if (!it) return;
    requestAnimationFrame(() => drawSpark(cv, it.spark, it.pct >= 0 ? col('--up') : col('--dn')));
  });
}

/* ---------------------------------------------------------------- 渲染：信号榜 */
function renderSignals() {
  const all = S.data.ranking.all.filter((r) => S.sigFilter === 'all' || r.group === S.sigFilter);
  const head = `<thead><tr>
    <th style="text-align:left">标的</th><th>分组</th><th>最新价</th><th>今日</th>
    <th>20 日</th><th>RSI</th><th>超额</th><th>评分</th><th>信号</th><th style="text-align:left;min-width:280px">技术面结论</th>
  </tr></thead>`;
  const body = all.map((r, idx) => `<tr data-code="${r.code}">
    <td><div class="cell-name"><b>${starHTML(r.code)}${esc(r.name)}${delHTML(r.code)}</b><span>${esc(r.code)}</span></div></td>
    <td><span class="grp">${r.group_label}</span></td>
    <td class="${cls(r.pct)}">${px(r.price)}</td>
    <td class="${cls(r.pct)}"><b>${pctTxt(r.pct)}</b></td>
    <td class="${cls(r.r20)}">${pctTxt(r.r20)}</td>
    <td class="${r.rsi > 75 ? 'up' : r.rsi < 30 ? 'dn' : ''}">${r.rsi != null ? r.rsi.toFixed(0) : '—'}</td>
    <td class="${cls(r.rs)}">${r.rs != null ? (r.rs > 0 ? '+' : '') + r.rs.toFixed(1) : '—'}</td>
    <td><span style="font-weight:650;color:${scoreColor(r.score)}">${r.score.toFixed(0)}</span></td>
    <td><span class="chip ${r.signal_cls}">${r.signal}</span></td>
    <td style="text-align:left;white-space:normal;font-size:11.5px;color:var(--txt-2);max-width:340px">${esc(r.conclusion)}</td>
  </tr>`).join('');
  $('#sigTable').innerHTML = head + `<tbody>${body}</tbody>`;
  $$('#sigTable tbody tr').forEach((tr) => (tr.onclick = (e) => {
    const st = e.target.closest('.star');
    if (st) { toggleWatch(st.dataset.star, e); return; }
    const del = e.target.closest('.row-del');
    if (del) { e.stopPropagation(); requestRemove(del.dataset.del); return; }
    openDrawer(tr.dataset.code, e);
  }));
}

/* ---------------------------------------------------------------- 渲染：自选股 */
function renderWatch() {
  const all = S.data.ranking.all;
  const picks = all.filter((r) => S.watch.has(r.code));
  const idxOnly = [...S.data.om.indices].filter((i) => S.watch.has(i.code) && !picks.some((p) => p.code === i.code));

  const s = {
    n: picks.length + idxOnly.length,
    up: picks.filter((p) => p.pct > 0).length,
    dn: picks.filter((p) => p.pct < 0).length,
    avg: picks.length ? picks.reduce((a, b) => a + b.score, 0) / picks.length : 0,
    strong: [...picks].sort((a, b) => b.score - a.score)[0] || null,
  };
  $('#watchStats').innerHTML = s.n === 0 ? '' : `
    <div class="stat"><span>自选数量</span><b>${s.n}</b></div>
    <div class="stat"><span>涨 / 跌</span><b><span class="up">${s.up}</span> / <span class="dn">${s.dn}</span></b></div>
    <div class="stat"><span>平均评分</span><b>${s.avg.toFixed(1)}</b></div>
    <div class="stat"><span>最强标的</span><b style="font-size:14px">${s.strong ? esc(s.strong.name) + ' · ' + s.strong.score.toFixed(0) : '—'}</b></div>`;

  if (s.n === 0) {
    $('#watchTable').innerHTML = `<tbody><tr><td style="text-align:center;padding:36px 0;color:var(--txt-3);font-size:12.5px">
      自选清单为空 —— 在任意市场页或信号榜里点标的名称前的 ★ 加入</td></tr></tbody>`;
    $('#watchHint').textContent = 'MySQL watchlist 表已连接，随时可收藏';
    $('#watchBatchDel').style.display = 'none';
    return;
  }
  // 选择集合只保留仍存在的自选
  const allCodes = new Set([...picks, ...idxOnly].map((r) => r.code));
  for (const c of [...S.watchSel]) if (!allCodes.has(c)) S.watchSel.delete(c);

  const head = `<thead><tr>
    <th style="width:30px;text-align:center"><input type="checkbox" id="wkAll" title="全选 / 全不选"
      ${S.watchSel.size && S.watchSel.size === allCodes.size ? 'checked' : ''}></th>
    <th style="text-align:left">标的</th><th>分组</th><th>最新价</th><th>今日</th><th>20 日</th>
    <th>RSI</th><th>评分</th><th>信号</th><th style="text-align:left;min-width:260px">技术面结论</th>
  </tr></thead>`;
  const chk = (code) => `<td style="text-align:center"><input type="checkbox" class="wk-chk" data-code="${code}"
    ${S.watchSel.has(code) ? 'checked' : ''}></td>`;
  const row = (r) => `<tr data-code="${r.code}">
    ${chk(r.code)}
    <td><div class="cell-name"><b>${starHTML(r.code)}${esc(r.name)}${delHTML(r.code)}</b><span>${esc(r.code)}</span></div></td>
    <td><span class="grp">${r.group_label || grpLabel(r.group)}</span></td>
    <td class="${cls(r.pct)}">${px(r.price)}</td>
    <td class="${cls(r.pct)}"><b>${pctTxt(r.pct)}</b></td>
    <td class="${cls(r.r20)}">${pctTxt(r.r20)}</td>
    <td class="${r.rsi > 75 ? 'up' : r.rsi < 30 ? 'dn' : ''}">${r.rsi != null ? r.rsi.toFixed(0) : '—'}</td>
    <td><span style="font-weight:650;color:${scoreColor(r.score)}">${r.score.toFixed(0)}</span></td>
    <td><span class="chip ${r.signal_cls}">${r.signal}</span></td>
    <td style="text-align:left;white-space:normal;font-size:11.5px;color:var(--txt-2);max-width:320px">${esc(r.conclusion || '—')}</td>
  </tr>`;
  const idxRow = (i) => {
    const a = i.ana || {};
    return `<tr data-code="${i.code}">
    ${chk(i.code)}
    <td><div class="cell-name"><b>${starHTML(i.code)}${esc(i.name)}${delHTML(i.code)}</b><span>${esc(i.code)}</span></div></td>
    <td><span class="grp">指数</span></td>
    <td class="${cls(i.pct)}">${px(i.price)}</td>
    <td class="${cls(i.pct)}"><b>${pctTxt(i.pct)}</b></td>
    <td class="${cls(a.r20)}">${pctTxt(a.r20)}</td>
    <td class="${a.rsi > 75 ? 'up' : a.rsi < 30 ? 'dn' : ''}">${a.rsi != null ? a.rsi.toFixed(0) : '—'}</td>
    <td><span style="font-weight:650;color:${scoreColor(a.score || 0)}">${(a.score || 0).toFixed(0)}</span></td>
    <td><span class="chip ${a.signal_cls || 'neutral'}">${a.signal || '—'}</span></td>
    <td style="text-align:left;white-space:normal;font-size:11.5px;color:var(--txt-2)">${esc(a.conclusion || '—')}</td>
  </tr>`;
  };
  $('#watchTable').innerHTML = head + `<tbody>${picks.map(row).join('')}${idxOnly.map(idxRow).join('')}</tbody>`;
  // 批量删除控件
  const batchBtn = $('#watchBatchDel');
  batchBtn.style.display = S.watchSel.size ? '' : 'none';
  batchBtn.textContent = `移除选中 (${S.watchSel.size})`;
  $('#watchTable').querySelectorAll('tbody tr').forEach((tr) => (tr.onclick = (e) => {
    if (e.target.closest('.star')) return;
    if (e.target.closest('input[type="checkbox"]')) return;
    const del = e.target.closest('.row-del');
    if (del) { e.stopPropagation(); requestRemove(del.dataset.del); return; }
    openDrawer(tr.dataset.code, e);
  }));
  $('#watchTable').querySelectorAll('.wk-chk').forEach((cb) => (cb.onchange = () => {
    if (cb.checked) S.watchSel.add(cb.dataset.code); else S.watchSel.delete(cb.dataset.code);
    batchBtn.style.display = S.watchSel.size ? '' : 'none';
    batchBtn.textContent = `移除选中 (${S.watchSel.size})`;
    const all = $('#wkAll');
    if (all) all.checked = $$('#watchTable .wk-chk').length > 0
      && $$('#watchTable .wk-chk').every((x) => x.checked);
  }));
  const wkAll = $('#wkAll');
  if (wkAll) wkAll.onchange = () => {
    S.watchSel = new Set(wkAll.checked ? allCodes : []);
    $$('#watchTable .wk-chk').forEach((cb) => (cb.checked = wkAll.checked));
    batchBtn.style.display = S.watchSel.size ? '' : 'none';
    batchBtn.textContent = `移除选中 (${S.watchSel.size})`;
  };
  $('#watchHint').textContent = `共 ${s.n} 个 · 存于 MySQL，跨会话保留`;
}

/** 自选批量移除（一次请求）。 */
async function removeWatchMany() {
  const codes = [...S.watchSel];
  if (!codes.length) return;
  if (!confirm(`确定从自选中移除 ${codes.length} 个标的？`)) return;
  try {
    const r = await fetch('/api/watchlist', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'remove_many', codes }),
    });
    const d = await r.json();
    if (d.watch) S.watch = new Set(d.watch);
    S.watchSel.clear();
    renderCurrent();
    updateDbStat();
  } catch (err) {
    console.warn('batch remove failed', err);
  }
}

/* ---------------------------------------------------------------- 渲染：持仓 */
async function loadPort() {
  try {
    const r = await fetch('/api/positions');
    S.port = await r.json();
  } catch (e) { S.port = { ready: false, brokers: [] }; }
  renderPort();
  fillBrokerList();
}

function fillBrokerList() {
  const names = (S.port && S.port.brokers || []).map((b) => b.broker);
  $('#brokerList').innerHTML = names.map((n) => `<option value="${esc(n)}">`).join('');
}

function renderPort() {
  const box = $('#portBrokers'), stats = $('#portStats');
  if (!S.port) { box.innerHTML = '<div class="small">加载中…</div>'; return; }

  if (!S.port.ready) {
    stats.innerHTML = '';
    box.innerHTML = `<div class="port-empty">MySQL 未连接，持仓功能不可用（需 Docker Desktop 运行 mysql 容器）</div>`;
    return;
  }
  const brokers = S.port.brokers || [];
  if (!brokers.length) {
    stats.innerHTML = '';
    box.innerHTML = `<div class="port-empty">还没有持仓记录 —— 点右上「图片识别」上传券商 App 持仓截图，
      或「手动添加」逐条录入。<br/><span class="small">同标的可分属不同券商各记一条。</span></div>`;
    return;
  }

  // 汇总
  const g = S.port.grand || [];
  const parts = [];
  for (const t of g) {
    parts.push(`<div class="stat"><span>${curSym(t.currency)} 市值</span>
      <b>${curSym(t.currency)}${fmt(t.mv, 2)}</b></div>
      <div class="stat"><span>总盈亏</span><b class="${cls(t.pl)}">${t.pl > 0 ? '+' : ''}${curSym(t.currency)}${fmt(Math.abs(t.pl), 2)}
      <span class="small ${cls(t.pl)}">(${pctTxt(t.pl_pct)})</span></b></div>
      <div class="stat"><span>今日盈亏</span><b class="${cls(t.day_pl)}">${t.day_pl > 0 ? '+' : ''}${curSym(t.currency)}${fmt(Math.abs(t.day_pl), 2)}</b></div>`);
  }
  if (S.port.grand_cny) {
    parts.push(`<div class="stat"><span>约合人民币</span><b style="font-size:14px">¥${fmt(S.port.grand_cny, 0)}
      <span class="small">（静态汇率 ${S.port.fx ? 'HKD ' + S.port.fx.HKD + ' / USD ' + S.port.fx.USD : ''}）</span></b></div>`);
  }
  stats.innerHTML = parts.join('');

  box.innerHTML = brokers.map((b) => {
    const tot = (b.totals || []).map((t) =>
      `<span class="port-tot ${cls(t.pl)}">${curSym(t.currency)}${fmt(t.mv, 2)}
       <em>${t.pl > 0 ? '+' : ''}${fmt(t.pl, 2)}（${pctTxt(t.pl_pct)}）</em></span>`).join('');
    const rows = b.items.map((it) => {
      const openable = S.data && Object.values(S.data.groups).some((g2) => g2.items.some((x) => x.code === it.code));
      return `<tr data-code="${it.code}" class="${openable ? 'linkable' : ''}">
        <td><div class="cell-name"><b>${esc(it.name)}</b><span>${esc(it.code.toUpperCase())}</span></div></td>
        <td>${fmt(it.qty, it.qty % 1 ? 4 : 0)}</td>
        <td>${curSym(it.currency)}${px(it.cost)}</td>
        <td class="${cls(it.pct)}">${it.live ? px(it.price) : '—'}</td>
        <td class="${cls(it.pct)}">${pctTxt(it.pct)}</td>
        <td class="${cls(it.day_pl)}">${it.live ? (it.day_pl > 0 ? '+' : '') + fmt(it.day_pl, 2) : '—'}</td>
        <td>${curSym(it.currency)}${fmt(it.mv, 2)}</td>
        <td class="${cls(it.pl)}"><b>${it.pl > 0 ? '+' : ''}${fmt(it.pl, 2)}</b><br/>
          <span class="small ${cls(it.pl)}">${pctTxt(it.pl_pct)}</span></td>
        <td>${it.score != null ? `<span style="font-weight:650;color:${scoreColor(it.score)}">${it.score.toFixed(0)}</span>` : '—'}</td>
        <td>${it.signal ? `<span class="chip ${signalCls(it.signal)}">${it.signal}</span>` : '—'}</td>
        <td><span class="row-del" data-delpos="${it.id}" title="删除该持仓">✕</span></td>
      </tr>`;
    }).join('');
    return `<div class="panel port-panel">
      <div class="panel-head"><h2>${esc(b.broker)}</h2><span class="hint">${b.items.length} 个标的</span>
        <span class="port-tots">${tot}</span></div>
      <div class="table-wrap"><table class="tbl">
        <thead><tr><th style="text-align:left">标的</th><th>数量</th><th>成本价</th><th>现价</th>
        <th>今日</th><th>今日盈亏</th><th>市值</th><th>持仓盈亏</th><th>评分</th><th>信号</th><th></th></tr></thead>
        <tbody>${rows}</tbody></table></div></div>`;
  }).join('');

  $$('#portBrokers tbody tr').forEach((tr) => (tr.onclick = (e) => {
    const del = e.target.closest('[data-delpos]');
    if (del) { e.stopPropagation(); delPosition(+del.dataset.delpos); return; }
    if (e.target.closest('.row-del')) return;
    const code = tr.dataset.code;
    if (S.data && Object.values(S.data.groups).some((g2) => g2.items.some((x) => x.code === code))) openDrawer(code);
  }));
}

function signalCls(sig) {
  return { 强势: 'up', 偏强: 'up', 中性: 'neutral', 偏弱: 'dn', 弱势: 'dn' }[sig] || 'neutral';
}

async function delPosition(id) {
  try {
    await fetch('/api/positions', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'remove', id }),
    });
    await loadPort();
  } catch (e) { console.warn('del position failed', e); }
}

/* ---------------- 手动添加持仓 ---------------- */
function openPortAdd() {
  $('#paModal').classList.add('open');
  $('#paMask').classList.add('show');
  $('#paErr').textContent = '';
  setTimeout(() => $('#paBroker').focus(), 60);
}
function closePortAdd() {
  $('#paModal').classList.remove('open');
  $('#paMask').classList.remove('show');
}

async function savePosition() {
  const body = {
    action: 'add',
    broker: $('#paBroker').value.trim(),
    code: $('#paCode').value.trim(),
    qty: +$('#paQty').value,
    cost: +$('#paCost').value,
  };
  if (!body.code || !body.qty) { $('#paErr').textContent = '请填写代码和数量'; return; }
  $('#paErr').textContent = '保存中…';
  try {
    const r = await fetch('/api/positions', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    const d = await r.json();
    if (d.error || d.ok === false) { $('#paErr').textContent = d.error || '保存失败'; return; }
    closePortAdd();
    ['paBroker', 'paCode', 'paQty', 'paCost'].forEach((i) => ($('#' + i).value = ''));
    await loadPort();
  } catch (e) { $('#paErr').textContent = '网络错误'; }
}

/* ---------------- 图片识别持仓 ---------------- */
function openPortOcr() {
  $('#poModal').classList.add('open');
  $('#poMask').classList.add('show');
}
function closePortOcr() {
  $('#poModal').classList.remove('open');
  $('#poMask').classList.remove('show');
}

function resetOcrModal() {
  $('#poFile').value = '';
  $('#poPreviewWrap').style.display = 'none';
  $('#poRows').innerHTML = '';
  $('#poImportBar').style.display = 'none';
  S.ocrItems = [];
}

async function handleOcrFile(file) {
  if (!file) return;
  resetOcrModal();
  const url = URL.createObjectURL(file);
  $('#poPreview').src = url;
  $('#poPreviewWrap').style.display = 'block';
  $('#poStatus').textContent = '识别中…（首次调用会先编译本地 OCR，约需十几秒）';
  const fd = new FormData();
  fd.append('file', file);
  try {
    const r = await fetch('/api/positions/ocr', { method: 'POST', body: fd });
    const d = await r.json();
    if (d.error) { $('#poStatus').innerHTML = `<span class="dn">${esc(d.error)}</span>`; return; }
    S.ocrItems = d.items || [];
    $('#poStatus').textContent = `识别出 ${S.ocrItems.length} 条持仓候选，请核对后导入（数字直接可编辑）`;
    renderOcrRows();
  } catch (e) {
    $('#poStatus').innerHTML = '<span class="dn">识别请求失败</span>';
  }
}

function renderOcrRows() {
  const items = S.ocrItems;
  if (!items.length) return;
  $('#poRows').innerHTML = `<table class="tbl po-tbl">
    <thead><tr><th></th><th style="text-align:left">名称</th><th style="text-align:left">代码</th>
    <th>数量</th><th>成本价</th><th>现价</th><th>状态</th></tr></thead>
    <tbody>${items.map((it, i) => `<tr>
      <td><input type="checkbox" class="po-chk" data-i="${i}" checked /></td>
      <td style="text-align:left"><input class="po-in" data-f="name" data-i="${i}" value="${esc(it.name || it.quote_name || '')}" /></td>
      <td style="text-align:left"><b>${esc((it.code || '').toUpperCase())}</b></td>
      <td><input class="po-in" data-f="qty" data-i="${i}" value="${it.qty ?? ''}" /></td>
      <td><input class="po-in" data-f="cost" data-i="${i}" value="${it.cost || ''}" placeholder="0" /></td>
      <td>${it.price ? px(it.price) : '—'}</td>
      <td>${it.confidence === 'high' ? '<span class="chip up">已匹配行情</span>'
        : it.confidence === 'check' ? '<span class="chip warn">名称待核对</span>'
        : '<span class="chip neutral">无行情</span>'}</td>
    </tr>`).join('')}</tbody></table>`;
  $('#poImportBar').style.display = 'block';
  $$('#poRows .po-in').forEach((inp) => (inp.onchange = () => {
    const it = S.ocrItems[+inp.dataset.i];
    it[inp.dataset.f] = inp.dataset.f === 'name' ? inp.value : +inp.value || 0;
  }));
}

async function importOcr() {
  const broker = $('#poBroker').value.trim() || '默认账户';
  const picks = $$('#poRows .po-chk').filter((c) => c.checked).map((c) => S.ocrItems[+c.dataset.i]);
  const valid = picks.filter((it) => it.code && it.qty > 0);
  if (!valid.length) return;
  const btn = $('#poImport');
  btn.disabled = true;
  btn.textContent = `导入中 0/${valid.length}`;
  let ok = 0;
  for (const it of valid) {
    try {
      const r = await fetch('/api/positions', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'add', broker, code: it.code, name: it.name || it.quote_name || '', qty: it.qty, cost: it.cost || 0 }),
      });
      const d = await r.json();
      if (d.ok) ok++;
    } catch (e) { /* 跳过失败行 */ }
    btn.textContent = `导入中 ${ok + 1}/${valid.length}`;
  }
  btn.disabled = false;
  btn.textContent = '导入所选';
  closePortOcr();
  resetOcrModal();
  await loadPort();
}

/* ---------------------------------------------------------------- 渲染：相关性 */
function renderCorr() {
  const { labels, matrix } = S.data.corr;
  const n = labels.length;
  const cellColor = (v) => {
    const a = Math.min(0.62, Math.abs(v) * 0.62);
    return v >= 0 ? `rgba(217,43,43,${a})` : `rgba(51,85,224,${a})`;
  };
  let html = `<div class="matrix" style="grid-template-columns:96px repeat(${n},1fr)">`;
  html += '<div></div>';
  labels.forEach((l) => (html += `<div class="mx-head">${esc(l)}</div>`));
  labels.forEach((rl, i) => {
    html += `<div class="mx-side">${esc(rl)}</div>`;
    labels.forEach((_, j) => {
      const v = matrix[i][j];
      const w = i === j ? 400 : Math.abs(v) > 0.75 ? 700 : 600;
      html += `<div class="mx-cell" style="background:${i === j ? 'var(--line-soft)' : cellColor(v)};font-weight:${w};${Math.abs(v) > 0.45 && i !== j ? 'color:#fff' : ''}">${v.toFixed(2)}</div>`;
    });
  });
  html += '</div>';
  $('#corrBox').innerHTML = html;

  // 解读
  const pairs = [];
  for (let i = 0; i < n; i++) for (let j = i + 1; j < n; j++) pairs.push({ a: labels[i], b: labels[j], v: matrix[i][j] });
  pairs.sort((x, y) => y.v - x.v);
  const high = pairs[0], low = pairs[pairs.length - 1];
  const eq = labels.filter((l) => /ETF/.test(l));
  const gold = '黄金ETF';
  const goldPairs = pairs.filter((p) => p.a === gold || p.b === gold);
  const goldAvg = goldPairs.length ? goldPairs.reduce((a, b) => a + b.v, 0) / goldPairs.length : null;

  const blocks = [];
  blocks.push({
    cls: high.v > 0.7 ? 'warn' : '',
    t: `<b>${high.a} × ${high.b}</b> 相关性最高，${high.v.toFixed(2)}。${high.v > 0.7
      ? '几乎同涨同跌，同时持有等于把同一份风险买了两遍，配置上应视为同一篮子。'
      : '联动偏强，但仍有分散空间。'}`,
  });
  if (goldAvg !== null) {
    blocks.push({
      cls: goldAvg < 0.15 ? 'ok' : 'warn',
      t: `<b>黄金 ETF</b> 与权益类资产平均相关性 ${goldAvg.toFixed(2)}，${goldAvg < 0.15
        ? '负相关/低相关特征明显，是当前组合里最有效的风险缓冲。'
        : '相关性偏高，避险属性被削弱，不宜计入“对冲”仓位。'}`,
    });
  }
  blocks.push({
    cls: '',
    t: `<b>${low.a} × ${low.b}</b> 相关性最低（${low.v.toFixed(2)}），是组合里分散效果最好的一对。`,
  });
  const crossAvg = pairs.filter((p) => {
    const grp = (l) => (l.includes('纳指') ? 'us' : l.includes('恒生') ? 'hk' : l.includes('黄金') ? 'gold' : 'cn');
    return grp(p.a) !== grp(p.b) && grp(p.a) !== 'gold' && grp(p.b) !== 'gold';
  });
  if (crossAvg.length) {
    const av = crossAvg.reduce((a, b) => a + b.v, 0) / crossAvg.length;
    blocks.push({
      cls: av < 0.4 ? 'ok' : 'warn',
      t: `跨市场（A 股 / 港股 / 美股）平均相关性 <b>${av.toFixed(2)}</b>，${av < 0.4
        ? '跨市场分散确实有效，海外权益仓位能显著降低单一市场回撤。'
        : '跨市场联动已经很高，靠地域分散的意义有限，需要靠资产类别（债券 / 黄金）来降波动。'}`,
    });
  }
  $('#corrRead').innerHTML = blocks.map((b) => `<div class="rl ${b.cls}">${b.t}</div>`).join('') +
    `<div class="small" style="margin-top:10px">样本：${labels.join('、')}，近 60 个交易日日收益率。</div>`;
}

/* ---------------------------------------------------------------- 数据加载 */
async function load(force) {
  if (S.loading) return;
  S.loading = true;
  $('#updTime').textContent = '刷新中…';
  try {
    const r = await fetch('/api/board' + (force ? '?force=1' : ''));
    const d = await r.json();
    if (d.error) throw new Error(d.error);
    S.data = d;
    S.watch = new Set(d.watch || []);
    renderAll();
    $('#updTime').textContent = d.ts.slice(11);
    const live = ['a', 'hk', 'us'].some((k) => d.sessions[k].cls === 'live');
    $('#liveDot').className = 'dot' + (live ? ' live' : '');
  } catch (e) {
    $('#updTime').textContent = '连接失败';
    $('#headline').innerHTML = `<span class="dn">行情加载失败：${esc(e.message)}</span>`;
  } finally {
    S.loading = false;
  }
}

function renderAll() {
  renderTicker();
  renderMktCards();
  renderIdx();
  renderSectors();
  renderMiniRank();
  renderMarket();
  renderWatch();
  renderSignals();
  renderCorr();
  if (S.view === 'port') loadPort();
}

/* ---------------------------------------------------------------- 交互 */
function switchView(v) {
  S.view = v;
  if (location.hash.slice(1) !== v) history.replaceState(null, '', '#' + v);
  $$('.nav-item').forEach((n) => n.classList.toggle('active', n.dataset.view === v));
  $$('.view').forEach((s) => s.classList.remove('active'));
  if (['a', 'hk', 'us', 'etf', 'fund'].includes(v)) {
    S.group = v; S.kind = 'all'; S.sort = { key: 'score', dir: -1 };
    $('#v-market').classList.add('active');
    if (S.data) renderMarket(); // 首屏数据未到时先不渲染，load() 完成后会补
  } else {
    $('#v-' + v).classList.add('active');
    if (v === 'port') loadPort();
  }
  $('main').scrollTo({ top: 0, behavior: 'smooth' });
}

function init() {
  $$('.nav-item').forEach((n) => (n.onclick = () => switchView(n.dataset.view)));
  $$('[data-view]').forEach((n) => {
    if (!n.classList.contains('nav-item') && n.tagName === 'A') n.onclick = () => switchView(n.dataset.view);
  });
  $('#refreshBtn').onclick = () => load(true);
  $('#watchBatchDel').onclick = () => removeWatchMany();
  $('#mgBatchDel').onclick = () => removeInstrumentsMany();
  $('#themeBtn').onclick = () => {
    const cur = document.documentElement.dataset.theme;
    document.documentElement.dataset.theme = cur === 'light' ? 'dark' : 'light';
    localStorage.setItem('qb-theme', document.documentElement.dataset.theme);
    if (S.data) renderAll();
  };
  const savedTheme = localStorage.getItem('qb-theme');
  if (savedTheme) document.documentElement.dataset.theme = savedTheme;

  $('#autoChk').onchange = (e) => {
    S.auto = e.target.checked;
    if (S.auto) S.timer = setInterval(() => load(false), 25000);
    else clearInterval(S.timer);
  };
  $('#sigSeg').onclick = (e) => {
    const b = e.target.closest('.seg-btn');
    if (!b) return;
    S.sigFilter = b.dataset.f;
    $$('#sigSeg .seg-btn').forEach((x) => x.classList.toggle('active', x === b));
    renderSignals();
  };
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    closeManage();
    closeConfirm();
    closePortAdd();
    closePortOcr();
  });

  // 持仓
  $('#portAddBtn').onclick = openPortAdd;
  $('#portOcrBtn').onclick = openPortOcr;
  $('#paClose').onclick = closePortAdd;
  $('#paMask').onclick = closePortAdd;
  $('#paCancel').onclick = closePortAdd;
  $('#paSave').onclick = savePosition;
  $('#paModal').addEventListener('keydown', (e) => { if (e.key === 'Enter') savePosition(); });
  $('#poClose').onclick = () => { closePortOcr(); resetOcrModal(); };
  $('#poMask').onclick = () => { closePortOcr(); resetOcrModal(); };
  $('#poPick').onclick = () => $('#poFile').click();
  $('#poPick').addEventListener('dragover', (e) => { e.preventDefault(); $('#poPick').classList.add('drag'); });
  $('#poPick').addEventListener('dragleave', () => $('#poPick').classList.remove('drag'));
  $('#poPick').addEventListener('drop', (e) => {
    e.preventDefault(); $('#poPick').classList.remove('drag');
    if (e.dataTransfer.files.length) handleOcrFile(e.dataTransfer.files[0]);
  });
  $('#poFile').onchange = (e) => { if (e.target.files.length) handleOcrFile(e.target.files[0]); };
  $('#poImport').onclick = importOcr;

  // 标的管理
  $('#mktAddBtn').onclick = openManage;
  $('#mgClose').onclick = closeManage;
  $('#mgMask').onclick = closeManage;
  $('#mgInput').addEventListener('input', (e) => {
    clearTimeout(mgTimer);
    const v = e.target.value;
    mgTimer = setTimeout(() => mgSearch(v), 280);
  });
  $('#mgInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { clearTimeout(mgTimer); mgSearch(e.target.value); }
    if (e.key === 'Escape') closeManage();
  });
  $('#mgResults').onclick = (e) => {
    const b = e.target.closest('[data-add]');
    if (b) addInstrument(b.dataset.add);
  };
  $('#mgModal').onclick = (e) => {
    const del = e.target.closest('[data-del]');
    if (del) { removeInstrument(del.dataset.del); return; }
  };
  $('#cfCancel').onclick = closeConfirm;
  $('#cfMask').onclick = closeConfirm;
  $('#cfOk').onclick = () => {
    const code = cfPending;
    closeConfirm();
    if (code) removeInstrument(code);
  };

  let rt;
  window.addEventListener('resize', () => {
    clearTimeout(rt);
    rt = setTimeout(() => { if (S.data) renderAll(); }, 200);
  });

  load(true);
  updateDbStat();
  S.timer = setInterval(() => load(false), 25000);
  const h = location.hash.slice(1);
  if (['overview', 'a', 'hk', 'us', 'etf', 'fund', 'watch', 'port', 'signals', 'corr'].includes(h)) switchView(h);
  const open = new URLSearchParams(location.search).get('open');
  if (open) { location.href = 'stock.html?code=' + encodeURIComponent(open); return; }
  const mg = new URLSearchParams(location.search);
  if (mg.get('manage') === '1') {
    openManage();
    const q = mg.get('q');
    if (q) { $('#mgInput').value = q; mgSearch(q); }
  }
  if (mg.get('confirm')) {
    const c = mg.get('confirm');
    setTimeout(() => requestRemove(c), 1200);
  }
}

init();
