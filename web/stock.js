/* QuantBoard 标的详情页 —— 独立页面，数据取自 /api/board 的同一份快照 */
'use strict';

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];

const St = {
  code: new URLSearchParams(location.search).get('code') || '',
  item: null,
  groupLabel: '',
  days: 120,
  tab: 'kline',
  watch: new Set(),
  kl: null,
  fin: null,
  hist: null,
};

/* ---------------------------------------------------------------- 渲染 */
function render() {
  const it = St.item;
  const a = it.ana || {};
  document.title = `${it.name} ${it.code.toUpperCase()} · QuantBoard`;

  $('#stName').textContent = it.name;
  $('#stCode').textContent = it.code.toUpperCase();
  $('#stGroup').textContent = St.groupLabel;
  $('#stLast').textContent = curSym(it.currency) + px(it.price);
  $('#stLast').className = 'st-last ' + cls(it.pct);
  $('#stChg').innerHTML = `<span class="${cls(it.pct)}">${it.change > 0 ? '+' : ''}${px(it.change)} · ${pctTxt(it.pct)}</span>`;

  St.watch = St.watch || new Set();
  syncStar();

  const score = a.score != null ? a.score : null;
  $('#stScore').textContent = score != null ? score.toFixed(0) : '—';
  $('#stScore').style.color = scoreColor(score || 0);
  $('#stScoreBar').style.width = (score || 0) + '%';
  $('#stScoreBar').style.background = scoreColor(score || 0);
  $('#stSignal').textContent = a.signal || '—';
  $('#stSignal').className = 'chip ' + (a.signal_cls || 'neutral');
  $('#stConcl').textContent = a.conclusion || '数据不足，无法给出技术面结论。';

  const facLabels = { trend: '趋势', momentum: '动量', volume: '量能', risk: '风险' };
  $('#stFactors').innerHTML = Object.entries(facLabels).map(([k, l]) => {
    const v = (a.factors || {})[k] ?? 50;
    return `<div class="fac-row"><span>${l}</span><span class="fac-track"><i style="width:${v}%;background:${scoreColor(v)}"></i></span><b>${v.toFixed(0)}</b></div>`;
  }).join('');

  const kv = (k, v) => `<div class="kv"><span>${k}</span><b>${v}</b></div>`;
  $('#stKv').innerHTML = [
    kv('5 日', `<span class="${cls(a.r5)}">${pctTxt(a.r5)}</span>`),
    kv('20 日', `<span class="${cls(a.r20)}">${pctTxt(a.r20)}</span>`),
    kv('60 日', `<span class="${cls(a.r60)}">${pctTxt(a.r60)}</span>`),
    kv('RSI(14)', a.rsi != null ? a.rsi.toFixed(1) : '—'),
    kv('MACD 柱', a.macd ? `<span class="${cls(a.macd.hist)}">${a.macd.hist.toFixed(3)}</span>` : '—'),
    kv('量比(5/20)', a.vol_ratio != null ? a.vol_ratio.toFixed(2) : '—'),
    kv('年化波动', a.vol_ann != null ? a.vol_ann.toFixed(1) + '%' : '—'),
    kv('ATR%', a.atr_pct != null ? a.atr_pct.toFixed(2) + '%' : '—'),
    kv('60 日回撤', `<span class="${cls(a.dd60)}">${pctTxt(a.dd60)}</span>`),
    kv('区间位置', a.pos60 != null ? a.pos60.toFixed(0) + '%' : '—'),
    kv('超额(20日)', a.rs != null ? `<span class="${cls(a.rs)}">${a.rs > 0 ? '+' : ''}${a.rs.toFixed(1)}pct</span>` : '—'),
    kv('成交额', amtTxt(it.amount_yi)),
    kv('市值', it.mktcap_yi ? amtTxt(it.mktcap_yi) : '—'),
    kv('市盈率', it.pe ? it.pe.toFixed(1) : '—'),
    kv('换手率', it.turnover != null ? it.turnover.toFixed(2) + '%' : '—'),
    ...(it.premium != null ? [
      kv('溢价率', `<span class="${cls(it.premium)}"${Math.abs(it.premium) >= 1 ? ' style="font-weight:650"' : ''} title="较净值/IOPV">${pctTxt(it.premium)}</span>`),
      kv('净值/IOPV', it.nav != null ? it.nav.toFixed(4) : '—'),
    ] : []),
    ...(it.kind === 'fund' ? [
      kv('净值日期', it.nav_date || '—'),
      kv('累计净值', it.nav_acc != null ? it.nav_acc.toFixed(4) : '—'),
      ...[['近1周', 'w1'], ['近1月', 'm1'], ['近3月', 'm3'], ['近6月', 'm6'], ['近1年', 'y1'], ['今年来', 'ytd']]
        .map(([l, k]) => {
          const v = (it.returns || {})[k];
          return kv(l, `<span class="${cls(v)}">${pctTxt(v)}</span>`);
        }),
    ] : []),
  ].join('');

  if (a.support && a.resistance) {
    const p = it.price, lo = a.support, hi = a.resistance;
    const pctIn = Math.max(0, Math.min(100, ((p - lo) / (hi - lo)) * 100));
    $('#stLevels').innerHTML = `
      <div class="lv-row"><span class="small" style="width:56px">压力位</span>
        <span class="track"><i style="left:0;width:${pctIn}%;background:var(--dn-soft)"></i></span>
        <b>${px(hi)}</b></div>
      <div class="lv-row"><span class="small" style="width:56px">现价</span>
        <span class="track"><i style="left:${pctIn}%;width:2px;background:var(--txt)"></i></span>
        <b>${px(p)}</b></div>
      <div class="lv-row"><span class="small" style="width:56px">支撑位</span>
        <span class="track"><i style="left:0;width:${pctIn}%;background:var(--up-soft)"></i></span>
        <b>${px(lo)}</b></div>
      <div class="small">压力/支撑取近 60 个交易日收盘极值，当前位于区间 ${pctIn.toFixed(0)}%。布林上轨 ${
        a.boll ? px(a.boll.up) + ' / 下轨 ' + px(a.boll.low) : '—'}。</div>`;
  } else {
    $('#stLevels').innerHTML = '<div class="small">暂无</div>';
  }

  const canFin = it.kind === 'stock';
  $('#stTabFin').style.display = canFin ? '' : 'none';
  if (!canFin && St.tab === 'fin') St.tab = 'kline';
  setTab(St.tab);
}

