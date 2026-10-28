/* QuantBoard 前端逻辑 —— 原生 JS，自绘 Canvas 图表 */
'use strict';

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];

const QB = {};   // 自绘组件实例注册表（QBSelect 等）
const S = {
  data: null,
  view: 'overview',
  group: 'a',
  kind: 'all',
  sigFilter: 'all',
  sort: { key: 'pct', dir: -1 },
  watchSort: { key: 'pct', dir: -1 },
  watch: new Set(),
  mgSel: new Set(),
  auto: true,
  timer: null,
  loading: false,
  pendingForce: false,
  news: null,
  newsKind: 'all',
  port: null,
  trades: null,
  div: null,
  perf: null,
  perfDays: 180,
  ocrItems: [],
  ai: false,
  paSide: 'buy',
  family: null,
  famEditing: null,
  famMember: -1,      // 成员筛选：-1 = 全部，0 = 共有，>0 = 成员 id
  famFlowEdit: null,
  guru: null,         // 大佬持仓总览
  guruInv: 0,         // 机构筛选：0 = 全部
  guruQuarter: '',    // 报告期筛选：'' = 全局最新
  guruRows: null,     // 持仓明细（当前页）
  guruMeta: null,     // 分页元信息 {total,page,size,pages,from,to,total_yi}
  guruPage: 1,        // 持仓明细当前页
  guruSize: 100,      // 每页行数
  guruChg: null,      // 本期调仓（QoQ diff）
  guruCnTried: false, // 本次会话是否已尝试补标的中文名
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
  if (!S.data || !$('#mgCustomList')) return;
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
  const ok = await QBConfirm({
    title: '移出自定义标的',
    text: `确定从看板移除选中的 <b>${codes.length}</b> 个自定义标的？<br/>标的及其已存数据将被删除，且不可恢复。`,
    okText: '移出看板',
    danger: true,
  });
  if (!ok) return;
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

async function addInstrument(code, btn) {
  const label = btn ? btn.textContent : '';
  if (btn) { btn.disabled = true; btn.textContent = '校验行情…'; }
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
    if (btn) btn.textContent = '刷新看板…';
    await load(true);
    closeManage();
    updateDbStat();
  } catch (e) { console.warn('add failed', e); }
  finally { if (btn) { btn.disabled = false; btn.textContent = label; } }
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

/* 时钟：总览页实时时间 + 数据更新时间 */
function tickClock() {
  const el = $('#clockLine');
  if (!el) return;
  const n = new Date();
  const p2 = (x) => String(x).padStart(2, '0');
  const wd = '日一二三四五六'[n.getDay()];
  const date = `${n.getFullYear()} 年 ${n.getMonth() + 1} 月 ${n.getDate()} 日 · 星期${wd}`;
  const time = `${p2(n.getHours())}:${p2(n.getMinutes())}:${p2(n.getSeconds())}`;
  const ts = (S.data && S.data.ts) ? S.data.ts.slice(11, 19) : '';
  el.innerHTML = `<b>${time}</b> · ${date}` + (ts ? ` · 行情数据更新于 ${ts}` : '');
}

/* ---------------------------------------------------------------- 渲染：核心指数 */function renderIdx() {
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
    const trend = it.trend && it.trend.length > 1 ? it.trend : null;
    const sparkColor = trend
      ? (trend[trend.length - 1] >= it.trend_pre ? col('--up') : col('--dn'))
      : (it.pct >= 0 ? col('--up') : col('--dn'));
    requestAnimationFrame(() => drawSpark(cv, trend || it.spark, sparkColor));
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
  { k: 'spark', t: '盘中分时', align: 'c' },
  { k: 'price', t: '最新价' },
  { k: 'pct', t: '涨跌幅' },
  { k: 'amount_yi', t: '成交额' },
  { k: 'r20', t: '20 日' },
  { k: 'rsi', t: 'RSI' },
  { k: 'score', t: '评分' },
  { k: 'signal', t: '信号', align: 'c' },
];

/** 固定列宽（px）：表头与数据共用 colgroup，保证逐列对齐。
 *  宽度按「最宽内容 + 24px 内边距」留量，总和压在 1000 以内，窄屏才不至于横向滚动太多。 */
const MKT_COL_W = {
  name: 190, spark: 76, price: 92, pct: 80, amount_yi: 88, premium: 76, ext_pct: 76,
  r20: 80, rsi: 48, score: 96, signal: 60, m1: 84, y1: 84,
};


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
  // 美股分组插入夜盘列（盘前/盘后，来自新浪 gb_ 非常规时段行情）
  const showExt = S.group === 'us' && S.kind !== 'index';
  // 基金分组：净值/日涨幅/区间收益替代行情列
  const isFundGrp = S.group === 'fund';
  const cols = isFundGrp
    ? [{ k: 'name', t: '名称', align: 'l' }, { k: 'spark', t: '净值 45 日', align: 'c' }, { k: 'price', t: '单位净值' }, { k: 'pct', t: '日涨幅' },
       { k: 'm1', t: '近1月' }, { k: 'y1', t: '近1年' }, { k: 'r20', t: '20 日' },
       { k: 'score', t: '评分' }, { k: 'signal', t: '信号', align: 'c' }]
    : [...MKT_COLS.slice(0, 4),
       ...(showPrem ? [{ k: 'premium', t: '溢价率' }] : []),
       ...(showExt ? [{ k: 'ext_pct', t: '夜盘' }] : []),
       ...MKT_COLS.slice(4)];
  const premTd = (i) => {
    if (i.premium == null) return '<td>—</td>';
    const warn = Math.abs(i.premium) >= 1;
    return `<td class="${cls(i.premium)}"${warn ? ' style="font-weight:650"' : ''} title="较净值/IOPV">${pctTxt(i.premium)}</td>`;
  };
  const extTd = (i) => {
    if (i.ext_price == null) return '<td>—</td>';
    return `<td class="${cls(i.ext_pct)}" title="${esc(i.ext_tag || '夜盘')} ${px(i.ext_price)} ${pctTxt(i.ext_pct)} · ${esc(i.ext_time || '')}"><b>${pctTxt(i.ext_pct)}</b></td>`;
  };
  const retTd = (i, k) => {
    const v = (i.returns || {})[k];
    return `<td class="${cls(v)}">${pctTxt(v)}</td>`;
  };

  const rows = items.map((i) => {
    const a = i.ana || {};
    const midCells = isFundGrp
      ? retTd(i, 'm1') + retTd(i, 'y1')
      : `${showPrem ? premTd(i) : ''}${showExt ? extTd(i) : ''}<td>${amtTxt(i.amount_yi)}</td>`;
    return `<tr data-code="${i.code}">
      <td><div class="cell-name"><b title="${esc(i.name)}">${starHTML(i.code)}${esc(i.name)}${i.held ? ' <span class="chip hold" title="持仓标的，自动入池">持仓</span>' : ''}${delHTML(i.code)}</b><span>${esc(i.code)}</span></div></td>
      <td class="ta-c"><canvas class="spark-canvas" data-code="${i.code}"></canvas></td>
      <td class="${cls(i.pct)}">${px(i.price)}</td>
      <td class="${cls(i.pct)}"><b>${pctTxt(i.pct)}</b></td>
      ${midCells}
      <td class="${cls(a.r20)}">${pctTxt(a.r20)}</td>
      ${isFundGrp ? '' : `<td class="${a.rsi > 75 ? 'up' : a.rsi < 30 ? 'dn' : ''}">${a.rsi != null ? a.rsi.toFixed(0) : '—'}</td>`}
      <td><div class="score-cell"><span class="score-track"><i style="width:${a.score || 0}%;background:${scoreColor(a.score || 0)}"></i></span><span class="small mk-score">${a.score != null ? a.score.toFixed(0) : '—'}</span></div></td>
      <td class="ta-c">${a.signal ? `<span class="chip ${a.signal_cls}">${a.signal}</span>` : '—'}</td>
    </tr>`;
  }).join('');

  const colgroup = `<colgroup>${cols.map((c) =>
    MKT_COL_W[c.k] ? `<col style="width:${MKT_COL_W[c.k]}px">` : '<col>').join('')}</colgroup>`;
  $('#mktTable').innerHTML = `${colgroup}<thead><tr>${cols.map((c) => {
    const sorted = S.sort.key === c.k ? 'sorted' : '';
    const arrow = S.sort.key === c.k ? (S.sort.dir < 0 ? ' ↓' : ' ↑') : '';
    const al = c.align === 'l' ? 'left' : c.align === 'c' ? 'center' : 'right';
    return `<th class="${sorted}" data-k="${c.k}" style="text-align:${al}">${c.t}${arrow}</th>`;
  }).join('')}</tr></thead><tbody>${rows}</tbody>`;

  $$('#mktTable th').forEach((th) => (th.onclick = () => {
    const k = th.dataset.k;
    if (!['price', 'pct', 'amount_yi', 'r20', 'rsi', 'score', 'm1', 'y1'].includes(k) &&
        !(k === 'premium' && showPrem) && !(k === 'ext_pct' && showExt)) return;
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

/* 迷你走势 = 当天分时（/api/trend 按需拉取，客户端缓存 60s；基金仍用净值 45 日） */
const TREND_MEM = new Map();    // code → { ts, prices, preClose }
const TREND_WAIT = new Map();   // code → 等待重画的回调
const TREND_QUEUE = [];
let TREND_ACTIVE = 0;

function bindSparks(scope) {
  $$(scope + ' canvas.spark-canvas').forEach((cv) => {
    const code = cv.dataset.code;
    let it = null;
    for (const g of Object.values(S.data.groups)) {
      it = g.items.find((x) => x.code === code);
      if (it) break;
    }
    if (!it) return;
    const fallback = () =>
      requestAnimationFrame(() => drawSpark(cv, it.spark, it.pct >= 0 ? col('--up') : col('--dn')));
    if (it.kind === 'fund') { fallback(); return; }   // 基金没有分时，保持净值 45 日
    const hit = TREND_MEM.get(code);
    if (hit && Date.now() - hit.ts < 60_000) { paintTrendSpark(cv, hit, it); return; }
    fallback();                                        // 分时未到先画 45 日兜底
    if (!TREND_WAIT.has(code)) {
      TREND_WAIT.set(code, []);
      TREND_QUEUE.push(code);
      pumpTrend();
    }
    TREND_WAIT.get(code).push(() => {
      const h = TREND_MEM.get(code);
      if (h) paintTrendSpark(cv, h, it);
    });
  });
}

function paintTrendSpark(cv, hit, it) {
  const p = hit.prices || [];
  requestAnimationFrame(() => {
    if (p.length > 1) {
      const base = hit.preClose != null && hit.preClose > 0 ? hit.preClose : p[0];
      drawSpark(cv, p, p[p.length - 1] >= base ? col('--up') : col('--dn'));
    } else {
      drawSpark(cv, it.spark, it.pct >= 0 ? col('--up') : col('--dn'));
    }
  });
}

function pumpTrend() {
  while (TREND_ACTIVE < 4 && TREND_QUEUE.length) {
    const code = TREND_QUEUE.shift();
    TREND_ACTIVE++;
    fetch('/api/trend?code=' + encodeURIComponent(code))
      .then((r) => r.json())
      .then((d) => { TREND_MEM.set(code, { ts: Date.now(), prices: d.trend || [], preClose: d.preClose }); })
      .catch(() => { TREND_MEM.set(code, { ts: Date.now(), prices: [], preClose: null }); })
      .finally(() => {
        TREND_ACTIVE--;
        (TREND_WAIT.get(code) || []).forEach((fn) => { try { fn(); } catch (e) { /* 画布可能已被重绘替换 */ } });
        TREND_WAIT.delete(code);
        pumpTrend();
      });
  }
}

/* ---------------------------------------------------------------- 渲染：信号榜 */
function renderSignals() {
  const all = S.data.ranking.all.filter((r) => S.sigFilter === 'all' || r.group === S.sigFilter);
  const head = `<thead><tr>
    <th style="text-align:left">标的</th><th>分组</th><th>最新价</th><th>今日</th><th>夜盘</th>
    <th>20 日</th><th>RSI</th><th>超额</th><th>评分</th><th>信号</th><th style="text-align:left;min-width:280px">技术面结论</th>
  </tr></thead>`;
  const body = all.map((r, idx) => `<tr data-code="${r.code}">
    <td><div class="cell-name"><b>${starHTML(r.code)}${esc(r.name)}${r.held ? ' <span class="chip hold">持仓</span>' : ''}${delHTML(r.code)}</b><span>${esc(r.code)}</span></div></td>
    <td><span class="grp">${r.group_label}</span></td>
    <td class="${cls(r.pct)}">${px(r.price)}</td>
    <td class="${cls(r.pct)}"><b>${pctTxt(r.pct)}</b></td>
    <td class="${cls(r.ext_pct)}" title="${r.ext_price != null ? `${esc(r.ext_tag || '夜盘')} ${px(r.ext_price)} ${pctTxt(r.ext_pct)} · ${esc(r.ext_time || '')}` : ''}">${r.ext_pct != null ? pctTxt(r.ext_pct) : '—'}</td>
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
    return;
  }

  const wCols = [
    { k: 'name', t: '标的', align: 'l' }, { k: null, t: '分组' }, { k: 'price', t: '最新价' },
    { k: 'pct', t: '今日' }, { k: 'ext_pct', t: '夜盘' }, { k: 'r20', t: '20 日' }, { k: 'rsi', t: 'RSI' },
    { k: 'score', t: '评分' }, { k: null, t: '信号' }, { k: null, t: '技术面结论', align: 'l' },
  ];
  const wk = S.watchSort;
  const wGet = (r) => {
    if (wk.key === 'score') return r.score ?? (r.ana || {}).score ?? -1;
    if (wk.key === 'rsi') return r.rsi ?? (r.ana || {}).rsi ?? -1;
    if (wk.key === 'r20') return r.r20 ?? (r.ana || {}).r20 ?? -999;
    return r[wk.key] ?? 0;
  };
  // 股票与指数合并后统一排序（指数行原样渲染）
  const rowsData = [
    ...picks.map((r) => ({ r, idx: false })),
    ...idxOnly.map((r) => ({ r, idx: true })),
  ].sort((a, b) => (wGet(a.r) - wGet(b.r)) * (wk.dir < 0 ? -1 : 1));

  const head = `<thead><tr>${wCols.map((c) => {
    const on = c.k && wk.key === c.k;
    return `<th ${c.k && c.k !== 'name' ? `class="th-sort" data-wk="${c.k}"` : ''}
      style="text-align:${c.align === 'l' ? 'left' : 'right'};${c.t === '技术面结论' ? 'min-width:260px;' : ''}${on ? 'color:var(--accent)' : ''}">${c.t}${on ? (wk.dir < 0 ? ' ↓' : ' ↑') : ''}</th>`;
  }).join('')}</tr></thead>`;
  const row = (r) => `<tr data-code="${r.code}">
    <td><div class="cell-name"><b>${starHTML(r.code)}${esc(r.name)}${r.held ? ' <span class="chip hold">持仓</span>' : ''}${delHTML(r.code)}</b><span>${esc(r.code)}</span></div></td>
    <td><span class="grp">${r.group_label || grpLabel(r.group)}</span></td>
    <td class="${cls(r.pct)}">${px(r.price)}</td>
    <td class="${cls(r.pct)}"><b>${pctTxt(r.pct)}</b></td>
    <td class="${cls(r.ext_pct)}" title="${r.ext_price != null ? `${esc(r.ext_tag || '夜盘')} ${px(r.ext_price)} ${pctTxt(r.ext_pct)} · ${esc(r.ext_time || '')}` : ''}">${r.ext_pct != null ? pctTxt(r.ext_pct) : '—'}</td>
    <td class="${cls(r.r20)}">${pctTxt(r.r20)}</td>
    <td class="${r.rsi > 75 ? 'up' : r.rsi < 30 ? 'dn' : ''}">${r.rsi != null ? r.rsi.toFixed(0) : '—'}</td>
    <td><span style="font-weight:650;color:${scoreColor(r.score)}">${r.score.toFixed(0)}</span></td>
    <td><span class="chip ${r.signal_cls}">${r.signal}</span></td>
    <td style="text-align:left;white-space:normal;font-size:11.5px;color:var(--txt-2);max-width:320px">${esc(r.conclusion || '—')}</td>
  </tr>`;
  const idxRow = (i) => {
    const a = i.ana || {};
    return `<tr data-code="${i.code}">
    <td><div class="cell-name"><b>${starHTML(i.code)}${esc(i.name)}${delHTML(i.code)}</b><span>${esc(i.code)}</span></div></td>
    <td><span class="grp">指数</span></td>
    <td class="${cls(i.pct)}">${px(i.price)}</td>
    <td class="${cls(i.pct)}"><b>${pctTxt(i.pct)}</b></td>
    <td>—</td>
    <td class="${cls(a.r20)}">${pctTxt(a.r20)}</td>
    <td class="${a.rsi > 75 ? 'up' : a.rsi < 30 ? 'dn' : ''}">${a.rsi != null ? a.rsi.toFixed(0) : '—'}</td>
    <td><span style="font-weight:650;color:${scoreColor(a.score || 0)}">${(a.score || 0).toFixed(0)}</span></td>
    <td><span class="chip ${a.signal_cls || 'neutral'}">${a.signal || '—'}</span></td>
    <td style="text-align:left;white-space:normal;font-size:11.5px;color:var(--txt-2)">${esc(a.conclusion || '—')}</td>
  </tr>`;
  };
  $('#watchTable').innerHTML = head + `<tbody>${rowsData.map(({ r, idx }) => (idx ? idxRow(r) : row(r))).join('')}</tbody>`;
  $('#watchTable').querySelectorAll('thead th.th-sort').forEach((th) => (th.onclick = () => {
    const k = th.dataset.wk;
    S.watchSort = { key: k, dir: wk.key === k ? -wk.dir : -1 };
    renderWatch();
  }));
  $('#watchTable').querySelectorAll('tbody tr').forEach((tr) => (tr.onclick = (e) => {
    if (e.target.closest('.star')) return;
    const del = e.target.closest('.row-del');
    if (del) { e.stopPropagation(); requestRemove(del.dataset.del); return; }
    openDrawer(tr.dataset.code, e);
  }));
  $('#watchHint').textContent = `共 ${s.n} 个 · 存于 MySQL，跨会话保留`;
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

/** 股息率单元：实收流水优先，美股没有流水时用「近年除息推算」的参考值，前面加 ~ 区分。 */
function divRateCell(it) {
  if (it.div_ttm > 0) return it.div_rate_cost ? it.div_rate_cost.toFixed(2) + '%' : '—';
  if (it.div_ttm_est > 0 && it.div_rate_mv_est) {
    return `<span class="est" title="按该股近年除息记录推算的年化股息率（参考，未实际入账）">~${it.div_rate_mv_est.toFixed(2)}%</span>`;
  }
  return '<span class="small">—</span>';
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

  // 汇总：总收益 = 浮盈 + 已实现 + 股息（统一折人民币），再按券商拆分
  const gd = S.port.grand_cny_detail;
  const parts = [];
  const sgn = (v) => (v > 0 ? '+' : v < 0 ? '-' : '');
  if (gd) {
    const total = gd.total != null ? gd.total : gd.pl;
    const base = Math.abs(total) || 1;
    const w = (v) => Math.max(0, Math.min(100, Math.abs(v) / base * 100));
    parts.push(`<div class="stat"><span>总收益（浮盈 + 已实现 + 股息）</span>
      <b class="${cls(total)}">${sgn(total)}¥${fmt(Math.abs(total), 2)}</b>
      <div class="attr-bar">
        <i class="up" style="width:${w(gd.pl)}%" title="浮盈"></i>
        <i class="realized" style="width:${w(gd.realized)}%" title="已实现"></i>
        <i class="div" style="width:${w(gd.div_ttm)}%" title="近12月股息"></i>
      </div>
      <span class="small">浮盈 ${fmt(gd.pl / base * 100, 0)}% · 已实现 ${fmt(gd.realized / base * 100, 0)}% · 股息 ${fmt(gd.div_ttm / base * 100, 0)}%</span></div>
      <div class="stat"><span>未实现 · 浮盈</span><b class="${cls(gd.pl)}">${sgn(gd.pl)}¥${fmt(Math.abs(gd.pl), 2)}
        <span class="small ${cls(gd.pl)}">(${pctTxt(gd.pl_pct)})</span></b></div>
      <div class="stat"><span>已实现 · 卖出落袋</span><b class="${cls(gd.realized)}">${sgn(gd.realized)}¥${fmt(Math.abs(gd.realized), 2)}</b></div>
      <div class="stat"><span>股息 · 近 12 个月</span><b class="${cls(gd.div_ttm)}">${sgn(gd.div_ttm)}¥${fmt(Math.abs(gd.div_ttm), 2)}
        <span class="small">累计 ¥${fmt(gd.div_total || 0, 0)}</span></b></div>
      <div class="stat"><span>总市值 · 约合人民币</span><b>¥${fmt(gd.mv, 0)}</b>
        <span class="small">今日 ${sgn(gd.day_pl)}¥${fmt(Math.abs(gd.day_pl), 2)}</span></div>`);
  }
  // 按券商拆分（原币 + 折人民币），折算与占比全部由后端收益引擎给出，前端不再自己算
  for (const b of brokers) {
    const tots = b.totals || [];
    const mvLines = tots.map((t) => `${curSym(t.currency)}${fmt(t.mv, 2)}
      <span class="small">≈¥${fmt(t.mv * (t.fx || 1), 0)}</span>`).join(' · ');
    const plLines = tots.map((t) => `<b class="${cls(t.pl)}" style="font-weight:600">${sgn(t.pl)}${curSym(t.currency)}${fmt(Math.abs(t.pl), 0)}</b>`).join(' ');
    const reLines = tots.filter((t) => t.realized).map((t) => `<b class="${cls(t.realized)}" style="font-weight:600">${sgn(t.realized)}${curSym(t.currency)}${fmt(Math.abs(t.realized), 0)}</b>`).join(' ');
    const dvLines = tots.filter((t) => t.div_total).map((t) => `<b class="${cls(t.div_total)}" style="font-weight:600">${sgn(t.div_total)}${curSym(t.currency)}${fmt(Math.abs(t.div_total), 0)}</b>`).join(' ');
    parts.push(`<div class="stat"><span>${esc(b.broker)} · ${b.items.length} 只 · 占比 ${b.share != null ? b.share.toFixed(1) : '—'}%</span>
      <b style="font-size:15px">${mvLines}</b>
      <span class="small">浮盈 ${plLines}${reLines ? ' · 已实现 ' + reLines : ''}${dvLines ? ' · 股息 ' + dvLines : ''}</span></div>`);
  }
  if (gd && (S.port.fx.USD !== 1 || S.port.fx.HKD !== 1)) {
    const f = S.port.fx || {};
    const tag = f.as_of ? `（${f.as_of.slice(5)}${f.stale ? ' · 已过期' : ''}）` : '（静态）';
    parts.push(`<div class="stat"><span>汇率${tag}</span><b style="font-size:14px">HKD ${f.HKD} / USD ${f.USD}</b></div>`);
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
        <td class="${cls(it.day_pl)}">${it.live
          ? `<b>${sgn(it.day_pl)}${fmt(Math.abs(it.day_pl), 2)}</b> <span class="small ${cls(it.day_pl)}">${pctTxt(it.pct)}</span>`
          : '—'}</td>
        <td>${curSym(it.currency)}${fmt(it.mv, 2)}</td>
        <td class="${cls(it.pl)}"><b>${sgn(it.pl)}${fmt(Math.abs(it.pl), 2)}</b> <span class="small ${cls(it.pl)}">${pctTxt(it.pl_pct)}</span></td>
        <td class="small ${it.realized ? cls(it.realized) : ''}">${it.realized ? sgn(it.realized) + fmt(Math.abs(it.realized), 0) : '—'}</td>
        <td class="small ${it.div_total ? cls(it.div_total) : ''}">${it.div_total ? curSym(it.currency) + fmt(it.div_total, 0) : '—'}</td>
        <td>${divRateCell(it)}</td>
        <td>${it.score != null ? `<span style="font-weight:650;color:${scoreColor(it.score)}">${it.score.toFixed(0)}</span>` : '—'}</td>
        <td>${it.signal ? `<span class="chip ${signalCls(it.signal)}">${it.signal}</span>` : '—'}</td>
        <td><span class="row-del" data-poskey="${esc(it.pos_key)}" data-name="${esc(it.name)}" title="删除该标的全部流水">✕</span></td>
      </tr>`;
    }).join('');
    const closed = (b.closed || []).map((it) => `<tr class="closed-row">
        <td><div class="cell-name"><b>${esc(it.name)}</b><span>${esc(it.code.toUpperCase())}</span></div></td>
        <td colspan="6" class="small">已清仓 · ${it.trades} 笔流水</td>
        <td class="small ${cls(it.realized)}">${it.realized ? sgn(it.realized) + fmt(Math.abs(it.realized), 0) : '—'}</td>
        <td class="small">${it.div_total ? curSym(it.currency) + fmt(it.div_total, 0) : '—'}</td>
        <td colspan="4" class="small">—</td>
      </tr>`).join('');
    return `<div class="panel port-panel">
      <div class="panel-head"><h2>${esc(b.broker)}</h2><span class="hint">${b.items.length} 个持仓${b.closed && b.closed.length ? ' · ' + b.closed.length + ' 个已清仓' : ''}</span>
        <span class="port-tots">${tot}</span></div>
      <div class="table-wrap"><table class="tbl">
        <thead><tr><th style="text-align:left">标的</th><th>数量</th><th>成本价</th><th>现价</th>
        <th>今日盈亏</th><th>市值</th><th>持仓盈亏</th><th>已实现</th><th>已收股息</th><th>股息率</th>
        <th>评分</th><th>信号</th><th></th></tr></thead>
        <tbody>${rows}${closed}</tbody></table></div></div>`;
  }).join('');

  $$('#portBrokers tbody tr').forEach((tr) => (tr.onclick = (e) => {
    const del = e.target.closest('[data-poskey]');
    if (del) {
      e.stopPropagation();
      delPosition(del.dataset.poskey, del.dataset.name);
      return;
    }
    if (e.target.closest('.row-del')) return;
    const code = tr.dataset.code;
    if (S.data && Object.values(S.data.groups).some((g2) => g2.items.some((x) => x.code === code))) openDrawer(code);
  }));
}

function signalCls(sig) {
  return { 强势: 'up', 偏强: 'up', 中性: 'neutral', 偏弱: 'dn', 弱势: 'dn' }[sig] || 'neutral';
}

/* ------------------------------------------------------------- 家庭总览 */
const FAM_TYPE = { cash: '现金', deposit: '存款', fund: '场外基金', other: '其他', liability: '负债' };
const FAM_COLOR = { a: '#3355e0', hk: '#f0a63d', us: '#b26cf0', etf_fund: '#3aa981', cash_other: '#8a93a3' };
const FAM_COLOR_DARK = { a: '#7b93ff', hk: '#e0a63a', us: '#c88ff5', etf_fund: '#1fc98a', cash_other: '#6c7686' };

async function loadFamily() {
  try {
    const r = await fetch('/api/family');
    S.family = await r.json();
  } catch (e) { S.family = { ready: false, categories: [], manual: [] }; }
  renderFamily();
}

function famColors() {
  const dark = document.documentElement.dataset.theme === 'dark';
  return dark ? FAM_COLOR_DARK : FAM_COLOR;
}

function renderFamily() {
  const d = S.family;
  const stats = $('#famStats');
  if (!d || !d.categories) return;

  if (d.ready === false) {
    stats.innerHTML = '';
    $('#famTable').innerHTML = '';
    $('#famTargets').innerHTML = '<div class="hint" style="padding:14px 2px">MySQL 未连接，家庭资产不可用（需 Docker Desktop 运行 mysql 容器）</div>';
    return;
  }

  const sgn = (v) => (v > 0 ? '+' : v < 0 ? '-' : '');
  const secCny = (d.holdings || []).reduce((s, h) => s + (h.mv_cny || 0), 0);
  const ft = d.flow_totals || {};
  stats.innerHTML = [
    `<div class="stat"><span>总资产 · 约合人民币</span><b>¥${fmt(d.assets, 0)}</b>
      <span class="small">其中证券 ¥${fmt(secCny, 0)}</span></div>`,
    `<div class="stat"><span>总负债</span><b class="${d.liabilities > 0 ? 'dn' : ''}">¥${fmt(d.liabilities, 0)}</b></div>`,
    `<div class="stat"><span>家庭净值</span><b>¥${fmt(d.net, 0)}</b>
      <span class="small">总资产 − 总负债</span></div>`,
    `<div class="stat"><span>本月结余 · 收入 − 支出</span><b class="${(ft.month_income || 0) - (ft.month_expense || 0) >= 0 ? 'up' : 'dn'}">
      ${sgn((ft.month_income || 0) - (ft.month_expense || 0))}¥${fmt(Math.abs((ft.month_income || 0) - (ft.month_expense || 0)), 0)}</b>
      <span class="small">收 ¥${fmt(ft.month_income || 0, 0)} · 支 ¥${fmt(ft.month_expense || 0, 0)}</span></div>`,
    `<div class="stat"><span>本年 / 累计结余</span>
      <b class="${(ft.year_income || 0) - (ft.year_expense || 0) >= 0 ? 'up' : 'dn'}">
      ${sgn((ft.year_income || 0) - (ft.year_expense || 0))}¥${fmt(Math.abs((ft.year_income || 0) - (ft.year_expense || 0)), 0)}</b>
      <span class="small">累计 ${sgn((ft.all_income || 0) - (ft.all_expense || 0))}¥${fmt(Math.abs((ft.all_income || 0) - (ft.all_expense || 0)), 0)}</span></div>`,
    `<div class="stat"><span>目标合计</span><b
      style="color:${Math.abs(d.target_sum - 100) > 0.01 ? 'var(--warn)' : 'inherit'}">${fmt(d.target_sum, 1)}%</b>
      <span class="small">${Math.abs(d.target_sum - 100) > 0.01 ? '偏离 100%，请调整' : '五大类目标占比'}</span></div>`,
  ].join('');

  // 成员筛选 seg（过滤成员表之外的台账 / 收支）
  const members = d.members || [];
  $('#famMemberSeg').innerHTML =
    `<button class="seg-btn ${S.famMember === -1 ? 'active' : ''}" data-m="-1">全部</button>` +
    members.map((m) => `<button class="seg-btn ${S.famMember === m.id ? 'active' : ''}" data-m="${m.id}">${esc(m.name)}</button>`).join('');

  drawFamilyPie($('#famPie'), d.categories);
  $('#famPieHint').textContent = `折人民币 · ${fmt(d.assets, 0)} 元`;

  drawFamMonthly($('#famMonthly'), d.monthly || []);
  renderFamCats(d.flow_categories || {});
  drawFamNav($('#famNav'), d.nav || []);

  // 图例
  $('#famLegend').innerHTML = d.categories.map((c) => `
    <div class="fam-leg"><i style="background:${famColors()[c.bucket]}"></i>
      <span>${esc(c.label)}</span>
      <b>¥${fmt(c.mv_cny, 0)}</b>
      <em>${c.share.toFixed(1)}%</em></div>`).join('');

  // 目标偏离：每行 当前占比 vs 目标输入
  $('#famTargets').innerHTML = d.categories.map((c) => {
    const t = c.target || 0;
    const diff = c.diff;
    const w = Math.min(100, Math.abs(diff || 0) * 4);   // ±25pp 拉满
    return `<div class="fam-tgt">
      <span class="fam-tgt-name"><i style="background:${famColors()[c.bucket]}"></i>${esc(c.label)}</span>
      <span class="fam-tgt-cur"><b>${c.share.toFixed(1)}%</b><span class="small">目标
        <input type="number" class="fam-tgt-in" data-bucket="${c.bucket}" value="${t}" min="0" max="100" step="1" /></span></span>
      ${diff == null || t <= 0 ? '<span class="fam-tgt-diff small">未设目标</span>'
        : `<span class="fam-tgt-diff"><span class="fam-diffbar"><i style="width:${w}%;background:${Math.abs(diff) > 5 ? 'var(--warn)' : 'var(--txt-3)'}"></i></span>
           <b class="${diff > 0 ? 'up' : diff < 0 ? 'dn' : ''}" style="font-weight:600">${sgn(diff)}${fmt(Math.abs(diff), 1)}pp</b></span>`}
    </div>`;
  }).join('') + `<div class="small" style="padding:10px 2px 0;color:var(--txt-3)">
    偏离 = 当前占比 − 目标占比（单位 pp）；偏离超过 ±5pp 标黄提醒。占现金/存款的空仓也是配置的一部分。</div>`;

  // 成员汇总表
  $('#famMembersTable').innerHTML = `<thead><tr>
    <th style="text-align:left">成员</th><th>台账余额（折 CNY）</th><th>本月收入</th><th>本月支出</th><th>本月结余</th>
  </tr></thead><tbody>` +
    members.map((m) => `<tr>
      <td style="text-align:left"><b>${esc(m.name)}</b>${m.id === 0 ? ' <span class="small">（家庭公共）</span>' : ''}</td>
      <td>¥${fmt(m.balance, 0)}</td>
      <td class="up" style="font-weight:600">¥${fmt(m.month_income, 0)}</td>
      <td class="dn" style="font-weight:600">¥${fmt(m.month_expense, 0)}</td>
      <td class="${cls(m.month_net)}" style="font-weight:600">${sgn(m.month_net)}¥${fmt(Math.abs(m.month_net), 0)}</td>
    </tr>`).join('') + `</tbody>`;

  // 收支台账（按成员筛选）
  const flows = (d.flows || []).filter((f) => S.famMember === -1 || Number(f.member_id) === S.famMember);
  $('#famFlowTable').innerHTML = `<thead><tr>
    <th style="text-align:left">日期</th><th>成员</th><th>类型</th><th style="text-align:left">分类</th>
    <th>金额（原币）</th><th>折 CNY</th><th style="text-align:left">备注</th><th></th></tr></thead><tbody>` +
    (flows.length ? flows.map((f) => {
      const fx = f.currency === 'HKD' ? d.fx.HKD : f.currency === 'USD' ? d.fx.USD : 1;
      const inc = f.ftype === 'income';
      return `<tr data-flow="${f.id}">
      <td style="text-align:left">${esc(String(f.flow_date).slice(0, 10))}</td>
      <td>${esc(f.member || '共有')}</td>
      <td><span class="grp ${inc ? 'fam-income' : 'fam-expense'}">${inc ? '收入' : '支出'}</span></td>
      <td style="text-align:left">${esc(f.category || '—')}</td>
      <td class="${inc ? 'up' : 'dn'}" style="font-weight:600">${inc ? '+' : '−'}${curSym(f.currency)}${fmt(f.amount, 2)}</td>
      <td>${inc ? '+' : '−'}¥${fmt(f.amount * fx, 0)}</td>
      <td class="small" style="text-align:left">${esc(f.note || '—')}</td>
      <td><span class="row-del" data-flowdel="${f.id}" title="删除该笔收支">✕</span></td>
    </tr>`;
    }).join('') : `<tr><td colspan="8" style="text-align:center;padding:28px 0;color:var(--txt-3);font-size:12.5px">
      还没有收支记录 —— 点右上「＋ 新增收支」录入工资、日常开销等</td></tr>`) + `</tbody>`;
  $$('#famFlowTable [data-flowdel]').forEach((el) => (el.onclick = (e) => {
    e.stopPropagation();
    delFamilyFlow(el.dataset.flowdel);
  }));
  $$('#famFlowTable tbody tr[data-flow]').forEach((tr) => (tr.onclick = (e) => {
    if (e.target.closest('[data-flowdel]')) return;
    const row = (d.flows || []).find((f) => String(f.id) === tr.dataset.flow);
    if (row) openFamilyFlow(row);
  }));

  // 非证券科目台账（按成员筛选 + 成员列）
  const rows = (d.manual || []).filter((r) => S.famMember === -1 || Number(r.member_id) === S.famMember);
  $('#famTable').innerHTML = `<thead><tr>
    <th style="text-align:left">科目</th><th>成员</th><th>类型</th><th>金额（原币）</th><th>约合人民币</th>
    <th style="text-align:left">备注</th><th>更新时间</th><th></th></tr></thead><tbody>` +
    (rows.length ? rows.map((r) => {
      const fx = r.currency === 'HKD' ? d.fx.HKD : r.currency === 'USD' ? d.fx.USD : 1;
      return `<tr data-id="${r.id}">
      <td><div class="cell-name"><b>${esc(r.name || FAM_TYPE[r.atype] || r.atype)}</b></div></td>
      <td>${esc(r.member || '共有')}</td>
      <td><span class="grp ${r.atype === 'liability' ? 'fam-liab' : ''}">${FAM_TYPE[r.atype] || esc(r.atype)}</span></td>
      <td>${curSym(r.currency)}${fmt(r.amount, 2)}</td>
      <td>${curSym('CNY')}${fmt(r.amount * fx, 0)}</td>
      <td class="small" style="text-align:left">${esc(r.note || '—')}</td>
      <td class="small">${esc(String(r.updated_at || '').slice(0, 16))}</td>
      <td><span class="row-del" data-famdel="${r.id}" title="删除该科目">✕</span></td>
    </tr>`;
    }).join('') : `<tr><td colspan="8" style="text-align:center;padding:28px 0;color:var(--txt-3);font-size:12.5px">
      还没有非证券科目 —— 点右上「＋ 记一笔」添加现金 / 存款 / 场外基金 / 负债</td></tr>`) +
    `</tbody>`;
  $$('#famTable [data-famdel]').forEach((el) => (el.onclick = (e) => {
    e.stopPropagation();
    delFamily(el.dataset.famdel);
  }));
  $$('#famTable tbody tr[data-id]').forEach((tr) => (tr.onclick = (e) => {
    if (e.target.closest('[data-famdel]')) return;
    const row = (d.manual || []).find((r) => String(r.id) === tr.dataset.id);
    if (row) openFamilyAdd(row);
  }));
}

/** 近 12 个月收支双柱图（折人民币，明暗主题自适应）。 */
function drawFamMonthly(canvas, rows) {
  const { ctx, w, h } = hdpi(canvas);
  ctx.clearRect(0, 0, w, h);
  if (!rows.length) {
    ctx.fillStyle = col('--txt-3');
    ctx.font = '12px -apple-system, sans-serif';
    ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.fillText('暂无收支数据', w / 2, h / 2);
    return;
  }
  const up = col('--up'), dn = col('--dn'), grid = col('--grid'), t3 = col('--txt-3');
  const padL = 4, padR = 44, padT = 8, padB = 20;
  const H = h - padT - padB;
  const max = Math.max(...rows.flatMap((r) => [r.income, r.expense])) || 1;
  const gw = (w - padL - padR) / rows.length;
  const bw = Math.max(3, Math.min(14, gw * 0.26));

  ctx.font = '10px -apple-system, sans-serif';
  ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
  for (let g = 0; g <= 3; g++) {
    const v = (max / 3) * g, yy = padT + H - (H / 3) * g;
    ctx.strokeStyle = grid; ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(padL, yy + .5); ctx.lineTo(w - padR, yy + .5); ctx.stroke();
    ctx.fillStyle = t3;
    ctx.fillText(v >= 10000 ? fmt(v / 10000, 1) + '万' : fmt(v, 0), w - padR + 5, yy);
  }
  rows.forEach((r, i) => {
    const cx = padL + gw * (i + 0.5);
    const hi = (r.income / max) * H, he = (r.expense / max) * H;
    ctx.fillStyle = up;
    ctx.fillRect(cx - bw - 1, padT + H - hi, bw, Math.max(1, hi));
    ctx.fillStyle = dn;
    ctx.fillRect(cx + 1, padT + H - he, bw, Math.max(1, he));
    ctx.fillStyle = t3;
    ctx.textAlign = 'center';
    ctx.fillText(r.month.slice(5), cx, padT + H + 11);
  });
  ctx.textAlign = 'left';
}

/** 本月收支分类构成条目。 */
function renderFamCats(cats) {
  const box = $('#famCats');
  const hint = $('#famCatHint');
  const hasAny = (cats.income || []).length || (cats.expense || []).length;
  hint.textContent = hasAny ? `${cats.month} · 各取 Top 8` : '';
  if (!hasAny) {
    box.innerHTML = '<div class="hint" style="padding:14px 2px">本月暂无收支记录</div>';
    return;
  }
  const section = (title, items, colorVar) => {
    if (!items.length) return `<div class="fam-cat-title small">${title}：无</div>`;
    const max = Math.max(...items.map((i) => i.amount_cny)) || 1;
    return `<div class="fam-cat-title small">${title}</div>` + items.map((i) => `
      <div class="fam-cat">
        <span class="fam-cat-name">${esc(i.category)}</span>
        <span class="fam-cat-bar"><i style="width:${Math.max(2, i.amount_cny / max * 100)}%;background:var(${colorVar})"></i></span>
        <b>¥${fmt(i.amount_cny, 0)}</b>
      </div>`).join('');
  };
  box.innerHTML = section('收入', cats.income || [], '--up')
    + `<div style="height:8px"></div>` + section('支出', cats.expense || [], '--dn');
}

/** 家庭净值曲线：净值（主线）+ 总资产（灰线），近 180 天。 */
function drawFamNav(canvas, pts) {
  const { ctx, w, h } = hdpi(canvas);
  ctx.clearRect(0, 0, w, h);
  if (pts.length < 2) {
    ctx.fillStyle = col('--txt-3');
    ctx.font = '12px -apple-system, sans-serif';
    ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.fillText(pts.length ? '快照积累中，明天起出现曲线（今日快照已落库）' : '暂无快照，打开本页即自动落当日快照', w / 2, h / 2);
    return;
  }
  const grid = col('--grid'), t3 = col('--txt-3'), accent = col('--accent');
  const padL = 4, padR = 56, padT = 8, padB = 6;
  const H = h - padT - padB;
  let lo = Infinity, hi = -Infinity;
  pts.forEach((p) => {
    lo = Math.min(lo, p.net, p.assets); hi = Math.max(hi, p.net, p.assets);
  });
  const pad = (hi - lo || hi || 1) * 0.06;
  lo -= pad; hi += pad;
  const x = (i) => padL + (i / (pts.length - 1)) * (w - padL - padR);
  const y = (v) => padT + (1 - (v - lo) / (hi - lo)) * H;

  ctx.font = '10px -apple-system, sans-serif';
  ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
  for (let g = 0; g <= 3; g++) {
    const v = hi - ((hi - lo) / 3) * g, yy = y(v);
    ctx.strokeStyle = grid; ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(padL, yy + .5); ctx.lineTo(w - padR, yy + .5); ctx.stroke();
    ctx.fillStyle = t3;
    ctx.fillText(Math.abs(v) >= 10000 ? fmt(v / 10000, 1) + '万' : fmt(v, 0), w - padR + 5, yy);
  }

  const line = (key, color, width) => {
    ctx.beginPath();
    pts.forEach((p, i) => (i ? ctx.lineTo(x(i), y(p[key])) : ctx.moveTo(x(i), y(p[key]))));
    ctx.strokeStyle = color; ctx.lineWidth = width; ctx.lineJoin = 'round'; ctx.stroke();
  };
  line('assets', t3, 1);
  line('net', accent, 1.6);

  const last = pts[pts.length - 1], prev = pts[pts.length - 2];
  const lc = last.net >= prev.net ? col('--up') : col('--dn');
  ctx.beginPath(); ctx.arc(x(pts.length - 1), y(last.net), 2.8, 0, Math.PI * 2);
  ctx.fillStyle = lc; ctx.fill();
  ctx.fillStyle = t3; ctx.textAlign = 'center';
  ctx.fillText(String(last.date).slice(5), x(pts.length - 1) - 4 > padL + 20 ? x(pts.length - 1) : padL + 18, h - 1);
  ctx.textAlign = 'left';
}

/** 大类分布环形图（Canvas 自绘，明暗主题自适应）。 */
function drawFamilyPie(canvas, cats) {  const { ctx, w, h } = hdpi(canvas);
  ctx.clearRect(0, 0, w, h);
  const total = cats.reduce((s, c) => s + c.mv_cny, 0);
  const cx = w / 2, cy = h / 2, R = Math.min(w, h) / 2 - 6, r0 = R * 0.62;
  if (total <= 0) {
    ctx.fillStyle = col('--txt-3');
    ctx.font = '12px -apple-system, sans-serif';
    ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.fillText('暂无资产数据', cx, cy);
    return;
  }
  let a0 = -Math.PI / 2;
  const colors = famColors();
  for (const c of cats) {
    if (c.mv_cny <= 0) continue;
    const a1 = a0 + (c.mv_cny / total) * Math.PI * 2;
    ctx.beginPath();
    ctx.arc(cx, cy, R, a0, a1);
    ctx.arc(cx, cy, r0, a1, a0, true);
    ctx.closePath();
    ctx.fillStyle = colors[c.bucket] || col('--txt-3');
    ctx.fill();
    a0 = a1;
  }
  ctx.fillStyle = col('--txt');
  ctx.font = '600 15px -apple-system, sans-serif';
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  ctx.fillText('¥' + fmt(total >= 10000 ? total / 10000 : total, total >= 10000 ? 1 : 0), cx, cy - 8);
  ctx.fillStyle = col('--txt-3');
  ctx.font = '10px -apple-system, sans-serif';
  ctx.fillText(total >= 10000 ? '万元' : '元', cx, cy + 10);
}

async function saveFamily(body) {
  const r = await fetch('/api/family', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const d = await r.json();
  if (!r.ok || d.ok === false) throw new Error(d.error || '保存失败');
  return d;
}

async function delFamily(id) {
  const ok = await QBConfirm({
    title: '删除科目',
    text: '删除后不可恢复，确定删除该科目吗？',
    okText: '删除', danger: true,
  });
  if (!ok) return;
  try {
    await saveFamily({ action: 'remove', id });
    loadFamily();
  } catch (e) { QBToast.err('删除失败', { desc: e.message }); }
}

async function saveFamilyTargets() {
  const targets = {};
  $$('.fam-tgt-in').forEach((i) => { targets[i.dataset.bucket] = +i.value || 0; });
  try {
    const d = await fetch('/api/family/target', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ targets }),
    }).then((r) => r.json());
    if (d.warn) QBToast.warn(d.warn);
    else QBToast.ok('目标已保存');
    loadFamily();
  } catch (e) { QBToast.err('保存失败', { desc: e.message }); }
}

