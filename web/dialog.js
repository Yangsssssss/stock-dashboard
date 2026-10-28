/* QuantBoard 弹窗提示组件 —— 纯 DOM 实现，不使用 alert / confirm / prompt 等系统组件
   提供：QBToast（轻提示）、QBConfirm（确认框）、QBAlert（提示框）
   样式见 dialog.css，主题变量跟随 --up / --dn / --accent，明暗自动适配。 */
(function () {
  'use strict';

  var MAX_TOAST = 4;
  var root = null;        // 提示容器（惰性创建）
  var mask = null;        // 弹窗遮罩（惰性创建）
  var onOk = null;        // 当前弹窗的确认回调
  var escEl = null;       // 键盘处理
  var lastFocus = null;   // 打开前的焦点元素，关闭后还原

  /* ------------------------------------------------------------------ 基础 */
  function ensureRoot() {
    if (root && document.body.contains(root)) return root;
    root = document.createElement('div');
    root.className = 'qb-toasts';
    document.body.appendChild(root);
    return root;
  }

  function icons() {
    return {
      success: '<svg viewBox="0 0 24 24"><path d="M20 6 9 17l-5-5"/></svg>',
      error: '<svg viewBox="0 0 24 24"><path d="M18 6 6 18M6 6l12 12"/></svg>',
      warn: '<svg viewBox="0 0 24 24"><path d="M12 9v4M12 17h.01"/><path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z"/></svg>',
      info: '<svg viewBox="0 0 24 24"><path d="M12 16v-4M12 8h.01"/><circle cx="12" cy="12" r="10"/></svg>'
    };
  }

  function h(tag, cls, html) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    return e;
  }

  /** 转义：组件自包含，不依赖 common.js 的加载顺序。 */
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  /* ------------------------------------------------------------------ 轻提示 */
  /**
   * 轻提示：右上角堆叠，自动消失（hover 暂停），点击关闭。
   * @param {string} type  success | error | warn | info
   * @param {string} title 主文案
   * @param {object} opt    { desc, duration }  duration=0 表示不自动关闭
   */
  function toast(type, title, opt) {
    opt = opt || {};
    var known = ['success', 'error', 'warn', 'info'];
    if (known.indexOf(type) < 0) type = 'info';

    var box = ensureRoot();
    // 超过上限时移除最旧的一条，避免堆叠过高
    while (box.children.length >= MAX_TOAST) box.removeChild(box.firstChild);

    var el = h('div', 'qb-toast qb-' + type);
    el.setAttribute('role', type === 'error' ? 'alert' : 'status');
    el.appendChild(h('div', 'qb-toast-ico', icons()[type]));
    var body = h('div', 'qb-toast-body');
    body.appendChild(h('div', 'qb-toast-title', esc(title)));
    if (opt.desc) body.appendChild(h('div', 'qb-toast-desc', esc(opt.desc)));
    el.appendChild(body);
    var close = h('button', 'qb-toast-x', '&times;');
    close.setAttribute('aria-label', '关闭');
    el.appendChild(close);

    var ms = opt.duration != null ? opt.duration : (type === 'error' ? 6000 : 3600);
    var bar = null, timer = null, left = ms, started = Date.now();

    function remove() {
      if (!el.parentNode) return;
      el.classList.add('qb-leaving');
      window.setTimeout(function () { if (el.parentNode) el.parentNode.removeChild(el); }, 220);
    }
    function tick() {
      timer = window.setTimeout(function () {
        if (el.classList.contains('qb-paused')) { left -= 0; started = Date.now(); tick(); return; }
        if (left <= 0) { remove(); return; }
        if (bar) bar.style.width = Math.max(0, (left / ms) * 100) + '%';
        var prev = Date.now();
        timer = window.setTimeout(function () { left -= Date.now() - prev; tick(); }, 100);
      }, 100);
    }
    if (ms > 0) {
      bar = h('div', 'qb-toast-bar');
      el.appendChild(bar);
      tick();
    }
    el.addEventListener('mouseenter', function () { el.classList.add('qb-paused'); if (bar) bar.style.opacity = '.35'; });
    el.addEventListener('mouseleave', function () { el.classList.remove('qb-paused'); if (bar) bar.style.opacity = ''; });
    close.addEventListener('click', function () { if (timer) window.clearTimeout(timer); remove(); });

    box.appendChild(el);
    window.requestAnimationFrame(function () { el.classList.add('qb-in'); });
    return remove;
  }

  var T = function (title, opt) { return toast('info', title, opt); };
  T.ok = function (title, opt) { return toast('success', title, opt); };
  T.err = function (title, opt) { return toast('error', title, opt); };
  T.warn = function (title, opt) { return toast('warn', title, opt); };
  T.info = function (title, opt) { return toast('info', title, opt); };

  /* ------------------------------------------------------------------ 弹窗 */
  /**
   * 通用弹窗（内部实现）。
   * @param {object} o { title, text(html), okText, cancelText, danger, showCancel, icon }
   * @returns {Promise<boolean>} confirm 结果：true=确认，false=取消
   */
  function dialog(o) {
    return new Promise(function (resolve) {
      closeDialog(true);
      lastFocus = document.activeElement;

      mask = h('div', 'qb-mask');
      var dlg = h('div', 'qb-dialog' + (o.danger ? ' qb-danger' : ''));
      dlg.setAttribute('role', 'dialog');
      dlg.setAttribute('aria-modal', 'true');

      var ico = o.icon || (o.danger ? 'warn' : 'info');
      dlg.appendChild(h('div', 'qb-dlg-ico qb-' + ico, icons()[ico]));
      dlg.appendChild(h('h3', 'qb-dlg-title', esc(o.title || '提示')));
      dlg.appendChild(h('div', 'qb-dlg-text', o.text || ''));

      var btns = h('div', 'qb-dlg-btns');
      var okBtn = h('button', 'qb-btn ' + (o.danger ? 'qb-btn-danger' : 'qb-btn-primary'), esc(o.okText || '确定'));
      btns.appendChild(okBtn);
      if (o.showCancel !== false) {
        var cancelBtn = h('button', 'qb-btn qb-btn-ghost', esc(o.cancelText || '取消'));
        cancelBtn.addEventListener('click', function () { closeDialog(); resolve(false); });
        btns.insertBefore(cancelBtn, okBtn);
      }
      dlg.appendChild(btns);

      onOk = function () { closeDialog(); resolve(true); };
      okBtn.addEventListener('click', onOk);
      mask.addEventListener('click', function () {
        if (o.showCancel === false) return;      // 纯提示框点遮罩也关闭，但不返回值语义
        closeDialog();
        resolve(false);
      });
      escEl = function (e) {
        if (e.key === 'Escape') { closeDialog(); resolve(o.showCancel === false); }
        else if (e.key === 'Enter') { e.preventDefault(); onOk && onOk(); }
      };
      document.addEventListener('keydown', escEl);

      document.body.appendChild(mask);
      document.body.appendChild(dlg);
      var m = mask;      // 局部引用：避免弹窗被瞬间关闭后 mask 被置空导致回调报错
      window.requestAnimationFrame(function () {
        m.classList.add('qb-show');
        dlg.classList.add('qb-show');
        okBtn.focus();
      });
    });
  }

  function closeDialog(instant) {
    var dlg = document.querySelector('.qb-dialog');
    if (dlg) {
      if (instant) dlg.parentNode.removeChild(dlg);
      else {
        dlg.classList.remove('qb-show');
        window.setTimeout(function () { if (dlg.parentNode) dlg.parentNode.removeChild(dlg); }, 180);
      }
    }
    if (mask) {
      if (instant) { mask.parentNode.removeChild(mask); mask = null; }
      else {
        mask.classList.remove('qb-show');
        var m = mask; mask = null;
        window.setTimeout(function () { if (m.parentNode) m.parentNode.removeChild(m); }, 180);
      }
    }
    if (escEl) { document.removeEventListener('keydown', escEl); escEl = null; }
    onOk = null;
    if (!instant && lastFocus && lastFocus.focus) { try { lastFocus.focus(); } catch (e) { } }
    lastFocus = null;
  }

  /** 确认框：返回 Promise<boolean>。 */
  function confirmBox(o) {
    o = o || {};
    return dialog({
      title: o.title || '确认操作',
      text: o.text || '',
      okText: o.okText || '确定',
      cancelText: o.cancelText || '取消',
      danger: !!o.danger,
      icon: o.icon,
      showCancel: true
    });
  }

  /** 提示框：只有一个确定按钮，点确定 / Esc 均返回 true。 */
  function alertBox(o) {
    o = o || {};
    return dialog({
      title: o.title || '提示',
      text: o.text || '',
      okText: o.okText || '知道了',
      danger: !!o.danger,
      icon: o.icon,
      showCancel: false
    });
  }

  /* ---------------------------------------------------------------- 自绘下拉组件 */
  // 替代原生 <select>：纯 DOM 实现，明暗主题自适应，支持键盘（↑/↓/Enter/Esc）、
  // 空间不足自动向上翻、同屏只展开一个。值同步写入隐藏 input（id 保留），原代码按 id 读值不受影响。
  var openSel = null;   // 当前展开的实例

  function QBSelect(host, o) {
    if (!host) return null;
    o = o || {};
    var options = (o.options || []).slice();
    var onChange = o.onChange || null;

    var hidden = h('input');
    hidden.type = 'hidden';
    if (o.id) hidden.id = o.id;
    if (o.value != null) hidden.value = o.value;

    var btn = h('button', 'qbsel-btn');
    btn.type = 'button';
    btn.setAttribute('aria-haspopup', 'listbox');
    var menu = h('div', 'qbsel-menu');
    menu.setAttribute('role', 'listbox');

    host.classList.add('qbsel');
    host.appendChild(hidden);
    host.appendChild(btn);
    host.appendChild(menu);

    var open = false, hoverIdx = -1;

    function cur() {
      for (var i = 0; i < options.length; i++)
        if (String(options[i].value) === String(hidden.value)) return options[i];
      return options[0] || { value: '', label: '—' };
    }
    function paintBtn() {
      var c = cur();
      btn.innerHTML = '<span class="qbsel-txt">' + esc(c.label) + '</span>' +
        '<svg class="qbsel-caret" viewBox="0 0 24 24"><path d="m6 9 6 6 6-6"/></svg>';
    }
    function paintMenu() {
      menu.innerHTML = options.map(function (op) {
        return '<div class="qbsel-opt' + (String(op.value) === String(hidden.value) ? ' active' : '') +
          '" role="option" data-v="' + esc(op.value) + '">' + esc(op.label) + '</div>';
      }).join('');
    }
    function setHover(i) {
      var kids = menu.children;
      for (var k = 0; k < kids.length; k++) kids[k].classList.toggle('hover', k === i);
      hoverIdx = i;
      if (kids[i]) kids[i].scrollIntoView({ block: 'nearest' });
    }
    function close() {
      if (!open) return;
      open = false;
      menu.classList.remove('open');
      btn.classList.remove('open');
      if (openSel === api) openSel = null;
    }
    function openMenu() {
      if (openSel && openSel !== api) openSel.close();
      paintMenu();
      open = true;
      menu.classList.add('open');
      btn.classList.add('open');
      var r = btn.getBoundingClientRect();          // 下方放不下且上方够用时向上翻
      menu.classList.toggle('up', window.innerHeight - r.bottom < 235 && r.top > 235);
      var act = menu.querySelector('.qbsel-opt.active');
      setHover(act ? Array.prototype.indexOf.call(menu.children, act) : 0);
      if (openSel !== api) openSel = api;
    }
    function choose(v) {
      var changed = String(hidden.value) !== String(v);
      hidden.value = v;
      paintBtn();
      close();
      lastChoose = Date.now();               // 防止 label 的合成 click 把刚关的菜单再打开
      if (changed && onChange) onChange(String(v));
    }

    var lastChoose = 0;
    btn.addEventListener('click', function (e) {
      e.stopPropagation();
      if (Date.now() - lastChoose < 350) return;   // 忽略选择后紧跟的合成 click
      open ? close() : openMenu();
    });
    menu.addEventListener('click', function (e) {
      var t = e.target.closest('.qbsel-opt');
      if (t) choose(t.dataset.v);
    });
    btn.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        if (!open) { openMenu(); return; }
        var d = e.key === 'ArrowDown' ? 1 : -1;
        setHover(Math.min(options.length - 1, Math.max(0, hoverIdx + d)));
      } else if (e.key === 'Enter') {
        e.preventDefault();
        if (open && options[hoverIdx]) choose(options[hoverIdx].value);
      } else if (e.key === 'Escape' && open) {
        e.preventDefault(); e.stopPropagation(); close();
      }
    });
    document.addEventListener('click', function (e) { if (open && !host.contains(e.target)) close(); });
    document.addEventListener('keydown', function (e) {           // 捕获阶段截获 Esc，避免连带关闭所在弹窗
      if (open && e.key === 'Escape') { e.stopPropagation(); close(); }
    }, true);
    window.addEventListener('resize', close);

    paintBtn();

    var api = {
      close: close,
      get: function () { return hidden.value; },
      setValue: function (v) { hidden.value = v; paintBtn(); },
      setOptions: function (list) {
        options = (list || []).slice();
        var ok = options.some(function (op) { return String(op.value) === String(hidden.value); });
        if (!ok && options.length) hidden.value = options[0].value;
        paintBtn();
        if (open) paintMenu();
        return api;
      },
    };
    return api;
  }

  window.QBToast = T;
  window.QBConfirm = confirmBox;
  window.QBAlert = alertBox;
  window.QBSelect = QBSelect;
})();