function syncStar() {
  const on = St.watch.has(St.code);
  const b = $('#stStar');
  b.textContent = on ? '★ 已自选' : '★ 自选';
  b.classList.toggle('on', on);
}

/* ---------------------------------------------------------------- 图表区 */
function setTab(mode) {
  St.tab = mode === 'fin' ? 'fin' : 'kline';
  $('#klineWrap').style.display = St.tab === 'fin' ? 'none' : '';
  $('#finPanel').style.display = St.tab === 'fin' ? '' : 'none';
  $$('#stTabs .ct').forEach((x) => {
    x.classList.toggle('active', St.tab === 'fin' ? x.dataset.tab === 'fin' : +x.dataset.days === St.days);
  });
}

async function loadKline() {
  try {
    const r = await fetch(`/api/kline?code=${encodeURIComponent(St.code)}&days=${St.days}`);
    St.kl = await r.json();
  } catch (e) {
    St.kl = { bars: [] };
  }
  drawKline($('#kline'), St.kl || { bars: [] });
}

async function loadFinance() {
  if (St.fin) { renderFinance(St.fin); return; }
  $('#finPanel').innerHTML = '<div class="fin-empty">加载中…</div>';
  let rows = [];
  try {
    const r = await fetch(`/api/finance?code=${encodeURIComponent(St.code)}`);
    const d = await r.json();
    rows = d.rows || [];
  } catch (e) { console.warn('finance error', e); }
  St.fin = rows;
  renderFinance(rows);
}

function renderFinance(rows) {
  if (!rows.length) {
    $('#finPanel').innerHTML = '<div class="fin-empty">暂无财报数据（指数 / ETF 或数据源未覆盖该标的）</div>';
    return;
  }
  const unit = { CNY: '元', HKD: '港元', USD: '美元' }[rows[0].currency] || '元';
  const yi = (v) => v == null ? '—' : (v / 1e8 >= 100 ? fmt(v / 1e8, 0) : fmt(v / 1e8, 1)) + '亿';
  const yoy = (v) => v == null ? '—' : `<span class="${cls(v)}">${pctTxt(v)}</span>`;
  const pc = (v, d) => v == null ? '—' : fmt(v, d) + '%';
  const head = ['报告期', `营收(${unit})`, '同比', `净利(${unit})`, '同比', '毛利率', '净利率', 'ROE', 'EPS'];
  const body = rows.map((r) => [
    esc(r.period || '—'), yi(r.revenue), yoy(r.revenueYoy), yi(r.profit), yoy(r.profitYoy),
    pc(r.grossMargin, 1), pc(r.netMargin, 1),
    pc(r.roe, 1), r.eps != null ? fmt(r.eps, 2) : '—',
  ]);
  $('#finPanel').innerHTML = `
    <div class="fin-table-wrap"><table class="fin-table">
      <thead><tr>${head.map((h) => `<th>${h}</th>`).join('')}</tr></thead>
      <tbody>${body.map((r) => `<tr>${r.map((c) => `<td>${c}</td>`).join('')}</tr>`).join('')}</tbody>
    </table></div>
    <div class="fin-foot">数据来源：东方财富 F10 · A股为累计报告期口径，美股为单季度口径</div>`;
}