/* 家庭：记一笔弹窗（新增 / 编辑复用） */
function famMemberOptions() {
  const ms = ((S.family && S.family.members) || []).filter((m) => m.id !== 0);
  return [{ value: 0, label: '共有' }].concat(ms.map((m) => ({ value: m.id, label: m.name })));
}

function openFamilyAdd(row) {
  S.famEditing = row ? row.id : null;
  QB.fmType.setValue(row ? row.atype : 'cash');
  QB.fmMember.setOptions(famMemberOptions()).setValue(row ? row.member_id : 0);
  $('#fmName').value = row ? row.name || '' : '';
  $('#fmAmount').value = row ? row.amount : '';
  QB.fmCurrency.setValue(row ? row.currency : 'CNY');
  $('#fmNote').value = row ? row.note || '' : '';
  $('#fmErr').textContent = '';
  $('.modal-head h3', $('#fmModal')).textContent = row ? '编辑科目' : '记一笔科目';
  $('#fmModal').classList.add('open');
  $('#fmMask').classList.add('show');
  setTimeout(() => $('#fmName').focus(), 60);
}

function closeFamilyAdd() {
  S.famEditing = null;
  $('#fmModal').classList.remove('open');
  $('#fmMask').classList.remove('show');
}

async function saveFamilyRow() {
  const body = {
    atype: $('#fmType').value,
    member_id: $('#fmMember').value,
    name: $('#fmName').value.trim(),
    amount: +$('#fmAmount').value || 0,
    currency: $('#fmCurrency').value,
    note: $('#fmNote').value.trim(),
  };
  if (S.famEditing) body.action = 'update', body.id = S.famEditing;
  try {
    await saveFamily(body);
    closeFamilyAdd();
    QBToast.ok(S.famEditing ? '已更新' : '已保存');
    loadFamily();
  } catch (e) {
    $('#fmErr').textContent = e.message;
  }
}

/* 家庭：记收支弹窗 */
function openFamilyFlow(row) {
  S.famFlowEdit = row ? row.id : null;
  QB.ffType.setValue(row ? row.ftype : 'expense');
  QB.ffMember.setOptions(famMemberOptions()).setValue(row ? row.member_id : 0);
  $('#ffCategory').value = row ? row.category || '' : '';
  $('#ffAmount').value = row ? row.amount : '';
  QB.ffCurrency.setValue(row ? row.currency : 'CNY');
  $('#ffDate').value = row ? String(row.flow_date).slice(0, 10) : new Date().toISOString().slice(0, 10);
  $('#ffNote').value = row ? row.note || '' : '';
  $('#ffErr').textContent = '';
  $('.modal-head h3', $('#ffModal')).textContent = row ? '编辑收支' : '记收支';
  $('#ffModal').classList.add('open');
  $('#ffMask').classList.add('show');
  setTimeout(() => $('#ffAmount').focus(), 60);
}

function closeFamilyFlow() {
  S.famFlowEdit = null;
  $('#ffModal').classList.remove('open');
  $('#ffMask').classList.remove('show');
}

async function saveFamilyFlowRow() {
  const body = {
    ftype: $('#ffType').value,
    member_id: $('#ffMember').value,
    category: $('#ffCategory').value.trim(),
    amount: +$('#ffAmount').value || 0,
    currency: $('#ffCurrency').value,
    flow_date: $('#ffDate').value,
    note: $('#ffNote').value.trim(),
  };
  if (S.famFlowEdit) body.action = 'update', body.id = S.famFlowEdit;
  try {
    const r = await fetch('/api/family/flow', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    const d2 = await r.json();
    if (!r.ok || d2.ok === false) throw new Error(d2.error || '保存失败');
    closeFamilyFlow();
    QBToast.ok(S.famFlowEdit ? '已更新' : '已保存');
    loadFamily();
  } catch (e) {
    $('#ffErr').textContent = e.message;
  }
}

async function delFamilyFlow(id) {
  const ok = await QBConfirm({ title: '删除收支', text: '删除后不可恢复，确定删除该笔收支吗？', okText: '删除', danger: true });
  if (!ok) return;
  try {
    await fetch('/api/family/flow', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'remove', id }),
    });
    loadFamily();
  } catch (e) { QBToast.err('删除失败', { desc: e.message }); }
}