async function loadHistory() {
  $('#histHint').textContent = '加载中…';
  drawHistory($('#histCanvas'), []);
  let pts = [];
  try {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), 8000);
    const r = await fetch(`/api/history?code=${encodeURIComponent(St.code)}&days=30`, { signal: ctl.signal });
    clearTimeout(timer);
    const d = await r.json();
    pts = d.points || [];
  } catch (e) { console.warn('history error', e); }
  St.hist = pts;
  if (!pts.length) {
    $('#histHint').textContent = '暂无评分历史（每轮看板刷新会自动落库，新标的需积累 1 个交易日以上）';
    return;
  }
  drawHistory($('#histCanvas'), pts);
  const sc = pts.map((p) => +p.score);
  const lo = Math.min(...sc), hi = Math.max(...sc), cur = sc[sc.length - 1];
  $('#histHint').innerHTML = `近 ${pts.length} 个交易日：最低 <b>${fmt(lo, 0)}</b> · 最高 <b>${fmt(hi, 0)}</b> · `
    + `当前 <b class="${cls(cur - sc[Math.max(0, sc.length - 2)])}">${fmt(cur, 0)}</b>`;
}

/* ---------------------------------------------------------------- 动作 */
function back() {
  if (history.length > 1) history.back();
  else location.href = 'index.html';
}

async function toggleStar() {
  const on = St.watch.has(St.code);
  try {
    const r = await fetch('/api/watchlist', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: St.code, action: on ? 'remove' : 'add' }),
    });
    const d = await r.json();
    if (d.watch) St.watch = new Set(d.watch);
    syncStar();
  } catch (e) { console.warn('watchlist failed', e); }
}

async function removeInstrument() {
  const it = St.item;
  if (!confirm(`从看板移除「${it.name} ${it.code.toUpperCase()}」？\n内置标的移除后可通过「标的管理 → 恢复默认」找回，自定义标的会直接删除。`)) return;
  try {
    await fetch('/api/instruments', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: St.code, action: 'remove' }),
    });
    location.href = 'index.html';
  } catch (e) { alert('移除失败：' + e.message); }
}

/* ---------------------------------------------------------------- 启动 */
function fail(msg) {
  $('#stName').textContent = '打不开这个标的';
  $('#stConcl').textContent = msg;
  $('.st-main').innerHTML = `<div class="panel"><div class="small" style="padding:8px 2px">${esc(msg)}　<a href="index.html" style="color:var(--accent)">← 返回看板</a></div></div>`;
}

async function boot() {
  const saved = localStorage.getItem('qb-theme');
  if (saved) document.documentElement.dataset.theme = saved;
  if (!St.code) { fail('地址缺少 code 参数，正确格式：stock.html?code=sh600036'); return; }

  try {
    const r = await fetch('/api/board');
    const d = await r.json();
    if (d.error) throw new Error(d.error);
    St.watch = new Set(d.watch || []);
    let found = null, gk = null;
    for (const [k, g] of Object.entries(d.groups || {})) {
      const f = (g.items || []).find((x) => x.code === St.code);
      if (f) { found = f; gk = k; break; }
    }
    if (!found) { fail('看板里没有这个标的（可能已被移出）：' + St.code); return; }
    St.item = found;
    St.groupLabel = (d.groups[gk] && d.groups[gk].label) || gk;
    $('#stTs').textContent = d.ts ? '行情 ' + String(d.ts).slice(11, 16) : '';
    render();
    loadKline();
    loadHistory();
    if (found.kind === 'stock') loadFinance();   // 预取，切到财报页即秒开
  } catch (e) {
    fail('行情加载失败：' + e.message);
  }
}

$('#stBack').onclick = (e) => { e.preventDefault(); back(); };
$('#stStar').onclick = toggleStar;
$('#stRemove').onclick = removeInstrument;
$('#stTheme').onclick = () => {
  const cur = document.documentElement.dataset.theme;
  document.documentElement.dataset.theme = cur === 'light' ? 'dark' : 'light';
  localStorage.setItem('qb-theme', document.documentElement.dataset.theme);
  if (St.kl) drawKline($('#kline'), St.kl);
  if (St.item) render();
};
$('#stTabs').onclick = (e) => {
  const b = e.target.closest('.ct');
  if (!b) return;
  if (b.dataset.tab === 'fin') { setTab('fin'); loadFinance(); return; }
  setTab('kline');
  St.days = +b.dataset.days;
  loadKline();
};
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') back(); });
window.addEventListener('resize', () => {
  if (St.tab === 'kline' && St.kl) drawKline($('#kline'), St.kl);
  if (St.item) drawHistory($('#histCanvas'), St.hist || []);
});

boot();