/* 家庭：成员管理弹窗 */
function openFamMembers() {
  renderFamMembers();
  $('#fmbModal').classList.add('open');
  $('#fmbMask').classList.add('show');
  $('#fmbName').value = '';
}

function closeFamMembers() {
  $('#fmbModal').classList.remove('open');
  $('#fmbMask').classList.remove('show');
}

function renderFamMembers() {
  const ms = ((S.family && S.family.members) || []).filter((m) => m.id !== 0);
  $('#fmbList').innerHTML = ms.length ? ms.map((m) => `
    <div class="fmb-row">
      <input type="text" value="${esc(m.name)}" data-mid="${m.id}" />
      <button class="btn" data-msave="${m.id}">改名</button>
      <button class="btn ghost danger" data-mdel="${m.id}" data-mname="${esc(m.name)}">删除</button>
    </div>`).join('') : '<div class="small" style="padding:6px 0">暂无成员，先添加</div>';
  $$('#fmbList [data-msave]').forEach((b) => (b.onclick = async () => {
    const name = $(`#fmbList input[data-mid="${b.dataset.msave}"]`).value.trim();
    if (!name) return QBToast.warn('成员名不能为空');
    try {
      await fetch('/api/family/member', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'update', id: b.dataset.msave, name }) });
      QBToast.ok('已改名');
      await loadFamily();
      renderFamMembers();
    } catch (e) { QBToast.err('改名失败', { desc: e.message }); }
  }));
  $$('#fmbList [data-mdel]').forEach((b) => (b.onclick = async () => {
    const ok = await QBConfirm({
      title: '删除成员',
      text: `<b>${esc(b.dataset.mname)}</b> 名下的科目与收支将归入「共有」，确定删除吗？`,
      okText: '删除', danger: true,
    });
    if (!ok) return;
    try {
      await fetch('/api/family/member', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'remove', id: b.dataset.mdel }) });
      QBToast.ok('已删除');
      await loadFamily();
      renderFamMembers();
    } catch (e) { QBToast.err('删除失败', { desc: e.message }); }
  }));
}

async function addFamMember() {
  const name = $('#fmbName').value.trim();
  if (!name) return QBToast.warn('成员名不能为空');
  try {
    await fetch('/api/family/member', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }) });
    $('#fmbName').value = '';
    QBToast.ok('已添加');
    await loadFamily();
    renderFamMembers();
  } catch (e) { QBToast.err('添加失败', { desc: e.message }); }
}

/* ------------------------------------------------------------- AI 持仓分析 */
async function loadAi(force) {
  const body = $('#aiBody'), hint = $('#aiHint'), stats = $('#aiStats');
  const btn = $('#aiRefreshBtn');
  if (!S.port || !S.port.ready || !(S.port.brokers || []).length) {
    body.innerHTML = '<div class="ai-error">暂无持仓数据，先录入持仓再分析。</div>';
    if (stats) stats.innerHTML = '';
    return;
  }
  btn.disabled = true;
  body.innerHTML = '<div class="ai-loading">DeepSeek 正在分析持仓，约需 10–30 秒…</div>';
  hint.textContent = '';
  try {
    const r = await fetch('/api/positions/ai' + (force ? '?refresh=1' : ''));
    const d = await r.json();
    if (d.ok) {
      body.innerHTML = mdToHtml(d.content);
      hint.textContent = `${d.model || 'deepseek'} · ${d.generated_at || ''}`
        + (d.cached ? ' · 缓存结果（30 分钟内），点「重新分析」强制刷新' : '');
      S.ai = true;
    } else {
      body.innerHTML = (d.configured === false)
        ? `<div class="ai-error">${esc(d.message || '尚未配置 DeepSeek API Key')}</div>`
        : `<div class="ai-error">${esc(d.error || '分析失败')}</div>`;
    }
  } catch (e) {
    body.innerHTML = `<div class="ai-error">请求失败：${esc(e.message)}</div>`;
  }
  btn.disabled = false;
}

/** 页面顶部的持仓概览（市值 / 净投入 / 收益 / 标的数），即交给模型的那份快照口径。 */
function renderAiStats() {
  const stats = $('#aiStats');
  if (!stats || !S.port || !S.port.ready) return;
  const g = S.port.grand_cny_detail;
  if (!g) { stats.innerHTML = ''; return; }
  const sgn = (v) => (v > 0 ? '+' : v < 0 ? '-' : '');
  let count = 0, accounts = 0;
  for (const b of S.port.brokers || []) { accounts++; count += (b.items || []).length; }
  const cards = [
    ['总市值 · 约合人民币', '¥' + fmt(g.mv, 0), '', '今日 ' + sgn(g.day_pl) + '¥' + fmt(Math.abs(g.day_pl), 2)],
    ['净投入', '¥' + fmt(g.cost, 2), '', '按流水累计，出入金不影响'],
    ['总收益 · 浮盈+已实现+股息', sgn(g.total) + '¥' + fmt(Math.abs(g.total), 2), cls(g.total),
      '浮盈 ' + sgn(g.pl) + '¥' + fmt(Math.abs(g.pl), 0) + ' · 股息 ¥' + fmt(g.div_total || 0, 0)],
    ['收益率', pctTxt(g.pl_pct), cls(g.pl_pct), '相对净投入'],
    ['持仓标的', count + ' 只', '', accounts + ' 个账户'],
  ];
  stats.innerHTML = cards.map(([label, val, c, sub]) =>
    `<div class="stat"><span>${esc(label)}</span><b class="${c}">${val}</b>`
    + (sub ? `<span class="small">${esc(sub)}</span>` : '') + '</div>').join('');
}

/** 轻量 Markdown → HTML（标题 / 加粗 / 行内码 / 列表 / 表格 / 段落），先转义防注入。 */
function mdToHtml(md) {
  const lines = String(md).split('\n');
  const inline = (s) => s
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
    .replace(/`([^`]+)`/g, '<code>$1</code>');
  const isTbl = (l) => /^\s*\|.*\|\s*$/.test(l);
  const isLi = (l) => /^\s*([-*]|\d+\.)\s+/.test(l);
  let html = '', i = 0;
  while (i < lines.length) {
    const l = lines[i];
    if (/^\s*$/.test(l)) { i++; continue; }
    const h = l.match(/^#{1,6}\s+(.*)/);
    if (h) { html += `<h4>${inline(h[1])}</h4>`; i++; continue; }
    if (isTbl(l)) {
      const rows = [];
      while (i < lines.length && isTbl(lines[i])) { rows.push(lines[i]); i++; }
      const parse = rows
        .filter((r) => !/^\s*\|[\s:|-]+\|\s*$/.test(r))
        .map((r) => r.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim()));
      if (parse.length) {
        html += '<div class="table-wrap"><table class="tbl">' + parse.map((cs, ri) => {
          const tag = ri === 0 ? 'th' : 'td';
          return '<tr>' + cs.map((c) => `<${tag}>${inline(c)}</${tag}>`).join('') + '</tr>';
        }).join('') + '</table></div>';
      }
      continue;
    }
    if (isLi(l)) {
      const ordered = /^\s*\d+\./.test(l);
      const items = [];
      while (i < lines.length && isLi(lines[i])) {
        items.push(lines[i].replace(/^\s*([-*]|\d+\.)\s+/, '')); i++;
      }
      html += `<${ordered ? 'ol' : 'ul'}>` + items.map((t) => `<li>${inline(t)}</li>`).join('') + `</${ordered ? 'ol' : 'ul'}>`;
      continue;
    }
    const buf = [];
    while (i < lines.length && !/^\s*$/.test(lines[i]) && !/^#{1,6}\s/.test(lines[i])
      && !isTbl(lines[i]) && !isLi(lines[i])) { buf.push(lines[i]); i++; }
    html += `<p>${inline(buf.join(' '))}</p>`;
  }
  return html;
}

/** 删除某账户下某标的的全部流水（含已实现与分红记录，需确认）。 */
async function delPosition(posKey, name) {
  const ok = await QBConfirm({
    title: '删除标的流水',
    text: `删除「<b>${esc(name || posKey)}</b>」的全部流水？<br/>该标的的已实现盈亏与分红记录会一并消失，且不可恢复。`,
    okText: '删除',
    danger: true,
  });
  if (!ok) return;
  try {
    await fetch('/api/positions', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'remove', pos_key: posKey }),
    });
    await loadPort();
  } catch (e) { console.warn('del position failed', e); }
}

/* ---------------- 交易记录 ---------------- */
const SIDE_LABEL = { BUY: '买入', SELL: '卖出', DIV: '分红', INIT: '期初', SPLIT: '送转股' };
const SIDE_CLS = { BUY: 'good', SELL: 'weak', DIV: 'neutral', INIT: 'neutral', SPLIT: 'neutral' };

async function loadTrades() {
  try {
    const r = await fetch('/api/trades?limit=500');
    S.trades = await r.json();
  } catch (e) { S.trades = { trades: [] }; }
  renderTrades();
}

function renderTrades() {
  const box = $('#trTable');
  const rows = (S.trades && S.trades.trades) || [];
  $('#trHint').textContent = `共 ${rows.length} 笔 · 按日期倒序`;
  if (!rows.length) {
    box.innerHTML = `<tbody><tr><td style="padding:24px;text-align:center;color:var(--txt-3);font-size:12px">
      还没有流水记录 —— 用上面的表单记第一笔</td></tr></tbody>`;
    return;
  }
  const body = rows.map((t) => `<tr>
    <td class="small">${esc(String(t.trade_date).slice(0, 10))}</td>
    <td><span class="chip ${SIDE_CLS[t.side] || 'neutral'}">${SIDE_LABEL[t.side] || esc(t.side)}</span></td>
    <td class="small">${esc(t.broker)}</td>
    <td style="text-align:left"><div class="cell-name"><b>${esc(t.name)}</b><span>${esc(String(t.code).toUpperCase())}</span></div></td>
    <td>${t.qty ? fmt(t.qty, t.qty % 1 ? 4 : 0) : '—'}</td>
    <td>${t.price ? curSym(t.currency) + px(t.price) : '—'}</td>
    <td>${curSym(t.currency)}${fmt(t.amount, 2)}</td>
    <td class="small">${t.fee ? fmt(t.fee, 2) : '—'}</td>
    <td class="small" style="text-align:left">${esc(t.note || '')}</td>
    <td><span class="row-del" data-trid="${t.id}" title="删除这笔流水">✕</span></td>
  </tr>`).join('');
  box.innerHTML = `<thead><tr><th>日期</th><th>类型</th><th>券商</th><th style="text-align:left">标的</th>
    <th>数量</th><th>单价</th><th>金额</th><th>费用</th><th style="text-align:left">备注</th><th></th></tr></thead>
    <tbody>${body}</tbody>`;
  $$('#trTable [data-trid]').forEach((el) => (el.onclick = async (e) => {
    e.stopPropagation();
    const ok = await QBConfirm({
      title: '删除流水',
      text: '删除这笔流水？持仓与盈亏会重新计算。',
      okText: '删除',
      danger: true,
    });
    if (!ok) return;
    await fetch('/api/trades', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'remove', id: +el.dataset.trid }),
    });
    loadTrades(); loadPort();
  }));
}

async function saveTrade() {
  const err = $('#trErr');
  err.textContent = '';
  const side = $('#trSide').value;
  const body = {
    side,
    broker: $('#trBroker').value.trim(),
    code: $('#trCode').value.trim(),
    trade_date: $('#trDate').value || undefined,
    qty: +$('#trQty').value || 0,
    price: +$('#trPrice').value || 0,
    fee: +$('#trFee').value || 0,
    amount: +$('#trAmount').value || 0,
  };
  if (!body.code) { err.textContent = '请填写代码'; return; }
  if (side === 'SPLIT') {
    if (!body.qty) { err.textContent = '送转股请填股数：送股 / 拆股为正，并股为负'; return; }
  } else if (side !== 'DIV' && body.qty <= 0) { err.textContent = '买入 / 卖出必须填数量'; return; }
  if (side === 'DIV' && body.amount <= 0) { err.textContent = '分红请填税后到账金额'; return; }
  try {
    const r = await fetch('/api/trades', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
    const d = await r.json();
    if (!d.ok) { err.textContent = d.error || '保存失败'; return; }
    ['#trCode', '#trQty', '#trPrice', '#trFee', '#trAmount'].forEach((s) => ($(s).value = ''));
    loadTrades(); loadPort();
    QBToast.ok('已记录', { desc: SIDE_LABEL[side] + ' · ' + (d.name || body.code) });
  } catch (e) { err.textContent = '网络错误：' + e.message; }
}

/* ---------------- 收益分析 ---------------- */
async function loadPerf(force) {
  const days = S.perfDays || 180;
  const now = Date.now();
  // 20 秒内重复切进来直接复用：后端虽然只要十几毫秒，但来回一趟仍会让页面空转一下
  const fresh = force || !S.perf || S.perfDaysLoaded !== days || now - (S.perfTs || 0) > 20000;
  if (fresh) {
    try {
      const r = await fetch('/api/performance?days=' + days);
      S.perf = await r.json();
      S.perfTs = now;
      S.perfDaysLoaded = days;
    } catch (e) {
      S.perf = null;
    }
  }
  renderPerf();
}

function renderPerf() {
  const box = $('#perfStats');
  const d = S.perf;
  if (!d) { box.innerHTML = '<div class="stat"><span>加载失败</span><b>—</b></div>'; return; }
  const s = d.summary || {};
  const dd = d.drawdown || {};
  const sgn = (v) => (v > 0 ? '+' : v < 0 ? '-' : '');
  const total = s.total_pl || 0;
  const base = Math.abs(total) || 1;
  const w = (v) => Math.max(0, Math.min(100, Math.abs(v) / base * 100));
  const mw = s.mw || {};
  const mwAnnual = mw.annual != null ? `${mw.annual > 0 ? '+' : ''}${fmt(mw.annual, 2)}%` : '—';
  const mwTip = mw.note || (mw.capital_days != null
    ? `平均占用 ${fmt(mw.capital_days, 0)} 天 · 加权收益率 ${mw.rate > 0 ? '+' : ''}${fmt(mw.rate, 2)}%`
    : '');
  box.innerHTML = `
    <div class="stat"><span>总收益（浮盈 + 已实现 + 股息）</span>
      <b class="${cls(total)}">${sgn(total)}¥${fmt(Math.abs(total), 0)}</b>
      <div class="attr-bar">
        <i class="up" style="width:${w(s.unrealized)}%" title="浮盈"></i>
        <i class="realized" style="width:${w(s.realized)}%" title="已实现"></i>
        <i class="div" style="width:${w(s.div)}%" title="股息"></i>
      </div>
      <span class="small">浮盈 ${fmt(s.unrealized / base * 100, 0)}% · 已实现 ${fmt(s.realized / base * 100, 0)}% · 股息 ${fmt(s.div / base * 100, 0)}%</span></div>
    <div class="stat"><span>简单收益率 · 对净投入</span>
      <b class="${cls(s.simple_ret)}">${s.simple_ret > 0 ? '+' : ''}${fmt(s.simple_ret, 2)}%</b>
      <span class="small">净投入 ¥${fmt(s.invested, 0)}</span></div>
    <div class="stat"><span>资金加权年化 · 按占用天数</span>
      <b class="${cls(mw.annual || 0)}">${mwAnnual}</b>
      <span class="small">${esc(mwTip)}</span></div>
    <div class="stat"><span>最大回撤</span>
      <b class="dn">${dd.max_dd ? fmt(dd.max_dd, 2) + '%' : '—'}</b>
      <span class="small">${dd.peak_date ? esc(dd.peak_date) + ' → ' + esc(dd.trough_date) : '区间内无回撤'}</span></div>
    <div class="stat"><span>浮盈 / 已实现 / 股息</span>
      <b class="${cls(s.unrealized)}">${sgn(s.unrealized)}¥${fmt(Math.abs(s.unrealized), 0)}</b>
      <span class="small">已实现 <b class="${cls(s.realized)}">${sgn(s.realized)}¥${fmt(Math.abs(s.realized), 0)}</b>
        · 股息 <b class="${cls(s.div)}">${sgn(s.div)}¥${fmt(Math.abs(s.div), 0)}</b></span></div>
    <div class="stat"><span>总市值 / 持仓数</span>
      <b>¥${fmt(s.mv, 0)}</b>
      <span class="small">${s.holdings || 0} 只持仓${s.closed ? ` · ${s.closed} 只已清仓` : ''} · 汇率 USD ${(s.fx && s.fx.USD) || '—'} / HKD ${(s.fx && s.fx.HKD) || '—'}${s.fx && s.fx.as_of ? `（${s.fx.as_of.slice(5)}）` : ''}</span></div>`;

  // 区间收益
  const iv = d.intervals || [];
  $('#perfIntervals').innerHTML = `<thead><tr><th style="text-align:left">区间</th><th>收益</th><th>收益率</th></tr></thead>
    <tbody>${iv.map((i) => `<tr>
      <td style="text-align:left">${esc(i.label)}${i.estimated && i.label !== '累计' ? ' <span class="chip neutral" title="含 K 线回溯估算">估</span>' : ''}</td>
      <td class="${cls(i.pl)}"><b>${i.pl > 0 ? '+' : ''}${fmt(i.pl, 0)}</b></td>
      <td class="${cls(i.ret)}">${i.ret > 0 ? '+' : ''}${fmt(i.ret, 2)}%</td>
    </tr>`).join('')}</tbody>`;

  // 标的贡献
  const cs = d.contributors || [];
  const maxAbs = Math.max(1, ...cs.map((c) => Math.abs(c.total_cny)));
  $('#perfContrib').innerHTML = `<thead><tr><th style="text-align:left">标的</th><th>浮盈</th><th>已实现</th><th>股息</th><th>合计</th><th style="width:64px"></th></tr></thead>
    <tbody>${cs.map((c) => `<tr class="${c.closed ? 'closed-row' : ''}">
      <td style="text-align:left"><div class="cell-name"><b>${esc(c.name)}${c.closed ? ' <span class="chip neutral">已清仓</span>' : ''}</b>
        <span>${esc(String(c.code).toUpperCase())} · ${esc(c.broker)}</span></div></td>
      <td class="${cls(c.unrealized_cny)}">${c.unrealized_cny ? (c.unrealized_cny > 0 ? '+' : '') + fmt(c.unrealized_cny, 0) : '—'}</td>
      <td class="${cls(c.realized_cny)}">${c.realized_cny ? (c.realized_cny > 0 ? '+' : '') + fmt(c.realized_cny, 0) : '—'}</td>
      <td class="${cls(c.div_cny)}">${c.div_cny ? '+' + fmt(c.div_cny, 0) : '—'}</td>
      <td class="${cls(c.total_cny)}"><b>${c.total_cny > 0 ? '+' : ''}${fmt(c.total_cny, 0)}</b></td>
      <td><div class="mini-bar ${c.total_cny >= 0 ? 'up' : 'dn'}"><i style="width:${Math.abs(c.total_cny) / maxAbs * 100}%"></i></div></td>
    </tr>`).join('')}</tbody>`;

  const h = d.history || [];
  $('#perfCurveHint').textContent = h.length
    ? `${h[0].date} 起 · ${h.length} 个交易日 · 虚线为 K 线回溯估算`
    : '暂无快照';
  drawPerfCurve($('#perfCurve'), h);

  const bm = d.benchmark || {};
  const bh = $('#benchHint');
  if (bh) {
    bh.textContent = bm.ready
      ? `${bm.name}（${String(bm.code).toUpperCase()}） · 组合 ${bm.port_ret > 0 ? '+' : ''}${fmt(bm.port_ret, 2)}%`
        + ` vs 基准 ${bm.bm_ret > 0 ? '+' : ''}${fmt(bm.bm_ret, 2)}%`
        + ` · 超额 ${bm.excess > 0 ? '+' : ''}${fmt(bm.excess, 2)}%`
      : (bm.note || '基准数据加载中…');
  }
  drawBenchCurve($('#benchCurve'), bm.ready ? bm.rows : null);
}

/** 组合 vs 基准：两条指数化曲线（同以 100 起步）；组合实线，基准灰色虚线。 */
function drawBenchCurve(canvas, rows) {
  if (!canvas) return;
  const { ctx, w, h } = hdpi(canvas);
  ctx.clearRect(0, 0, w, h);
  if (!rows || rows.length < 2) {
    ctx.fillStyle = 'rgba(128,128,128,.6)';
    ctx.font = '12px system-ui';
    ctx.fillText('基准对比需要至少两个交易日的快照，明天起会自动累积', 12, h / 2);
    return;
  }
  const all = rows.flatMap((r) => [r.port, r.bm]);
  let min = Math.min(...all), max = Math.max(...all);
  const pad = (max - min) * 0.12 || 1;
  min -= pad; max += pad;
  const padL = 48, padR = 12, padT = 16, padB = 22;
  const iw = w - padL - padR, ih = h - padT - padB;
  const x = (i) => padL + (i / (rows.length - 1)) * iw;
  const y = (v) => padT + (1 - (v - min) / (max - min)) * ih;

  ctx.font = '11px system-ui';
  ctx.lineWidth = 1;
  for (let k = 0; k <= 4; k++) {
    const v = min + (max - min) * (k / 4);
    const yy = Math.round(y(v)) + 0.5;
    ctx.strokeStyle = 'rgba(128,128,128,.18)';
    ctx.beginPath(); ctx.moveTo(padL, yy); ctx.lineTo(w - padR, yy); ctx.stroke();
    ctx.fillStyle = 'rgba(128,128,128,.9)';
    ctx.fillText(v.toFixed(0), 6, yy + 3);
  }
  // 100 基准线
  const y100 = Math.round(y(100)) + 0.5;
  ctx.strokeStyle = 'rgba(128,128,128,.45)';
  ctx.setLineDash([3, 3]);
  ctx.beginPath(); ctx.moveTo(padL, y100); ctx.lineTo(w - padR, y100); ctx.stroke();
  ctx.setLineDash([]);

  const accent = col('--accent') || '#3355e0';
  // 基准（灰）
  ctx.beginPath();
  rows.forEach((r, i) => (i ? ctx.lineTo(x(i), y(r.bm)) : ctx.moveTo(x(i), y(r.bm))));
  ctx.strokeStyle = 'rgba(128,128,128,.75)';
  ctx.lineWidth = 1.4; ctx.lineJoin = 'round';
  ctx.stroke();
  // 组合（主色）
  ctx.beginPath();
  rows.forEach((r, i) => (i ? ctx.lineTo(x(i), y(r.port)) : ctx.moveTo(x(i), y(r.port))));
  ctx.strokeStyle = accent;
  ctx.lineWidth = 1.8;
  ctx.stroke();

  // 末点
  const lastIdx = rows.length - 1;
  [[rows[lastIdx].bm, 'rgba(128,128,128,.75)'], [rows[lastIdx].port, accent]].forEach(([v, c]) => {
    ctx.beginPath(); ctx.arc(x(lastIdx), y(v), 3, 0, Math.PI * 2); ctx.fillStyle = c; ctx.fill();
  });

  // 图例
  ctx.font = '11px system-ui';
  ctx.fillStyle = accent;
  ctx.fillText('组合', padL + 4, padT + 2);
  ctx.fillStyle = 'rgba(128,128,128,.9)';
  ctx.fillText('基准', padL + 36, padT + 2);

  // 横轴
  ctx.fillStyle = 'rgba(128,128,128,.9)';
  [[0, 'left'], [Math.floor(rows.length / 2), 'center'], [lastIdx, 'right']].forEach(([i, al]) => {
    ctx.textAlign = al;
    ctx.fillText(rows[i].date.slice(5), x(i), h - 6);
  });
  ctx.textAlign = 'left';
}

/** 净值曲线：画累计总收益，零轴为界，正红负绿；估算段用虚线。 */
function drawPerfCurve(canvas, hist) {
  const { ctx, w, h } = hdpi(canvas);
  ctx.clearRect(0, 0, w, h);
  if (!hist || hist.length < 2) {
    ctx.fillStyle = 'rgba(128,128,128,.6)';
    ctx.font = '12px system-ui';
    ctx.fillText('数据不足，明天起会逐日累积真实快照', 12, h / 2);
    return;
  }
  const vals = hist.map((r) => r.total_pl);
  let min = Math.min(...vals, 0), max = Math.max(...vals, 0);
  if (max === min) { max += 1; min -= 1; }
  const padL = 56, padR = 10, padT = 12, padB = 22;
  const iw = w - padL - padR, ih = h - padT - padB;
  const x = (i) => padL + (i / (hist.length - 1)) * iw;
  const y = (v) => padT + (1 - (v - min) / (max - min)) * ih;

  // 网格 + 纵轴刻度
  ctx.font = '11px system-ui';
  ctx.strokeStyle = 'rgba(128,128,128,.18)';
  ctx.fillStyle = 'rgba(128,128,128,.9)';
  ctx.lineWidth = 1;
  for (let k = 0; k <= 4; k++) {
    const v = min + (max - min) * (k / 4);
    const yy = Math.round(y(v)) + 0.5;
    ctx.beginPath(); ctx.moveTo(padL, yy); ctx.lineTo(w - padR, yy); ctx.stroke();
    ctx.fillText((v / 10000).toFixed(1) + '万', 6, yy + 3);
  }
  // 零轴
  if (min < 0 && max > 0) {
    ctx.strokeStyle = 'rgba(128,128,128,.5)';
    const zy = Math.round(y(0)) + 0.5;
    ctx.beginPath(); ctx.moveTo(padL, zy); ctx.lineTo(w - padR, zy); ctx.stroke();
  }

  const last = vals[vals.length - 1], first = vals[0];
  const color = last >= first ? '#e24b4a' : '#1d9e75';
  const y0 = y(0);

  // 面积
  const g = ctx.createLinearGradient(0, padT, 0, h - padB);
  g.addColorStop(0, color + '30');
  g.addColorStop(1, color + '00');
  ctx.beginPath();
  ctx.moveTo(x(0), y(vals[0]));
  hist.forEach((r, i) => ctx.lineTo(x(i), y(r.total_pl)));
  ctx.lineTo(x(hist.length - 1), y0);
  ctx.lineTo(x(0), y0);
  ctx.closePath();
  ctx.fillStyle = g; ctx.fill();

  // 实线段 / 估算段分开画
  ctx.lineWidth = 1.6; ctx.lineJoin = 'round';
  const segments = [];
  hist.forEach((r, i) => {
    const key = r.estimated ? 'e' : 'r';
    if (!segments.length || segments[segments.length - 1].key !== key) segments.push({ key, pts: [] });
    segments[segments.length - 1].pts.push(i);
  });
  segments.forEach((seg) => {
    if (seg.pts.length < 2 && seg.key === 'r') {
      ctx.setLineDash([]);
      return;
    }
    ctx.beginPath();
    seg.pts.forEach((i, k) => {
      const px = x(i), py = y(hist[i].total_pl);
      if (k === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
    });
    ctx.strokeStyle = seg.key === 'e' ? color + '99' : color;
    ctx.setLineDash(seg.key === 'e' ? [4, 3] : []);
    ctx.stroke();
  });
  ctx.setLineDash([]);

  // 横轴：首 / 中 / 末
  ctx.fillStyle = 'rgba(128,128,128,.9)';
  [[0, 'left'], [Math.floor(hist.length / 2), 'center'], [hist.length - 1, 'right']].forEach(([i, al]) => {
    ctx.textAlign = al;
    ctx.fillText(hist[i].date.slice(5), x(i), h - 6);
  });
  ctx.textAlign = 'left';

  // 末点标记
  ctx.beginPath();
  ctx.arc(x(hist.length - 1), y(last), 3, 0, Math.PI * 2);
  ctx.fillStyle = color; ctx.fill();
}

/* ---------------- 手动添加持仓（买入 / 卖出） ---------------- */
function paSide(v) {
  S.paSide = v;
  $('#paSideBuy').classList.toggle('active', v === 'buy');
  $('#paSideSell').classList.toggle('active', v === 'sell');
}
function openPortAdd() {
  $('#paModal').classList.add('open');
  $('#paMask').classList.add('show');
  $('#paErr').textContent = '';
  paSide('buy');
  setTimeout(() => $('#paBroker').focus(), 60);
}
function closePortAdd() {
  $('#paModal').classList.remove('open');
  $('#paMask').classList.remove('show');
}

async function savePosition() {
  const sell = S.paSide === 'sell';
  const body = {
    action: 'add',
    side: sell ? 'SELL' : 'BUY',
    broker: $('#paBroker').value.trim(),
    code: $('#paCode').value.trim(),
    trade_date: ($('#paDate') && $('#paDate').value) || undefined,
    qty: +$('#paQty').value,
    cost: +$('#paCost').value,
  };
  if (!body.code || !body.qty) { $('#paErr').textContent = '请填写代码和数量'; return; }
  if (sell && body.cost <= 0) { $('#paErr').textContent = '卖出需填写成交价'; return; }
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
    if (typeof loadTrades === 'function' && document.getElementById('trTable')) loadTrades();
    QBToast.ok(sell ? '已记录卖出' : '已记录买入',
      { desc: `${esc(String(d.name || body.code))} ${body.qty} @ ${body.cost}` });
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
  const fails = [];
  for (const it of valid) {
    try {
      const r = await fetch('/api/positions', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'add', broker, code: it.code, name: it.name || it.quote_name || '', qty: it.qty, cost: it.cost || 0 }),
      });
      const d = await r.json();
      if (d.ok) ok++;
      else fails.push(`${it.name || it.code}：${d.error || '导入失败'}`);
    } catch (e) { fails.push(`${it.code}：网络错误`); }
    btn.textContent = `导入中 ${ok + fails.length}/${valid.length}`;
  }
  btn.disabled = false;
  btn.textContent = '导入所选';
  if (fails.length) {   // 有失败行：留在弹窗里显示原因（多为该账户已有同标的流水）
    $('#poStatus').innerHTML = `<span class="dn">成功 ${ok} 条，失败 ${fails.length} 条：${esc(fails.slice(0, 3).join('；'))}</span>`;
    await loadPort();
    return;
  }
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

/* ---------------- 消息中心 ---------------- */
const NEWS_KIND = { policy: '政策', company: '企业', us: '美国', ann: '公告', market: '市场', global: '国际' };

async function loadNews(force) {
  const btn = $('#newsRefresh');
  if (btn) { btn.disabled = true; btn.textContent = '拉取中…'; }
  try {
    const r = await fetch('/api/news' + (force ? '?force=1' : ''));
    S.news = await r.json();
  } catch (e) {
    S.news = null;
  }
  if (btn) { btn.disabled = false; btn.textContent = '刷新'; }
  renderNews();
}

function dayLabel(day) {
  const today = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  const t0 = `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`;
  const y = new Date(today.getTime() - 86400000);
  const y0 = `${y.getFullYear()}-${pad(y.getMonth() + 1)}-${pad(y.getDate())}`;
  if (day === t0) return '今天';
  if (day === y0) return '昨天';
  return day.slice(5);
}

function renderNews() {
  const box = $('#newsList');
  const d = S.news;
  if (!d) {
    box.innerHTML = '<div class="hint" style="padding:16px 2px">拉取失败，点右上角刷新重试。</div>';
    return;
  }
  const counts = d.counts || {};
  const all = d.items || [];
  // 「企业」= 关注标的的新闻 + 公告；「全部」不含美国 / 国际快讯（都有独立 Tab，混进来会淹掉政策和公司新闻）
  const matchKind = (it, k) => {
    if (k === 'all') return it.kind !== 'global' && it.kind !== 'us';
    if (k === 'company') return it.kind === 'company' || it.kind === 'ann';
    return it.kind === k;
  };
  const items = all.filter((it) => matchKind(it, S.newsKind));
  // tab 数字
  $$('#newsTabs .nws-tab').forEach((b) => {
    const k = b.dataset.kind;
    const n = all.filter((i) => matchKind(i, k)).length;
    b.textContent = (k === 'all' ? '全部' : NEWS_KIND[k]) + (n ? ` ${n}` : '');
    b.classList.toggle('active', k === S.newsKind);
  });
  $('#newsHint').textContent = `${d.ts ? d.ts.slice(11) : ''} 更新 · ${items.length} 条`;

  if (!items.length) {
    const msg = S.newsKind === 'policy'
      ? '当前时段没有识别到政策类消息。政策多集中在盘中与盘后（15:00–20:00）发布，稍后再刷。'
      : '这个分类暂时没有消息，切到「全部」看看。';
    box.innerHTML = `<div class="hint" style="padding:16px 2px">${msg}</div>`;
    return;
  }
  const shown = items.slice(0, 240);   // 太多行会拖慢渲染
  let html = '';
  let lastDay = '';
  for (const it of shown) {
    const day = String(it.time || '').slice(0, 10);
    if (day !== lastDay) {
      html += `<div class="nws-day">${esc(dayLabel(day))}</div>`;
      lastDay = day;
    }
    const kind = it.kind || 'global';
    const stocks = (it.stocks || []).map((s) => `<span class="nws-stock">${esc(s)}</span>`).join('');
    const title = esc(it.title || '');
    const link = it.url
      ? `<a href="${esc(it.url)}" target="_blank" rel="noopener">${title}</a>`
      : title;
    html += `<div class="nws-item${it.hot ? ' hot' : ''}">
      <div class="nws-time">${esc(String(it.time || '').slice(11, 16))}</div>
      <div class="nws-body">
        <div class="nws-title">${it.hot ? '<span class="nws-dot"></span>' : ''}${link}</div>
        ${it.digest ? `<div class="nws-digest">${esc(it.digest)}</div>` : ''}
        <div class="nws-meta">
          <span class="nws-tag k-${kind}">${esc(NEWS_KIND[kind] || kind)}</span>
          ${stocks}
          <span class="nws-src">${esc(it.source || '')}</span>
        </div>
      </div>
    </div>`;
  }
  box.innerHTML = html;
}

/* ---------------------------------------------------------------- 数据加载 */
async function load(force) {
  // 正在刷新时，强制刷新请求排队到本轮结束后再跑，避免"添加后看不到新标的"
  if (S.loading) {
    if (force) S.pendingForce = true;
    return;
  }
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
    if (S.pendingForce) { S.pendingForce = false; load(true); }
  }
}

/** 报告期转中文：2026Q2 → 2026 年二季度；short=true 时 → 26年二季度 */
function qCn(q, short) {
  const m = /^(\d{4})Q([1-4])$/i.exec(String(q || '').trim());
  if (!m) return String(q || '');
  const y = short ? m[1].slice(2) + '年' : m[1] + ' 年';
  return y + ['一', '二', '三', '四'][Number(m[2]) - 1] + '季度';
}

/** 机构类型的中文说明（悬停可见） */
const GURU_TYPES = {
  '13f': ['13F 机构', '13F 机构：按美国证监会要求，管理 1 亿美元以上美股资产的机构须每季度申报持仓'],
  sovereign: ['主权基金', '主权基金：国家级投资机构，同样向 SEC 申报 13F，可与对冲基金一并比较'],
};

/** 期权持仓的代码带 ~PUT/~CALL 后缀，展示与跳转都用正股代码 */
function baseCode(code) {
  return String(code || '').split('~')[0];
}

/** 13F 备注里的期权方向翻译 */
function posNote(n) {
  const s = String(n || '').trim().toUpperCase();
  if (s === 'PUT') return '看跌期权 Put';
  if (s === 'CALL') return '看涨期权 Call';
  return n || '';
}

/** 没有收录中文名的标的，把 SEC 的全大写原名转成常规写法，读起来省力些（缩写保持原样） */
const EN_KEEP = new Set(['ETF', 'SPDR', 'ADR', 'REIT', 'LLC', 'LP', 'PLC', 'S&P',
  'USA', 'USB', 'KKR', 'BP', 'TR', 'NVDA', 'IBM', 'AMD', 'AG', 'NV', 'PLC']);
function enPretty(s) {
  const t = String(s || '').trim();
  if (t.length < 5 || t !== t.toUpperCase() || /[\u4e00-\u9fff]/.test(t)) return t;
  return t.split(/\s+/).map((w) => {
    if (EN_KEEP.has(w) || /\d/.test(w) || w.includes('&')) return w;
    if (w.length <= 2) return w;
    return w[0] + w.slice(1).toLowerCase();
  }).join(' ');
}

/** 机构行的副行：掌门人 · 英文原名（中文名已作主行显示） */
function guruSub(x) {
  return [x.note, x.name_cn ? x.name : ''].filter(Boolean).join(' · ');
}

function renderGuru() {
  if (!S.guru) return;
  const g = S.guru;
  const inv = g.investors || [];
  const latestQ = (g.quarters || [])[0] || '';
  $('#guruStats').innerHTML = [
    ['跟踪机构', inv.length + ' 家'],
    ['最新报告期', latestQ ? qCn(latestQ) : '—'],
    ['已有期数', (g.quarters || []).length + ' 期'],
    ['持仓明细', fmt(g.rows || 0, 0) + ' 行'],
    ['共识标的', fmt((g.consensus || []).length, 0) + ' 只'],
  ].map(([k, v]) => `<div class="stat"><span>${k}</span><b>${v}</b></div>`).join('');
  $('#guruHint').textContent = inv.length ? '点机构行可只看它的持仓 · 「拉取」从 SEC 同步最新 13F' : '还没有机构，点右上角添加';

  $('#guruTable').innerHTML = `
    <thead><tr>
      <th title="主行为机构中文名；副行为掌门人与 SEC 英文原名，便于与原版申报文件核对">机构 / 掌门人</th>
      <th title="13F 机构＝按季申报持仓的对冲基金与资管；主权基金＝国家级投资机构">类型</th>
      <th title="SEC 机构编号（CIK），13F 自动拉取时用来定位该机构在 EDGAR 上的申报档案">SEC 编号</th>
      <th title="该机构已同步的最新一份季报所属季度（13F 允许滞后约一个季度）">最新报告期</th>
      <th title="该报告期申报的持仓标的总数">持仓数</th>
      <th title="该机构本报告期已披露持仓的合计市值，折亿美元">市值(亿美元)</th>
      <th title="按市值排序的第一大持仓，已译为中文">第一大持仓</th><th></th>
    </tr></thead>
    <tbody>${inv.map((x) => {
      const t = GURU_TYPES[x.gtype] || GURU_TYPES['13f'];
      const main = x.name_cn || x.name;
      const sub = guruSub(x);
      return `
      <tr data-inv="${x.id}" style="cursor:pointer">
        <td title="${esc([x.name, x.note].filter(Boolean).join(' · '))}">
          <b>${esc(main)}</b>${sub ? `<span class="guru-sub">${esc(sub)}</span>` : ''}
        </td>
        <td title="${esc(t[1])}">${t[0]}</td>
        <td class="hint">${x.cik ? esc(x.cik) : '<span title="没有 SEC 编号的机构不会出现在 EDGAR，只能手动录入持仓">—</span>'}</td>
        <td title="${x.latest_quarter ? esc(qCn(x.latest_quarter)) : '尚未同步过 13F'}">${x.latest_quarter ? esc(qCn(x.latest_quarter)) : '<span class="hint">未拉取</span>'}</td>
        <td>${fmt(x.holdings, 0)}</td>
        <td>$${x.total_yi ? fmt(x.total_yi, 1) : '—'}</td>
        <td class="hint">${esc(x.top || '—')}</td>
        <td style="white-space:nowrap">
          ${x.cik ? `<button class="btn ghost" data-gfetch="${x.id}" title="从 SEC EDGAR 同步最新 13F-HR" style="padding:2px 8px;font-size:12px">拉取</button>` : ''}
          <button class="btn ghost" data-gdel="${x.id}" title="删除机构及其持仓" style="padding:2px 7px;font-size:12px;color:var(--dn)">✕</button>
        </td>
      </tr>`; }).join('') || '<tr><td colspan="8" class="hint" style="padding:16px">暂无机构，点右上角「添加机构」</td></tr>'}</tbody>`;
  paintGuruSegs();
  renderGuruCons();
}

function paintGuruSegs() {
  if (!S.guru) return;
  // 机构 / 报告期都用下拉（QBSelect）承载，避免十几个选项在面板头铺成两行
  if (QB.guruInv) {
    QB.guruInv.setOptions([
      { value: '0', label: '全部机构' },
      ...(S.guru.investors || []).map((g) => ({ value: String(g.id), label: g.name_cn || g.name })),
    ]);
    QB.guruInv.setValue(String(S.guruInv));
  }
  if (QB.guruQ) {
    QB.guruQ.setOptions([
      { value: '', label: '各机构最新' },
      ...(S.guru.quarters || []).map((q) => ({ value: q, label: qCn(q, true) })),
    ]);
    QB.guruQ.setValue(S.guruQuarter);
  }
}

function renderGuruHoldings() {
  const rows = S.guruRows || [];
  $('#guruHoldTable').innerHTML = `
    <thead><tr>
      <th>机构</th>
      <th title="该行数据所属的报告期。选「各机构最新」时每家机构取自己最新的一期，所以同一张表里报告期可能不同">报告期</th>
      <th title="美股交易代码。SEC 13F 本身只申报 CUSIP 编号，本看板会按 CUSIP 反查代码；仍查不到的（未上市公司等）显示「—」">代码</th>
      <th title="已收录中文名的显示中文名，悬停可看 13F 申报的英文原名">标的</th>
      <th title="申报持股数量（同一发行人的多行已合并）">持股数(股)</th>
      <th title="按 13F 申报市值，折亿美元">市值(亿美元)</th>
      <th title="占该机构本报告期已披露持仓市值的比重">占组合</th>
      <th title="13F 中标的为期权时标注方向">备注</th>
    </tr></thead>
    <tbody>${rows.map((h) => {
      const code = h.short_code || baseCode(h.code);
      const isTicker = /^us/i.test(h.code || '');
      return `
      <tr>
        <td class="hint" title="${esc(h.guru)}" style="max-width:126px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${esc(h.guru_label || h.guru)}</td>
        <td class="hint" style="white-space:nowrap" title="${esc(qCn(h.quarter))}">${esc(qCn(h.quarter, true))}</td>
        <td title="${isTicker ? esc('美股代码 ' + code)
          : esc('SEC 申报只给了 CUSIP 编号 ' + code + '，没有对应的美股代码（多为未上市公司、基金或非美股标的），看「标的」列即可')}">${isTicker
          ? `<b class="link" data-gcode="${esc(baseCode(h.code))}" style="cursor:pointer">${esc(code)}</b>`
          : '<span class="hint">—</span>'}</td>
        <td title="${esc(h.name || '')}">${esc(h.name_cn || enPretty(h.name) || '—')}</td>
        <td>${h.shares ? fmt(h.shares, 0) : '—'}</td>
        <td>${h.value_yi ? '$' + fmt(h.value_yi, 2) : '—'}</td>
        <td>${h.pct ? fmt(h.pct, 2) + '%' : '—'}</td>
        <td class="hint">${esc(posNote(h.note))}</td>
      </tr>`; }).join('') || '<tr><td colspan="8" class="hint" style="padding:16px">没有持仓数据 —— 有 SEC 编号的机构点「拉取」，其他机构点右上角「录入持仓」</td></tr>'}</tbody>`;
}

function renderGuruCons() {
  if (!S.guru) return;
  const byId = {};
  const cnById = {};
  (S.guru.investors || []).forEach((g) => { byId[g.id] = g.name; cnById[g.id] = g.name_cn || g.name; });
  const cons = S.guru.consensus || [];
  $('#guruConsTable').innerHTML = `
    <thead><tr>
      <th>代码</th>
      <th title="已收录中文名的显示中文名，悬停可看英文原名">标的</th>
      <th title="同时持有该标的的跟踪机构数量">机构数</th>
      <th title="各机构本报告期该标的市值加总，折亿美元">合计市值(亿美元)</th>
      <th>持有机构</th>
    </tr></thead>
    <tbody>${cons.map((c) => {
      const code = baseCode(c.code).replace(/^us/i, '');
      const isTicker = /^us/i.test(c.code || '');
      return `
      <tr>
        <td>${isTicker
          ? `<b class="link" data-gcode="${esc(baseCode(c.code))}" style="cursor:pointer">${esc(code)}</b>`
          : `<span class="hint" title="${esc('SEC 申报只给了 CUSIP 编号 ' + code + '，无对应美股代码')}">—</span>`}</td>
        <td title="${esc(c.name || '')}">${esc(c.name_cn || enPretty(c.name) || '—')}</td>
        <td><b>${c.gurus}</b></td>
        <td>$${fmt(c.total_yi, 1)}</td>
        <td class="hint">${String(c.ids || '').split(',').map((i) => esc(cnById[i.trim()] || '?')).join(' / ')}</td>
      </tr>`; }).join('') || '<tr><td colspan="5" class="hint" style="padding:16px">暂无共识 —— 至少有 2 家机构持有同一标的时会出现在这里</td></tr>'}</tbody>`;
}

async function loadGuru() {
  try { const r = await fetch('/api/guru'); S.guru = await r.json(); }
  catch (e) { S.guru = { ready: false, investors: [], quarters: [], consensus: [] }; }
  renderGuru();
  loadGuruHoldings();
  loadGuruChanges();
  // 还有标的没收录中文名 → 后台补一次（腾讯批量取名），补到就重绘。每次会话只试一次。
  if (!S.guruCnTried && (S.guru.cn_missing || 0) > 0) {
    S.guruCnTried = true;
    fetch('/api/guru/names', { method: 'POST' })
      .then((x) => x.json())
      .then((d) => { if (d && d.filled > 0) loadGuru(); })
      .catch(() => {});
  }
}

async function loadGuruHoldings() {
  try {
    const r = await fetch(`/api/guru/holdings?investor=${S.guruInv}`
      + `&quarter=${encodeURIComponent(S.guruQuarter)}&page=${S.guruPage}&size=${S.guruSize}`);
    const d = await r.json();
    S.guruRows = d.rows || [];
    S.guruMeta = d;
    if (d.page && d.page !== S.guruPage) S.guruPage = d.page;   // 后端纠正过界页码
  } catch (e) { S.guruRows = []; S.guruMeta = null; }
  renderGuruHoldings();
  renderGuruPager();
}

/** 持仓明细分页条：范围 / 总行数 / 上下页 / 每页行数。 */
function renderGuruPager() {
  const host = $('#guruPager');
  if (!host) return;
  const m = S.guruMeta;
  if (!m || !m.total) { host.innerHTML = ''; return; }
  const pages = m.pages || 1;
  const atFirst = m.page <= 1, atLast = m.page >= pages;
  host.innerHTML = `
    <span class="pager-info">第 <b>${fmt(m.from, 0)}–${fmt(m.to, 0)}</b> 行，共 <b>${fmt(m.total, 0)}</b> 行
      · 合计 $${fmt(m.total_yi || 0, 1)} 亿 · 第 ${m.page}/${pages} 页</span>
    <span class="pager-ctl">
      <select id="guruSizeSel" title="每页行数">
        ${[50, 100, 200, 500].map((n) => `<option value="${n}"${n === m.size ? ' selected' : ''}>${n} 行/页</option>`).join('')}
      </select>
      <button class="btn ghost" id="guruPrev" ${atFirst ? 'disabled' : ''}>上一页</button>
      <button class="btn ghost" id="guruNext" ${atLast ? 'disabled' : ''}>下一页</button>
    </span>`;
  const prev = $('#guruPrev'), next = $('#guruNext'), sel = $('#guruSizeSel');
  if (prev) prev.onclick = () => { if (S.guruPage > 1) { S.guruPage--; loadGuruHoldings(); } };
  if (next) next.onclick = () => { if (S.guruPage < pages) { S.guruPage++; loadGuruHoldings(); } };
  if (sel) sel.onchange = (e) => { S.guruSize = +e.target.value; S.guruPage = 1; loadGuruHoldings(); };
}

/* ------- 大佬持仓：本期调仓（相邻两期对比） ------- */
const CHG_LABEL = { NEW: '新建', ADD: '加仓', TRIM: '减仓', EXIT: '清仓' };
const CHG_CLS = { NEW: 'good', ADD: 'good', TRIM: 'weak', EXIT: 'weak' };

async function loadGuruChanges() {
  if (!S.guruInv) { S.guruChg = null; renderGuruChanges(); return; }
  try {
    const r = await fetch(`/api/guru/changes?investor=${S.guruInv}&quarter=${encodeURIComponent(S.guruQuarter)}`);
    S.guruChg = await r.json();
  } catch (e) { S.guruChg = null; }
  renderGuruChanges();
}

/** 补拉上一期 13F（skip=1）：首次导入只有最新一期，环比需要相邻两期。 */
async function fetchGuruPrev(btn) {
  const id = S.guruInv;
  if (!id || !btn) return;
  const label = btn.textContent;
  btn.disabled = true;
  btn.textContent = '拉取中…';
  try {
    const r = await fetch('/api/guru/import', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id, skip: 1 }),
    });
    const d = await r.json();
    if (!d.ok) {
      btn.disabled = false;
      btn.textContent = label;
      const hint = $('#guruChgHint');
      if (hint) hint.textContent = d.error || '上一期拉取失败';
      return;
    }
    await loadGuru();
  } catch (e) {
    btn.disabled = false;
    btn.textContent = label;
  }
}

function renderGuruChanges() {
  const box = $('#guruChgTable');
  if (!box) return;
  const hint = $('#guruChgHint');
  if (!S.guruInv) {
    if (hint) hint.textContent = '选一家机构后，对比相邻两期的新建 / 加仓 / 减仓 / 清仓';
    box.innerHTML = '<tbody><tr><td class="hint" style="padding:16px">选择一家机构查看本期调仓 —— 环比只在同一家机构的相邻两期之间比较</td></tr></tbody>';
    return;
  }
  const d = S.guruChg;
  if (!d || !d.ready) {
    if (hint) hint.textContent = (d && d.note) || '调仓数据加载中…';
    box.innerHTML = `<tbody><tr><td class="hint" style="padding:16px">${esc((d && d.note) || '暂无数据')}</td></tr></tbody>`;
    return;
  }
  if (!d.comparable) {
    if (hint) hint.textContent = d.note || '只有一期数据，无法环比';
    box.innerHTML = `<tbody><tr><td class="hint" style="padding:16px">
      ${esc(d.note || '该机构只有一期数据，还没有可比的上期')}
      <button class="btn ghost" id="guruPrevBtn" style="margin-left:10px;padding:2px 10px;font-size:12px"
        title="从 SEC 再拉一份更早的 13F-HR，补齐上一期后即可对比">拉取上一期</button>
    </td></tr></tbody>`;
    const b = $('#guruPrevBtn');
    if (b) b.onclick = () => fetchGuruPrev(b);
    return;
  }
  const s = d.summary || {};
  if (hint) {
    hint.textContent = `${qCn(d.prev_quarter, true)} → ${qCn(d.quarter, true)} · 新建 ${s.new || 0} · 加仓 ${s.add || 0}`
      + ` · 减仓 ${s.trim || 0} · 清仓 ${s.exit || 0} · 未变 ${s.hold || 0}`;
  }
  const rows = d.rows || [];
  if (!rows.length) {
    box.innerHTML = '<tbody><tr><td class="hint" style="padding:16px">这两期持仓没有变化</td></tr></tbody>';
    return;
  }
  const body = rows.map((r) => {
    const code = r.short_code || baseCode(r.code);
    const isTicker = /^us/i.test(r.code || '');
    const dp = r.delta_pct === null || r.delta_pct === undefined ? '—'
      : (r.delta_pct > 0 ? '+' : '') + fmt(r.delta_pct, 1) + '%';
    return `<tr>
      <td><span class="chip ${CHG_CLS[r.action] || 'neutral'}">${CHG_LABEL[r.action] || esc(r.action)}</span></td>
      <td>${isTicker
        ? `<b class="link" data-gcode="${esc(baseCode(r.code))}" style="cursor:pointer">${esc(code)}</b>`
        : '<span class="hint">—</span>'}</td>
      <td title="${esc(r.name || '')}">${esc(r.label || enPretty(r.name) || '—')}</td>
      <td>${r.shares ? fmt(r.shares, 0) : '—'}</td>
      <td class="hint">${r.prev_shares ? fmt(r.prev_shares, 0) : '—'}</td>
      <td class="${r.delta_shares > 0 ? 'up' : r.delta_shares < 0 ? 'dn' : 'flat'}">${r.delta_shares
        ? (r.delta_shares > 0 ? '+' : '') + fmt(r.delta_shares, 0) + ' <span class="small">' + dp + '</span>'
        : '—'}</td>
      <td class="${r.delta_value_yi > 0 ? 'up' : r.delta_value_yi < 0 ? 'dn' : 'flat'}">${r.delta_value_yi
        ? (r.delta_value_yi > 0 ? '+' : '') + '$' + fmt(r.delta_value_yi, 2) + ' 亿' : '—'}</td>
    </tr>`;
  }).join('');
  const more = (d.total_changes || rows.length) > rows.length
    ? `<tr><td colspan="7" class="hint" style="padding:12px">共 ${fmt(d.total_changes, 0)} 条变化，按市值变化展示前 ${rows.length} 条</td></tr>` : '';
  box.innerHTML = `<thead><tr>
      <th style="width:64px">动作</th>
      <th title="美股交易代码；查不到代码的显示「—」">代码</th>
      <th style="text-align:left" title="优先中文名，悬停看 13F 英文原名">标的</th>
      <th title="本期申报持股数（同一发行人多行已合并）">本期股数</th>
      <th title="上期申报持股数">上期股数</th>
      <th title="股数变化与变化幅度（清仓时无上期基准，显示 —）">股数变化</th>
      <th title="两期申报市值之差，已剔除股价涨跌">市值变化</th>
    </tr></thead><tbody>${body}${more}</tbody>`;
}

/* ------- 大佬持仓：添加机构 / 录入持仓 弹窗 ------- */
function openGuruInv() {
  ['#giName', '#giNameCn', '#giCik', '#giNote'].forEach((s) => { const el = $(s); if (el) el.value = ''; });
  $('#giErr').textContent = '';
  if (QB.giType) QB.giType.setValue('13f');
  $('#giModal').classList.add('open');
  $('#giMask').classList.add('show');
  setTimeout(() => $('#giName').focus(), 60);
}
function closeGuruInv() {
  $('#giModal').classList.remove('open');
  $('#giMask').classList.remove('show');
}
async function saveGuruInv() {
  const body = {
    action: 'add_investor',
    name: $('#giName').value.trim(),
    name_cn: $('#giNameCn') ? $('#giNameCn').value.trim() : '',
    gtype: QB.giType ? QB.giType.get() : '13f',
    cik: $('#giCik').value.trim(),
    note: $('#giNote').value.trim(),
  };
  try {
    const r = await fetch('/api/guru', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    const d = await r.json();
    if (!r.ok || d.ok === false) { $('#giErr').textContent = d.error || '保存失败'; return; }
    QBToast.ok('机构已添加');
    closeGuruInv();
    loadGuru();
  } catch (e) { $('#giErr').textContent = e.message; }
}

function openGuruHold() {
  const inv = (S.guru && S.guru.investors) || [];
  if (!inv.length) { QBToast.warn('先添加机构'); return; }
  if (QB.ghGuru) QB.ghGuru.setOptions(inv.map((g) => ({ value: g.id, label: g.name })));
  $('#ghQuarter').value = S.guruQuarter || (S.guru.quarters && S.guru.quarters[0]) || '';
  $('#ghCode').value = ''; $('#ghName').value = ''; $('#ghShares').value = '';
  $('#ghValue').value = ''; $('#ghNote').value = ''; $('#ghErr').textContent = '';
  $('#ghModal').classList.add('open');
  $('#ghMask').classList.add('show');
  setTimeout(() => $('#ghCode').focus(), 60);
}
function closeGuruHold() {
  $('#ghModal').classList.remove('open');
  $('#ghMask').classList.remove('show');
}
async function saveGuruHold() {
  let code = $('#ghCode').value.trim().toUpperCase();
  if (code && !code.startsWith('US')) code = 'US' + code;
  const body = {
    action: 'add',
    guru_id: QB.ghGuru ? QB.ghGuru.get() : 0,
    quarter: $('#ghQuarter').value.trim().toUpperCase(),
    code,
    name: $('#ghName').value.trim(),
    shares: $('#ghShares').value || 0,
    value_usd: $('#ghValue').value || 0,
    note: $('#ghNote').value.trim(),
  };
  try {
    const r = await fetch('/api/guru', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    const d = await r.json();
    if (!r.ok || d.ok === false) { $('#ghErr').textContent = d.error || '保存失败'; return; }
    QBToast.ok('持仓已录入');
    closeGuruHold();
    if (S.guruQuarter === '') loadGuru(); else loadGuruHoldings();
  } catch (e) { $('#ghErr').textContent = e.message; }
}

async function fetchGuru13f(id, btn) {
  if (btn) { btn.disabled = true; btn.textContent = '拉取中…'; }
  try {
    const r = await fetch('/api/guru/import', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ id }) });
    const d = await r.json();
    if (!r.ok || d.ok === false) QBToast.err('拉取失败', { desc: d.error || '' });
    else QBToast.ok(`${d.guru} · ${d.quarter}：已同步 ${d.rows} 行持仓`);
    loadGuru();
  } catch (e) { QBToast.err('拉取失败', { desc: e.message }); }
  finally { if (btn) { btn.disabled = false; btn.textContent = '拉取'; } }
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
  if (S.view === 'family') loadFamily();   // 证券市值随行情刷新
  if (S.view === 'ai') renderAiStats();   // 行情刷新时同步概览，不重跑大模型
}

/* ---------------------------------------------------------------- 交互 */
function switchView(v) {
  S.view = v;
  $('#ticker').parentElement.classList.toggle('hide', v !== 'overview'); // 指数滚动条只在总览页显示
  if (location.hash.slice(1) !== v) history.replaceState(null, '', '#' + v);
  $$('.nav-item').forEach((n) => n.classList.toggle('active', n.dataset.view === v));
  $$('.view').forEach((s) => s.classList.remove('active'));
  if (['a', 'hk', 'us', 'etf', 'fund'].includes(v)) {
    S.group = v; S.kind = 'all'; S.sort = { key: 'pct', dir: -1 };
    $('#v-market').classList.add('active');
    if (S.data) renderMarket(); // 首屏数据未到时先不渲染，load() 完成后会补
  } else {
    $('#v-' + v).classList.add('active');
    if (v === 'port') loadPort();
    if (v === 'family') loadFamily();
    if (v === 'perf') loadPerf();
    if (v === 'trades') loadTrades();
    if (v === 'news') loadNews(false);
    if (v === 'guru') loadGuru();
    if (v === 'ai') (async () => {
      if (!S.port) await loadPort();      // 直接以 #ai 进入时先确保持仓快照就绪
      renderAiStats();
      if (!S.ai) loadAi(false);
    })();
  }
  $('main').scrollTo({ top: 0, behavior: 'smooth' });
}

function init() {
  $$('.nav-item').forEach((n) => (n.onclick = () => switchView(n.dataset.view)));
  $$('[data-view]').forEach((n) => {
    if (!n.classList.contains('nav-item') && n.tagName === 'A') n.onclick = () => switchView(n.dataset.view);
  });
  $('#refreshBtn').onclick = () => load(true);
  $('#newsRefresh').onclick = () => loadNews(true);
  $('#newsTabs').onclick = (e) => {
    const b = e.target.closest('.nws-tab');
    if (!b) return;
    S.newsKind = b.dataset.kind;
    renderNews();
  };
  if ($('#mgBatchDel')) $('#mgBatchDel').onclick = () => removeInstrumentsMany();
  $('#trSave').onclick = () => saveTrade();
  $('#perfSeg').onclick = (e) => {
    const b = e.target.closest('.seg-btn');
    if (!b) return;
    S.perfDays = +b.dataset.d;
    $$('#perfSeg .seg-btn').forEach((x) => x.classList.toggle('active', x === b));
    loadPerf();
  };
  if ($('#trDate')) $('#trDate').value = new Date().toISOString().slice(0, 10);
  const syncTrFields = () => {
    const v = $('#trSide').value;
    const isDiv = v === 'DIV', isSplit = v === 'SPLIT';
    $('#trQty').disabled = isDiv;
    $('#trPrice').disabled = isDiv || isSplit;
    $('#trAmount').disabled = !isDiv;
    $('#trQty').placeholder = isSplit ? '送股填正数 / 并股填负数' : '分红留空';
  };
  QB.trSide = QBSelect($('#trSideHost'), {
    id: 'trSide',
    value: 'BUY',
    options: [
      { value: 'BUY', label: '买入' },
      { value: 'SELL', label: '卖出' },
      { value: 'DIV', label: '分红' },
      { value: 'SPLIT', label: '送转股/拆股' },
      { value: 'INIT', label: '期初建仓' },
    ],
    onChange: syncTrFields,
  });
  syncTrFields();
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
    closeFamilyAdd();
    closeFamilyFlow();
    closeFamMembers();
    closeGuruInv();
    closeGuruHold();
  });

  // 家庭总览（自绘下拉组件实例）
  QB.fmType = QBSelect($('#fmTypeHost'), {
    id: 'fmType', value: 'cash',
    options: [
      { value: 'cash', label: '现金' },
      { value: 'deposit', label: '存款' },
      { value: 'fund', label: '场外基金 / 理财' },
      { value: 'other', label: '其他资产' },
      { value: 'liability', label: '负债' },
    ],
  });
  QB.fmMember = QBSelect($('#fmMemberHost'), { id: 'fmMember', value: 0, options: [{ value: 0, label: '共有' }] });
  QB.fmCurrency = QBSelect($('#fmCurrencyHost'), {
    id: 'fmCurrency', value: 'CNY',
    options: [{ value: 'CNY', label: 'CNY ¥' }, { value: 'HKD', label: 'HKD' }, { value: 'USD', label: 'USD $' }],
  });
  QB.ffType = QBSelect($('#ffTypeHost'), {
    id: 'ffType', value: 'expense',
    options: [{ value: 'income', label: '收入' }, { value: 'expense', label: '支出' }],
  });
  QB.ffMember = QBSelect($('#ffMemberHost'), { id: 'ffMember', value: 0, options: [{ value: 0, label: '共有' }] });
  QB.ffCurrency = QBSelect($('#ffCurrencyHost'), {
    id: 'ffCurrency', value: 'CNY',
    options: [{ value: 'CNY', label: 'CNY ¥' }, { value: 'HKD', label: 'HKD' }, { value: 'USD', label: 'USD $' }],
  });

  // 家庭总览
  $('#famAddBtn').onclick = () => openFamilyAdd(null);
  $('#fmClose').onclick = closeFamilyAdd;
  $('#fmMask').onclick = closeFamilyAdd;
  $('#fmCancel').onclick = closeFamilyAdd;
  $('#fmSave').onclick = saveFamilyRow;
  $('#famTargetSave').onclick = saveFamilyTargets;
  $('#famMemberSeg').onclick = (e) => {
    const b = e.target.closest('.seg-btn');
    if (!b) return;
    S.famMember = +b.dataset.m;
    $$('#famMemberSeg .seg-btn').forEach((x) => x.classList.toggle('active', x === b));
    renderFamily();
  };
  $('#famFlowBtn').onclick = () => openFamilyFlow(null);
  $('#famFlowAddBtn').onclick = () => openFamilyFlow(null);
  $('#ffClose').onclick = closeFamilyFlow;
  $('#ffMask').onclick = closeFamilyFlow;
  $('#ffCancel').onclick = closeFamilyFlow;
  $('#ffSave').onclick = saveFamilyFlowRow;
  $('#famMembersBtn').onclick = openFamMembers;
  $('#fmbClose').onclick = closeFamMembers;
  $('#fmbMask').onclick = closeFamMembers;
  $('#fmbAdd').onclick = addFamMember;
  $('#fmbName').addEventListener('keydown', (e) => { if (e.key === 'Enter') addFamMember(); });

  // 持仓
  $('#portAddBtn').onclick = openPortAdd;
  $('#portOcrBtn').onclick = openPortOcr;
  // 持仓页的入口跳到独立 AI 分析页
  $('#portAiBtn').onclick = () => switchView('ai');
  $('#aiRefreshBtn').onclick = () => loadAi(true);
  $('#aiRefreshBtn').onclick = () => loadAi(true);
  $('#paClose').onclick = closePortAdd;
  $('#paMask').onclick = closePortAdd;
  $('#paCancel').onclick = closePortAdd;
  $('#paSave').onclick = savePosition;
  $('#paSideBuy').onclick = () => paSide('buy');
  $('#paSideSell').onclick = () => paSide('sell');
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

  // 大佬持仓
  $('#guruAddBtn').onclick = openGuruInv;
  $('#giClose').onclick = closeGuruInv;
  $('#giMask').onclick = closeGuruInv;
  $('#giCancel').onclick = closeGuruInv;
  $('#giSave').onclick = saveGuruInv;
  $('#giName').addEventListener('keydown', (e) => { if (e.key === 'Enter') saveGuruInv(); });
  $('#guruHoldAddBtn').onclick = openGuruHold;
  $('#ghClose').onclick = closeGuruHold;
  $('#ghMask').onclick = closeGuruHold;
  $('#ghCancel').onclick = closeGuruHold;
  $('#ghSave').onclick = saveGuruHold;
  $('#ghValue').addEventListener('keydown', (e) => { if (e.key === 'Enter') saveGuruHold(); });
  QB.giType = QBSelect($('#giTypeHost'), {
    id: 'giType', value: '13f',
    options: [{ value: '13f', label: '13F 机构' }, { value: 'sovereign', label: '主权基金 / 其他' }],
  });
  QB.ghGuru = QBSelect($('#ghGuruHost'), { id: 'ghGuru', value: 0, options: [{ value: 0, label: '—' }] });
  QB.guruInv = QBSelect($('#guruSelHost'), {
    id: 'guruInvSel', value: '0',
    options: [{ value: '0', label: '全部机构' }],
    onChange: (v) => { S.guruInv = +v; S.guruPage = 1; loadGuruHoldings(); loadGuruChanges(); },
  });
  QB.guruQ = QBSelect($('#guruQSelHost'), {
    id: 'guruQSel', value: '',
    options: [{ value: '', label: '最新一期' }],
    onChange: (v) => { S.guruQuarter = v; S.guruPage = 1; loadGuruHoldings(); loadGuruChanges(); },
  });
  if ($('#guruTable')) $('#guruTable').onclick = (e) => {
    const f = e.target.closest('[data-gfetch]');
    if (f) { e.stopPropagation(); fetchGuru13f(+f.dataset.gfetch, f); return; }
    const del = e.target.closest('[data-gdel]');
    if (del) {
      e.stopPropagation();
      QBConfirm({
        title: '删除机构', danger: true, okText: '删除',
        text: '将同时删除该机构的全部持仓记录，不可恢复。确定删除吗？',
      }).then((ok) => {
        if (!ok) return;
        fetch('/api/guru', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action: 'remove_investor', id: +del.dataset.gdel }) })
          .then(() => { QBToast.ok('已删除'); loadGuru(); });
      });
      return;
    }
    const tr = e.target.closest('tr[data-inv]');
    if (tr) { S.guruInv = +tr.dataset.inv; S.guruPage = 1; paintGuruSegs(); loadGuruHoldings(); loadGuruChanges(); }
  };
  const guruCodeClick = (e) => {
    const c = e.target.closest('[data-gcode]');
    if (c) openDrawer(c.dataset.gcode, e);
  };
  $('#guruHoldTable').onclick = guruCodeClick;   // 持仓全部来自声明文件，不提供逐行删除
  $('#guruConsTable').onclick = guruCodeClick;

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
    if (b) addInstrument(b.dataset.add, b);
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
  tickClock();
  setInterval(tickClock, 1000);
  S.timer = setInterval(() => load(false), 25000);
  const h = location.hash.slice(1);
  if (['overview', 'a', 'hk', 'us', 'etf', 'fund', 'watch', 'family', 'port', 'perf', 'trades',
       'signals', 'corr', 'ai', 'news', 'guru'].includes(h)) switchView(h);
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
