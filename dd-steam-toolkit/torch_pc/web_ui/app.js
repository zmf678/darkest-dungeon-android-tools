(() => {
  'use strict';

  const TOKEN = document.querySelector('meta[name="torch-token"]')?.content || '';

  const state = {
    app: null,
    catalog: null,
    activeView: 'overview',
    activeSlot: '',
    activeSubTab: 'estate',
    saveDetail: null,
    snapshots: [],
    snapFilter: '',
    heroFilter: 'all',
    heroSearch: '',
    trinketSearch: '',
    codexCategory: '全部',
    codexSearch: '',
    codexQty: 1,
    dsonFile: 'persist.game.json',
    rawPath: [],
    rawEntries: [],
    rawSearch: '',
    editingHero: null,
    draftQuirks: [],
    quirkSearch: '',
    editingRaw: null,
    rulesConfig: null,
    rulesPresets: null,
    rulesInstalled: false,
    modSlot: '',
    modView: { active: [], inactive: [] },
    modSearch: '',
  };

  const $ = (sel) => document.querySelector(sel);
  const $$ = (sel) => Array.from(document.querySelectorAll(sel));

  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));

  function toast(msg, isError = false) {
    const box = $('#toast-box');
    const el = document.createElement('div');
    el.className = 'toast' + (isError ? ' err' : '');
    el.textContent = msg;
    box.appendChild(el);
    setTimeout(() => el.remove(), isError ? 5200 : 3200);
  }

  function forceFlag() {
    return Boolean($('#chk-force-write')?.checked);
  }

  async function apiGet(url) {
    const res = await fetch(url, { cache: 'no-store' });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
    return data;
  }

  async function apiPost(url, body = {}) {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Torch-Token': TOKEN },
      body: JSON.stringify({ ...body, force: forceFlag() }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
    return data;
  }

  function fmtTime(ms) {
    if (!ms) return '';
    return new Date(ms).toLocaleString('zh-CN', { hour12: false });
  }

  function fmtBytes(n) {
    if (!n) return '0 B';
    if (n < 1024) return `${n} B`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
    return `${(n / (1024 * 1024)).toFixed(2)} MB`;
  }

  function fmtCompact(n) {
    return n >= 100000 ? `${(n / 10000).toFixed(1)}万` : String(n ?? 0);
  }

  function modeLabel(code) {
    return { radiant: '光辉模式', darkest: '极暗模式', new_game_plus: '血月 / 冥暗' }[code] || code;
  }

  function slotLabel(slot) {
    const idx = parseInt(String(slot).replace('profile_', ''), 10);
    return Number.isFinite(idx) ? `档位 ${idx + 1} (${slot})` : slot;
  }

  function triggerLabel(t) {
    return {
      auto: '自动留档',
      manual: '手动快照',
      'pre-edit': '改档前保护',
      'pre-restore': '回档前保护',
      'pre-import': '导入前保护',
    }[t] || t;
  }

  // Fallback for browsers without native <dialog closedby="any">
  function setupDialogFallbacks() {
    for (const dlg of $$('dialog.modal')) {
      dlg.addEventListener('close', () => { dlg.returnValue = ''; });
    }
    if (!('closedBy' in HTMLDialogElement.prototype)) {
      const outside = (e, dlg) => {
        const r = dlg.getBoundingClientRect();
        return e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom;
      };
      for (const dlg of $$('dialog[closedby="any"]')) {
        let pressedOutside = false;
        dlg.addEventListener('pointerdown', (e) => { pressedOutside = outside(e, dlg); });
        dlg.addEventListener('pointerup', (e) => { if (pressedOutside && outside(e, dlg)) dlg.close(); });
      }
    }
  }

  // ==================== NAVIGATION ====================

  const VIEW_META = {
    overview: ['概览与 Steam 环境', '自动对接本机 Steam《暗黑地牢》，管理路径、进程安全与一键启动'],
    saves: ['存档档案柜 & 实时自动留档', 'SHA-256 去重校验快照、原子事务回档、变更对比与跨平台 ZIP 导入导出'],
    editor: ['全维度二进制 DSON 存档修改器', '无损精修庄园货币、花名册英雄、怪癖疾病、饰品仓库、城镇建筑、地牢进度与原始字段'],
    rules: ['非破坏性游戏规则修改器', '4 套一键平衡预设 + 5 组规则（含 PC 专属本体/DLC 英雄数值倍率与建筑折扣），随时还原原版'],
    mods: ['Steam 模组加载排序管理', '直接读写档位 applied_ugcs_1_0，严格保留创意工坊 Steam 与本地 mod 来源'],
  };

  function switchView(view) {
    state.activeView = view;
    $$('.nav-btn').forEach((b) => b.classList.toggle('active', b.dataset.view === view));
    $$('.view').forEach((v) => v.classList.toggle('active', v.id === `view-${view}`));
    const [t, s] = VIEW_META[view] || ['余烬 PC 工坊', ''];
    $('#page-title').textContent = t;
    $('#page-subtitle').textContent = s;

    if (view === 'saves') loadSnapshots();
    if (view === 'editor') loadSaveDetail();
    if (view === 'rules') loadRules();
    if (view === 'mods') loadMods();
  }

  // ==================== GLOBAL STATE SYNC ====================

  async function refreshAll(silent = false) {
    try {
      const [app, catalog] = await Promise.all([
        apiGet('/api/state'),
        state.catalog ? Promise.resolve(state.catalog) : apiGet('/api/catalog'),
      ]);
      state.app = app;
      state.catalog = catalog;

      const slots = app.slots || [];
      if (!state.activeSlot || !slots.some((s) => s.slot === state.activeSlot)) {
        state.activeSlot = slots[0]?.slot || '';
      }
      if (!state.modSlot || !slots.some((s) => s.slot === state.modSlot)) {
        state.modSlot = state.activeSlot;
      }

      renderChrome();
      renderOverview();

      if (state.activeView === 'saves') await loadSnapshots();
      if (state.activeView === 'editor') await loadSaveDetail();
      if (state.activeView === 'rules') await loadRules();
      if (state.activeView === 'mods') await loadMods();
      if (!silent) toast('已同步最新 Steam 存档与环境状态');
    } catch (e) {
      toast(e.message, true);
    }
  }

  function renderChrome() {
    const app = state.app;
    if (!app) return;
    $('#nav-snap-count').textContent = String(app.snapshot_count || 0);
    $('#nav-rule-badge').textContent = app.rules?.installed ? '已启' : '未启';
    $('#ov-version').textContent = `v${app.version}`;

    const w = app.watcher || {};
    const wDot = $('#sidebar-watcher-pill .dot');
    wDot.className = 'dot' + (w.running ? '' : ' muted');
    $('#sidebar-watcher-text').textContent = w.running
      ? `自动留档运行中 (${w.captured_count || 0})`
      : '自动留档已暂停';

    const running = Boolean(app.env?.game_running);
    const gDot = $('#sidebar-game-pill .dot');
    gDot.className = 'dot ' + (running ? 'warn' : 'muted');
    $('#sidebar-game-text').textContent = running ? 'Darkest.exe 运行中' : '游戏未运行（安全）';
    $('#game-running-banner').classList.toggle('hidden', !running);
  }

  // ==================== VIEW 1: OVERVIEW ====================

  function renderOverview() {
    const env = state.app?.env;
    if (!env) return;

    const kvs = [
      ['Steam 根目录', env.steam_root || '未检测到（可右侧手动指定）'],
      ['游戏安装目录', env.game_dir || '未检测到'],
      ['创意工坊目录', env.workshop_dir || '未检测到'],
      ['当前存档目录', env.active_saves_root || '未检测到'],
      ['快照档案库位置', state.app.archive_home],
      ['已安装 DLC', env.dlcs.length ? env.dlcs.map((d) => d.label).join('、') : '仅本体'],
      ['已发现 Mod', `${env.mods.filter((m) => m.source_kind === 'Steam').length} 个创意工坊 · ${env.mods.filter((m) => m.source_kind === 'mod').length} 个本地`],
    ];
    $('#ov-env-kv').innerHTML = kvs.map(([k, v]) => `<div class="kv-item"><span>${esc(k)}</span><span class="mono">${esc(v)}</span></div>`).join('');

    $('#ov-account-count').textContent = `${env.accounts.length} 个存档源`;
    const accSel = $('#ov-account-select');
    accSel.innerHTML = env.accounts.length
      ? env.accounts.map((a) => `<option value="${esc(a.saves_root)}" ${a.saves_root === env.active_saves_root ? 'selected' : ''}>账号 ${esc(a.account_id)} (${a.slot_names.length} 档位) — ${esc(a.saves_root)}</option>`).join('')
      : '<option value="">未检测到 Steam 账号目录</option>';

    $('#ov-game-dir').value = env.game_dir || '';
    $('#ov-saves-root').value = env.active_saves_root || '';
    $('#ov-dlc-summary').textContent = `DLC: ${env.dlcs.length} · 档位: ${(state.app.slots || []).length}`;

    const slots = state.app.slots || [];
    const holder = $('#ov-slot-cards');
    if (!slots.length) {
      holder.innerHTML = '<p class="muted-text">当前目录下未找到有效存档（profile_0..8）。请先启动一次游戏建档，或前往「存档档案柜」导入 ZIP 存档。</p>';
      return;
    }
    holder.innerHTML = slots.map((s) => `
      <div class="slot-card">
        <div class="card-head">
          <strong>${esc(slotLabel(s.slot))}</strong>
          <span class="pill gold">${esc(modeLabel(s.game_mode))}</span>
        </div>
        <div style="font-size:16px;font-weight:700">${esc(s.estate_name)}</div>
        <div class="muted-text">金币 ${fmtCompact(s.gold)} · 英雄 ${s.hero_count} 名 · 饰品 ${s.trinket_count} 件 · Mod ${s.applied_mods_count} 个</div>
        <div class="muted-text mono">${esc(s.date_time || '未知时间')}${s.in_raid ? ' · 地牢探险中' : ''}</div>
        <div class="btn-row mt-10">
          <button type="button" class="btn btn-sm btn-primary" data-jump-edit="${esc(s.slot)}">精修该档位</button>
          <button type="button" class="btn btn-sm" data-quick-snap="${esc(s.slot)}">立即留档</button>
        </div>
      </div>
    `).join('');

    holder.querySelectorAll('[data-jump-edit]').forEach((b) => {
      b.addEventListener('click', () => {
        state.activeSlot = b.dataset.jumpEdit;
        switchView('editor');
      });
    });
    holder.querySelectorAll('[data-quick-snap]').forEach((b) => {
      b.addEventListener('click', async () => {
        try {
          const r = await apiPost('/api/snapshots/backup', { slot: b.dataset.quickSnap, note: '概览页快捷留档' });
          toast(r.message);
          await refreshAll(true);
        } catch (e) { toast(e.message, true); }
      });
    });
  }

  // ==================== VIEW 2: SAVES VAULT ====================

  async function loadSnapshots() {
    try {
      const data = await apiGet(`/api/snapshots${state.snapFilter ? `?slot=${encodeURIComponent(state.snapFilter)}` : ''}`);
      state.snapshots = data.snapshots || [];
      if (state.app) {
        state.app.watcher = data.watcher;
        renderChrome();
      }
      renderSavesView();
    } catch (e) {
      toast(e.message, true);
    }
  }

  function renderSavesView() {
    const w = state.app?.watcher || {};
    const btn = $('#btn-toggle-watcher');
    btn.textContent = w.running ? '暂停自动留档' : '开启实时自动留档';
    btn.classList.toggle('btn-primary', !w.running);
    $('#watcher-detail-text').textContent = `${w.last_event || ''} · 本次会话已自动捕获 ${w.captured_count || 0} 份快照`;

    const slots = state.app?.slots || [];
    const sel = $('#snap-backup-slot');
    sel.innerHTML = slots.length
      ? slots.map((s) => `<option value="${esc(s.slot)}" ${s.slot === state.activeSlot ? 'selected' : ''}>${esc(slotLabel(s.slot))} · ${esc(s.estate_name)}</option>`).join('')
      : '<option value="">无可用档位</option>';

    const filterRow = $('#snap-filter-chips');
    const slotChoices = ['', ...slots.map((s) => s.slot)];
    filterRow.innerHTML = slotChoices.map((s) => `
      <button type="button" class="chip ${state.snapFilter === s ? 'active' : ''}" data-sf="${esc(s)}">
        ${s ? esc(slotLabel(s)) : '全部档位'}
      </button>
    `).join('');
    filterRow.querySelectorAll('.chip').forEach((c) => c.addEventListener('click', () => {
      state.snapFilter = c.dataset.sf;
      loadSnapshots();
    }));

    const list = $('#snap-list');
    if (!state.snapshots.length) {
      list.innerHTML = '<p class="muted-text">暂无快照记录。点击上方「立即手动留档」或开启自动留档后进游戏保存即可自动记录。</p>';
      return;
    }
    list.innerHTML = state.snapshots.map((r) => `
      <div class="snap-item">
        <div class="snap-meta">
          <div class="chip-row">
            <span class="pill gold">${esc(slotLabel(r.slot))}</span>
            <span class="pill ${r.trigger === 'auto' ? 'green' : 'arcane'}">${esc(triggerLabel(r.trigger))}</span>
            <strong>${esc(r.estate_name)}</strong>
            ${r.note ? `<span class="pill">备注: ${esc(r.note)}</span>` : ''}
          </div>
          <div class="muted-text">
            ${esc(fmtTime(r.timestamp_ms))} · 金币 ${fmtCompact(r.gold)} · 英雄 ${r.hero_count} 名 · 饰品 ${r.trinket_count} 件 · ${r.file_count} 文件 (${fmtBytes(r.size_bytes)})
          </div>
          <div class="muted-text mono">SHA-256: ${esc(r.digest.slice(0, 16))}…</div>
        </div>
        <div class="btn-row">
          <button type="button" class="btn btn-sm btn-primary" data-snap-restore="${esc(r.id)}">原子回档</button>
          <button type="button" class="btn btn-sm" data-snap-diff="${esc(r.id)}">对比变更</button>
          <button type="button" class="btn btn-sm" data-snap-note="${esc(r.id)}" data-cur-note="${esc(r.note)}">备注</button>
          <a class="btn btn-sm" href="/api/snapshots/export?id=${encodeURIComponent(r.id)}" download>导出 ZIP</a>
          <button type="button" class="btn btn-sm btn-danger" data-snap-del="${esc(r.id)}">删除</button>
        </div>
      </div>
    `).join('');

    list.querySelectorAll('[data-snap-restore]').forEach((b) => b.addEventListener('click', async () => {
      if (!confirm('确认回档到此快照？回档前会自动把当前现役存档备份一份快照，确保万无一失。')) return;
      try {
        const res = await apiPost('/api/snapshots/restore', { id: b.dataset.snapRestore });
        toast(res.message);
        await refreshAll(true);
      } catch (e) { toast(e.message, true); }
    }));

    list.querySelectorAll('[data-snap-diff]').forEach((b) => b.addEventListener('click', async () => {
      try {
        const d = await apiGet(`/api/snapshots/diff?id=${encodeURIComponent(b.dataset.snapDiff)}`);
        $('#dlg-diff-sub').textContent = d.previous_id
          ? `对比上一版本 ${d.previous_id.slice(0, 24)}…`
          : '这是该档位最早的一份快照';
        $('#dlg-diff-body').innerHTML = `
          <p class="muted-text">总文件数：${d.total_files}</p>
          <div class="field"><label>变更文件 (${d.changed.length})</label><div class="mono">${d.changed.length ? d.changed.map(esc).join('<br>') : '无变更'}</div></div>
          <div class="field"><label>新增文件 (${d.added.length})</label><div class="mono">${d.added.length ? d.added.map(esc).join('<br>') : '无'}</div></div>
          <div class="field"><label>移除文件 (${d.removed.length})</label><div class="mono">${d.removed.length ? d.removed.map(esc).join('<br>') : '无'}</div></div>
        `;
        $('#dlg-diff').showModal();
      } catch (e) { toast(e.message, true); }
    }));

    list.querySelectorAll('[data-snap-note]').forEach((b) => b.addEventListener('click', async () => {
      const next = prompt('请输入快照备注（留空清除备注）：', b.dataset.curNote || '');
      if (next === null) return;
      try {
        await apiPost('/api/snapshots/rename', { id: b.dataset.snapNote, note: next });
        toast('快照备注已更新');
        await loadSnapshots();
      } catch (e) { toast(e.message, true); }
    }));

    list.querySelectorAll('[data-snap-del]').forEach((b) => b.addEventListener('click', async () => {
      if (!confirm('确认永久删除该快照文件？')) return;
      try {
        await apiPost('/api/snapshots/delete', { id: b.dataset.snapDel });
        toast('快照已删除');
        await refreshAll(true);
      } catch (e) { toast(e.message, true); }
    }));
  }

  // ==================== VIEW 3: COMPLETE SAVE EDITOR ====================

  async function loadSaveDetail() {
    renderSlotHeader();
    if (!state.activeSlot) return;
    try {
      const detail = await apiGet(`/api/save/detail?slot=${encodeURIComponent(state.activeSlot)}`);
      state.saveDetail = detail;
      if (!detail.dson_files.includes(state.dsonFile)) {
        state.dsonFile = detail.dson_files[0] || 'persist.game.json';
        state.rawPath = [];
      }
      renderSlotHeader();
      renderEstateSubtab();
      renderHeroesSubtab();
      renderTrinketsSubtab();
      renderProgressSubtab();
      if (state.activeSubTab === 'raw') await loadRawPath();
    } catch (e) {
      toast(e.message, true);
    }
  }

  function renderSlotHeader() {
    const slots = state.app?.slots || [];
    const chipBox = $('#ed-slot-chips');
    if (!slots.length) {
      chipBox.innerHTML = '<p class="muted-text">未检测到 Steam 存档槽位，请先确认路径或导入存档。</p>';
      $('#ed-kpi-grid').innerHTML = '';
      return;
    }
    chipBox.innerHTML = slots.map((s) => `
      <button type="button" class="chip ${s.slot === state.activeSlot ? 'active' : ''}" data-eslot="${esc(s.slot)}">
        ${esc(slotLabel(s.slot))} · ${esc(s.estate_name)}
      </button>
    `).join('');
    chipBox.querySelectorAll('.chip').forEach((c) => c.addEventListener('click', () => {
      if (state.activeSlot !== c.dataset.eslot) {
        state.activeSlot = c.dataset.eslot;
        state.rawPath = [];
        loadSaveDetail();
      }
    }));

    $('#btn-dump-json').href = `/api/save/dump?slot=${encodeURIComponent(state.activeSlot)}`;
    const d = state.saveDetail;
    if (!d) return;
    const w = d.estate.wallet || {};
    const heirlooms = (w.bust || 0) + (w.portrait || 0) + (w.deed || 0) + (w.crest || 0);
    $('#ed-hero-cnt').textContent = String(d.heroes.length);
    $('#ed-trinket-cnt').textContent = String(d.trinkets.length);
    $('#ed-kpi-grid').innerHTML = `
      <div class="kpi-tile"><div class="kpi-val" style="color:var(--gold)">${fmtCompact(w.gold || 0)}</div><div class="kpi-lbl">金币</div></div>
      <div class="kpi-tile"><div class="kpi-val">${fmtCompact(heirlooms)}</div><div class="kpi-lbl">传家宝总计</div></div>
      <div class="kpi-tile"><div class="kpi-val" style="color:var(--green)">${d.heroes.length} 名</div><div class="kpi-lbl">在册英雄</div></div>
      <div class="kpi-tile"><div class="kpi-val" style="color:var(--arcane)">${d.trinkets.length} 件</div><div class="kpi-lbl">饰品仓库</div></div>
    `;
  }

  function renderEstateSubtab() {
    const est = state.saveDetail?.estate;
    if (!est || !state.catalog) return;
    $('#ed-estate-name').value = est.estate_name || '';
    $('#ed-endless-hs').value = String(est.endless_wave_highscore ?? 0);
    $$('#ed-mode-grid .mode-tile').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.mode === est.game_mode);
    });
    const wg = $('#ed-wallet-grid');
    wg.innerHTML = state.catalog.currencies.map((c) => `
      <div class="field">
        <label for="w-${esc(c.id)}">${esc(c.label)}</label>
        <input type="number" id="w-${esc(c.id)}" data-wallet="${esc(c.id)}" min="0" step="1" value="${est.wallet[c.id] ?? 0}">
      </div>
    `).join('');
  }

  function renderHeroesSubtab() {
    const heroes = state.saveDetail?.heroes || [];
    const injured = heroes.filter((h) => h.hp_fraction < 0.99 || h.stress > 0.5 || h.affliction || h.status === 3).length;
    const diseased = heroes.filter((h) => h.disease_count > 0).length;
    const dead = heroes.filter((h) => h.status === 3).length;

    const filters = [
      ['all', `全部 (${heroes.length})`],
      ['injured', `需恢复 (${injured})`],
      ['diseased', `含疾病 (${diseased})`],
      ['dead', `已阵亡 (${dead})`],
    ];
    const fRow = $('#ed-hero-filters');
    fRow.innerHTML = filters.map(([k, lbl]) => `
      <button type="button" class="chip ${state.heroFilter === k ? 'active' : ''}" data-hf="${k}">${esc(lbl)}</button>
    `).join('');
    fRow.querySelectorAll('.chip').forEach((c) => c.addEventListener('click', () => {
      state.heroFilter = c.dataset.hf;
      renderHeroesSubtab();
    }));

    const q = state.heroSearch.trim().toLowerCase();
    const shown = heroes.filter((h) => {
      if (state.heroFilter === 'injured' && !(h.hp_fraction < 0.99 || h.stress > 0.5 || h.affliction || h.status === 3)) return false;
      if (state.heroFilter === 'diseased' && h.disease_count <= 0) return false;
      if (state.heroFilter === 'dead' && h.status !== 3) return false;
      if (q && !h.name.toLowerCase().includes(q) && !h.hero_class.toLowerCase().includes(q) && !h.hero_class_label.toLowerCase().includes(q)) return false;
      return true;
    });

    const list = $('#ed-hero-list');
    if (!shown.length) {
      list.innerHTML = '<div class="card"><p class="muted-text">没有匹配当前筛选条件的英雄。</p></div>';
      return;
    }

    list.innerHTML = shown.map((h) => {
      const hpPct = Math.max(0, Math.min(100, Math.round(h.hp_fraction * 100)));
      const hpText = h.hp_is_absolute ? `${Math.round(h.hp_raw)} HP` : `${hpPct}%`;
      const stVal = Math.max(0, Math.min(200, Math.round(h.stress)));
      let badge = `<span class="pill">${esc(h.status_label)}</span>`;
      if (h.status === 3) badge = '<span class="pill red">已阵亡</span>';
      else if (h.virtue) badge = `<span class="pill green">美德:${esc(h.virtue)}</span>`;
      else if (h.affliction) badge = `<span class="pill red">折磨:${esc(h.affliction)}</span>`;

      return `
        <div class="hero-card">
          <div class="hero-top">
            <span class="hero-name">${esc(h.name)}</span>
            <span class="pill gold">${esc(h.hero_class_label)} · Lv.${h.resolve_level}</span>
            ${badge}
          </div>
          <div class="meters">
            <div>
              <div class="meter-lbl"><span>生命</span><span>${hpText}</span></div>
              <div class="meter-track"><div class="meter-fill hp" style="width:${hpPct}%"></div></div>
            </div>
            <div>
              <div class="meter-lbl"><span>压力</span><span>${stVal}/200</span></div>
              <div class="meter-track"><div class="meter-fill stress ${stVal >= 100 ? 'high' : ''}" style="width:${Math.round(stVal / 2)}%"></div></div>
            </div>
          </div>
          <div class="muted-text" style="${h.disease_count > 0 ? 'color:var(--error)' : ''}">
            武器 ${h.weapon_rank + 1} 级 · 护甲 ${h.armour_rank + 1} 级 · 怪癖 ${h.quirks.length} 个${h.disease_count > 0 ? ` · 含 ${h.disease_count} 项疾病` : ''}
          </div>
          <div class="btn-row">
            <button type="button" class="btn btn-sm" style="flex:1" data-hero-edit="${esc(h.guid)}">属性精修 →</button>
            <button type="button" class="btn btn-sm" style="flex:1" data-hero-quirks="${esc(h.guid)}">${h.disease_count > 0 ? '治病 / 怪癖 →' : '怪癖与疾病 →'}</button>
          </div>
        </div>
      `;
    }).join('');

    list.querySelectorAll('[data-hero-edit]').forEach((b) => b.addEventListener('click', () => openHeroDialog(b.dataset.heroEdit)));
    list.querySelectorAll('[data-hero-quirks]').forEach((b) => b.addEventListener('click', () => openQuirksDialog(b.dataset.heroQuirks)));
  }

  function openHeroDialog(guid) {
    const h = (state.saveDetail?.heroes || []).find((x) => x.guid === guid);
    if (!h) return;
    state.editingHero = {
      guid: h.guid,
      name: h.name,
      hero_class_label: h.hero_class_label,
      status: h.status,
      hpPct: Math.max(1, Math.min(100, Math.round(h.hp_fraction * 100))),
      stress: Math.max(0, Math.min(200, Math.round(h.stress))),
      xp: h.resolve_xp,
      weapon: h.weapon_rank,
      armour: h.armour_rank,
      virtue: h.virtue || '',
      affliction: h.affliction || '',
      revive: h.status === 3,
    };
    renderHeroDialogBody();
    const dlg = $('#dlg-hero');
    if (!dlg.open) dlg.showModal();
  }

  function renderHeroDialogBody() {
    const d = state.editingHero;
    if (!d) return;
    const thresholds = state.catalog.resolve_thresholds;
    let lv = 0;
    thresholds.forEach((t, i) => { if (d.xp >= t) lv = i; });
    $('#dlg-hero-title').textContent = `${d.name} · 属性精修`;
    $('#dlg-hero-sub').textContent = `${d.hero_class_label} · Lv.${lv} · 武器 ${d.weapon + 1} / 护甲 ${d.armour + 1}`;

    $('#dlg-hero-body').innerHTML = `
      <div class="preset-row mb-10">
        <button type="button" class="btn btn-sm" id="hp-preset-heal">满血0压复活</button>
        <button type="button" class="btn btn-sm" id="hp-preset-max">满级满装 (Lv.6)</button>
        <button type="button" class="btn btn-sm btn-primary" id="hp-preset-god">完美巅峰态</button>
      </div>
      <div class="grid-2">
        <div class="field"><label for="dh-name">英雄姓名</label><input type="text" id="dh-name" value="${esc(d.name)}"></div>
        <div class="field"><label for="dh-xp">决心经验 XP（Lv.6 需 48）</label><input type="number" id="dh-xp" min="0" value="${d.xp}"></div>
        <div class="field">
          <label for="dh-hp">生命百分比 (1–100%)</label>
          <div class="toolbar">
            ${[100, 75, 50, 25].map((v) => `<button type="button" class="chip ${d.hpPct === v ? 'active' : ''}" data-set-hp="${v}">${v}%</button>`).join('')}
            <input type="number" id="dh-hp" min="1" max="100" value="${d.hpPct}" style="width:84px">
          </div>
        </div>
        <div class="field">
          <label for="dh-stress">压力值 (0–200)</label>
          <div class="toolbar">
            ${[0, 50, 100, 170].map((v) => `<button type="button" class="chip ${d.stress === v ? 'active' : ''}" data-set-st="${v}">${v}</button>`).join('')}
            <input type="number" id="dh-stress" min="0" max="200" value="${d.stress}" style="width:84px">
          </div>
        </div>
      </div>

      <div class="rule-row">
        <label>决心等级快捷对齐</label>
        <div class="seg-group">
          ${thresholds.map((xp, i) => `<button type="button" class="${lv === i ? 'active' : ''}" data-set-lv="${xp}">Lv.${i}</button>`).join('')}
        </div>
      </div>

      <div class="grid-2">
        <div class="rule-row">
          <label>武器锻造等级</label>
          <div class="seg-group">
            ${[0, 1, 2, 3, 4].map((r) => `<button type="button" class="${d.weapon === r ? 'active' : ''}" data-set-wp="${r}">${r + 1} 级</button>`).join('')}
          </div>
        </div>
        <div class="rule-row">
          <label>护甲锻造等级</label>
          <div class="seg-group">
            ${[0, 1, 2, 3, 4].map((r) => `<button type="button" class="${d.armour === r ? 'active' : ''}" data-set-ar="${r}">${r + 1} 级</button>`).join('')}
          </div>
        </div>
      </div>

      <label class="toggle-card">
        <input type="checkbox" id="dh-revive" ${d.revive ? 'checked' : ''}>
        <div><strong>复活英雄并清除死门 / 心梗标记</strong><span>${d.status === 3 ? '该英雄已阵亡，开启后将重返花名册' : '清除异常死门与心梗记录'}</span></div>
      </label>

      <div class="rule-row">
        <label style="color:var(--green)">美德（正面爆发，与折磨互斥）</label>
        <div class="chip-grid-3">
          ${state.catalog.virtues.map((v) => `<button type="button" class="${d.virtue === v.id ? 'active-pos' : ''}" data-set-virt="${esc(v.id)}">${esc(v.label)}</button>`).join('')}
        </div>
      </div>
      <div class="rule-row">
        <label style="color:var(--error)">折磨（负面崩溃）</label>
        <div class="chip-grid-3">
          ${state.catalog.afflictions.map((a) => `<button type="button" class="${d.affliction === a.id ? 'active-neg' : ''}" data-set-aff="${esc(a.id)}">${esc(a.label)}</button>`).join('')}
        </div>
      </div>
    `;

    $('#dh-name').addEventListener('input', (e) => { d.name = e.target.value; });
    $('#dh-xp').addEventListener('input', (e) => { d.xp = Math.max(0, parseInt(e.target.value, 10) || 0); });
    $('#dh-hp').addEventListener('input', (e) => { d.hpPct = Math.max(1, Math.min(100, parseInt(e.target.value, 10) || 100)); });
    $('#dh-stress').addEventListener('input', (e) => { d.stress = Math.max(0, Math.min(200, parseInt(e.target.value, 10) || 0)); });
    $('#dh-revive').addEventListener('change', (e) => { d.revive = e.target.checked; });

    $('#hp-preset-heal').addEventListener('click', () => { d.hpPct = 100; d.stress = 0; d.affliction = ''; d.revive = true; renderHeroDialogBody(); });
    $('#hp-preset-max').addEventListener('click', () => { d.xp = 48; d.weapon = 4; d.armour = 4; renderHeroDialogBody(); });
    $('#hp-preset-god').addEventListener('click', () => { d.hpPct = 100; d.stress = 0; d.xp = 48; d.weapon = 4; d.armour = 4; d.affliction = ''; d.virtue = 'vigorous'; d.revive = true; renderHeroDialogBody(); });

    $$('[data-set-hp]').forEach((b) => b.addEventListener('click', () => { d.hpPct = Number(b.dataset.setHp); renderHeroDialogBody(); }));
    $$('[data-set-st]').forEach((b) => b.addEventListener('click', () => { d.stress = Number(b.dataset.setSt); renderHeroDialogBody(); }));
    $$('[data-set-lv]').forEach((b) => b.addEventListener('click', () => { d.xp = Number(b.dataset.setLv); renderHeroDialogBody(); }));
    $$('[data-set-wp]').forEach((b) => b.addEventListener('click', () => { d.weapon = Number(b.dataset.setWp); renderHeroDialogBody(); }));
    $$('[data-set-ar]').forEach((b) => b.addEventListener('click', () => { d.armour = Number(b.dataset.setAr); renderHeroDialogBody(); }));
    $$('[data-set-virt]').forEach((b) => b.addEventListener('click', () => { d.virtue = b.dataset.setVirt; if (d.virtue) d.affliction = ''; renderHeroDialogBody(); }));
    $$('[data-set-aff]').forEach((b) => b.addEventListener('click', () => { d.affliction = b.dataset.setAff; if (d.affliction) d.virtue = ''; renderHeroDialogBody(); }));
  }

  function openQuirksDialog(guid) {
    const h = (state.saveDetail?.heroes || []).find((x) => x.guid === guid);
    if (!h) return;
    state.editingHero = { guid: h.guid, name: h.name, hero_class_label: h.hero_class_label };
    state.draftQuirks = h.quirks.map((q) => ({ id: q.id, label: q.label, locked: Boolean(q.locked), is_disease: Boolean(q.is_disease) }));
    state.quirkSearch = '';
    renderQuirksDialogBody();
    const dlg = $('#dlg-quirks');
    if (!dlg.open) dlg.showModal();
  }

  function renderQuirksDialogBody() {
    const h = state.editingHero;
    if (!h) return;
    const diseaseSet = new Set(state.catalog.diseases.map((d) => d.id));
    const disCnt = state.draftQuirks.filter((q) => q.is_disease || diseaseSet.has(q.id)).length;
    $('#dlg-quirks-title').textContent = `${h.name} · 怪癖与疾病`;
    $('#dlg-quirks-sub').textContent = `${h.hero_class_label} · 当前 ${state.draftQuirks.length} 项特质（含 ${disCnt} 项疾病）`;

    const ownedIds = new Set(state.draftQuirks.map((q) => q.id));
    const q = state.quirkSearch.trim().toLowerCase();
    const codex = state.catalog.positive_quirks.filter((item) => !q || item.label.toLowerCase().includes(q) || item.id.toLowerCase().includes(q));

    $('#dlg-quirks-body').innerHTML = `
      <div class="btn-row mb-10">
        <button type="button" class="btn btn-sm ${disCnt > 0 ? 'btn-primary' : ''}" id="qk-cure-all">一键清除全部疾病 (${disCnt})</button>
        <button type="button" class="btn btn-sm" id="qk-lock-all">锁定全部非疾病项</button>
      </div>
      <div class="snap-list">
        ${state.draftQuirks.length ? state.draftQuirks.map((item, idx) => {
          const isDis = item.is_disease || diseaseSet.has(item.id);
          return `
            <div class="raw-item" style="${isDis ? 'background:var(--crimson-bg);border-color:var(--red)' : ''}">
              <div>
                <strong>${esc(item.label || item.id)}</strong>
                ${item.locked ? '<span class="pill gold" style="margin-left:6px">已锁定</span>' : ''}
                ${isDis ? '<span class="pill red" style="margin-left:6px">疾病</span>' : ''}
                <div class="muted-text mono">${esc(item.id)}</div>
              </div>
              <div class="btn-row">
                ${!isDis ? `<button type="button" class="btn btn-sm" data-qk-lock="${idx}">${item.locked ? '解锁' : '锁定'}</button>` : ''}
                <button type="button" class="btn btn-sm btn-danger" data-qk-del="${idx}">移除</button>
              </div>
            </div>
          `;
        }).join('') : '<p class="muted-text">当前没有任何怪癖或疾病，可从下方图鉴添加。</p>'}
      </div>

      <h3 class="mt-14">正面怪癖图鉴（精选中文 + Steam 全量库，点击即锁定添加）</h3>
      <div class="toolbar mt-10">
        <input type="search" id="qk-search" value="${esc(state.quirkSearch)}" placeholder="搜索怪癖名称、效果或英文 ID（例如 速度 / 暴击 / luminous）…">
      </div>
      <div class="snap-list" style="max-height:260px;overflow-y:auto">
        ${codex.slice(0, 80).map((entry) => {
          const has = ownedIds.has(entry.id);
          return `
            <div class="raw-item" style="${has ? 'background:var(--green-bg);border-color:var(--green)' : ''}">
              <div><strong>${esc(entry.label)}</strong><div class="muted-text mono">${esc(entry.id)} · ${esc(entry.category)}</div></div>
              ${has ? '<span class="pill green">已持有</span>' : `<button type="button" class="btn btn-sm btn-primary" data-qk-add="${esc(entry.id)}" data-qk-lbl="${esc(entry.label)}">+ 锁定添加</button>`}
            </div>
          `;
        }).join('')}
      </div>

      <div class="toolbar mt-12">
        <input type="text" id="qk-custom-id" placeholder="添加自定义或 Mod 怪癖 ID（如 twilight_dreamer）">
        <button type="button" class="btn" id="qk-custom-add">+ 添加自定义 ID</button>
      </div>
    `;

    $('#qk-cure-all').addEventListener('click', () => {
      state.draftQuirks = state.draftQuirks.filter((x) => !(x.is_disease || diseaseSet.has(x.id)));
      renderQuirksDialogBody();
    });
    $('#qk-lock-all').addEventListener('click', () => {
      state.draftQuirks.forEach((x) => { if (!(x.is_disease || diseaseSet.has(x.id))) x.locked = true; });
      renderQuirksDialogBody();
    });
    $$('[data-qk-lock]').forEach((b) => b.addEventListener('click', () => {
      const i = Number(b.dataset.qkLock);
      state.draftQuirks[i].locked = !state.draftQuirks[i].locked;
      renderQuirksDialogBody();
    }));
    $$('[data-qk-del]').forEach((b) => b.addEventListener('click', () => {
      state.draftQuirks.splice(Number(b.dataset.qkDel), 1);
      renderQuirksDialogBody();
    }));
    $$('[data-qk-add]').forEach((b) => b.addEventListener('click', () => {
      state.draftQuirks.push({ id: b.dataset.qkAdd, label: b.dataset.qkLbl, locked: true, is_disease: false });
      renderQuirksDialogBody();
    }));
    $('#qk-search').addEventListener('input', (e) => {
      const pos = e.target.selectionStart;
      state.quirkSearch = e.target.value;
      renderQuirksDialogBody();
      const inp = $('#qk-search');
      inp.focus();
      inp.setSelectionRange(pos, pos);
    });
    $('#qk-custom-add').addEventListener('click', () => {
      const id = $('#qk-custom-id').value.trim();
      if (!id) return;
      if (!state.draftQuirks.some((x) => x.id === id)) {
        state.draftQuirks.push({ id, label: id, locked: true, is_disease: diseaseSet.has(id) });
        renderQuirksDialogBody();
      } else {
        toast('该怪癖已在列表中');
      }
    });
  }

  function renderTrinketsSubtab() {
    const trinkets = state.saveDetail?.trinkets || [];
    const q = state.trinketSearch.trim().toLowerCase();
    const shown = trinkets.filter((t) => !q || t.label.toLowerCase().includes(q) || t.id.toLowerCase().includes(q) || t.category.toLowerCase().includes(q));
    const list = $('#ed-trinket-list');
    if (!shown.length) {
      list.innerHTML = `<div class="card"><p class="muted-text">${trinkets.length ? '没有匹配的饰品。' : '饰品仓库为空，可点击上方按钮打开图鉴添加。'}</p></div>`;
      return;
    }
    list.innerHTML = shown.map((t) => `
      <div class="trinket-item">
        <div>
          <div class="chip-row"><span class="pill gold">${esc(t.category)}</span><strong>${esc(t.label)}</strong></div>
          <div class="muted-text mono">#${t.index} · ${esc(t.id)}</div>
        </div>
        <div class="btn-row">
          <button type="button" class="btn btn-sm" data-tr-dup="${esc(t.id)}">+1</button>
          <button type="button" class="btn btn-sm btn-danger" data-tr-del="${t.index}">移除</button>
        </div>
      </div>
    `).join('');

    list.querySelectorAll('[data-tr-dup]').forEach((b) => b.addEventListener('click', async () => {
      try {
        await apiPost('/api/save/trinket/add', { slot: state.activeSlot, trinket_id: b.dataset.trDup, amount: 1 });
        toast('已再添加 1 件饰品');
        await loadSaveDetail();
      } catch (e) { toast(e.message, true); }
    }));
    list.querySelectorAll('[data-tr-del]').forEach((b) => b.addEventListener('click', async () => {
      try {
        await apiPost('/api/save/trinket/remove', { slot: state.activeSlot, index: Number(b.dataset.trDel) });
        toast('已移除饰品');
        await loadSaveDetail();
      } catch (e) { toast(e.message, true); }
    }));
  }

  function renderCodexDialog() {
    const owned = new Map();
    (state.saveDetail?.trinkets || []).forEach((t) => owned.set(t.id, (owned.get(t.id) || 0) + 1));
    const cats = ['全部', ...Array.from(new Set(state.catalog.trinkets.map((t) => t.category)))];
    const q = state.codexSearch.trim().toLowerCase();
    const list = state.catalog.trinkets.filter((t) => {
      if (state.codexCategory !== '全部' && t.category !== state.codexCategory) return false;
      if (q && !t.label.toLowerCase().includes(q) && !t.id.toLowerCase().includes(q)) return false;
      return true;
    });

    $('#dlg-codex-body').innerHTML = `
      <div class="toolbar">
        <input type="search" id="cx-search" value="${esc(state.codexSearch)}" placeholder="搜索饰品中文名或英文 ID（例如 祖先 / 专注 / ring）…">
        <div class="chip-row">
          <span class="muted-text">单次添加:</span>
          ${[1, 2, 4].map((n) => `<button type="button" class="chip ${state.codexQty === n ? 'active' : ''}" data-cx-qty="${n}">×${n}</button>`).join('')}
        </div>
      </div>
      <div class="chip-row mt-10">
        ${cats.map((c) => `<button type="button" class="chip ${state.codexCategory === c ? 'active' : ''}" data-cx-cat="${esc(c)}">${esc(c)}</button>`).join('')}
      </div>
      <div class="toolbar mt-10">
        <input type="text" id="cx-custom-id" placeholder="或直接输入任意原版 / Mod 饰品 ID">
        <button type="button" class="btn" id="cx-custom-add">+ 添加 ×${state.codexQty}</button>
      </div>
      <div class="trinket-grid mt-12">
        ${list.slice(0, 120).map((e) => {
          const cnt = owned.get(e.id) || 0;
          return `
            <div class="trinket-item">
              <div>
                <div class="chip-row">
                  <span class="pill gold">${esc(e.category)}</span>
                  <strong>${esc(e.label)}</strong>
                  ${cnt > 0 ? `<span class="pill green">已有 ×${cnt}</span>` : ''}
                </div>
                <div class="muted-text mono">${esc(e.id)}</div>
              </div>
              <button type="button" class="btn btn-sm btn-primary" data-cx-add="${esc(e.id)}">+ ×${state.codexQty}</button>
            </div>
          `;
        }).join('')}
      </div>
    `;

    $('#cx-search').addEventListener('input', (e) => {
      const pos = e.target.selectionStart;
      state.codexSearch = e.target.value;
      renderCodexDialog();
      const inp = $('#cx-search');
      inp.focus();
      inp.setSelectionRange(pos, pos);
    });
    $$('[data-cx-qty]').forEach((b) => b.addEventListener('click', () => { state.codexQty = Number(b.dataset.cxQty); renderCodexDialog(); }));
    $$('[data-cx-cat]').forEach((b) => b.addEventListener('click', () => { state.codexCategory = b.dataset.cxCat; renderCodexDialog(); }));
    $$('[data-cx-add]').forEach((b) => b.addEventListener('click', async () => {
      try {
        const res = await apiPost('/api/save/trinket/add', { slot: state.activeSlot, trinket_id: b.dataset.cxAdd, amount: state.codexQty });
        toast(res.message);
        await loadSaveDetail();
        renderCodexDialog();
      } catch (e) { toast(e.message, true); }
    }));
    $('#cx-custom-add').addEventListener('click', async () => {
      const id = $('#cx-custom-id').value.trim();
      if (!id) return;
      try {
        const res = await apiPost('/api/save/trinket/add', { slot: state.activeSlot, trinket_id: id, amount: state.codexQty });
        toast(res.message);
        await loadSaveDetail();
        renderCodexDialog();
      } catch (e) { toast(e.message, true); }
    });
  }

  function renderProgressSubtab() {
    const blds = state.saveDetail?.buildings || [];
    $('#ed-building-grid').innerHTML = blds.map((b) => `
      <div class="building-item">
        <strong>${esc(b.label)}</strong>
        <span class="pill ${b.unlocked >= b.total ? 'green' : 'gold'}">${b.unlocked} / ${b.total}</span>
      </div>
    `).join('');

    const dungeons = state.saveDetail?.dungeons || [];
    $('#ed-dungeon-grid').innerHTML = dungeons.map((d) => `
      <div class="dungeon-item">
        <div class="card-head">
          <strong>${esc(d.label)}</strong>
          <span class="pill gold">Lv.${d.level}</span>
        </div>
        <input type="number" class="mt-10" data-dungeon="${esc(d.id)}" min="0" value="${d.xp}">
      </div>
    `).join('');
  }

  async function loadRawPath() {
    if (!state.activeSlot) return;
    try {
      const pStr = state.rawPath.map(encodeURIComponent).join('/');
      const data = await apiGet(`/api/save/raw?slot=${encodeURIComponent(state.activeSlot)}&file=${encodeURIComponent(state.dsonFile)}&path=${encodeURIComponent(pStr)}`);
      state.rawEntries = data.entries || [];
      renderRawSubtab();
    } catch (e) {
      toast(e.message, true);
    }
  }

  function renderRawSubtab() {
    const files = state.saveDetail?.dson_files || [];
    const fRow = $('#ed-dson-files');
    fRow.innerHTML = files.map((f) => `
      <button type="button" class="chip ${f === state.dsonFile ? 'active' : ''}" data-rf="${esc(f)}">
        ${esc(f.replace(/^persist\./, '').replace(/\.json$/, ''))}
      </button>
    `).join('');
    fRow.querySelectorAll('.chip').forEach((c) => c.addEventListener('click', () => {
      state.dsonFile = c.dataset.rf;
      state.rawPath = [];
      state.rawSearch = '';
      $('#ed-raw-search').value = '';
      loadRawPath();
    }));

    const crumbs = $('#ed-raw-crumbs');
    crumbs.innerHTML = [
      `<button type="button" class="chip ${state.rawPath.length === 0 ? 'active' : ''}" data-rc="-1">/${esc(state.dsonFile)}</button>`,
      ...state.rawPath.map((seg, idx) => `<span>›</span><button type="button" class="chip ${idx === state.rawPath.length - 1 ? 'active' : ''}" data-rc="${idx}">${esc(seg)}</button>`),
    ].join('');
    crumbs.querySelectorAll('[data-rc]').forEach((b) => b.addEventListener('click', () => {
      const idx = Number(b.dataset.rc);
      state.rawPath = idx < 0 ? [] : state.rawPath.slice(0, idx + 1);
      loadRawPath();
    }));

    const q = state.rawSearch.trim().toLowerCase();
    const shown = state.rawEntries.filter((e) => !q || e.key.toLowerCase().includes(q) || e.display_value.toLowerCase().includes(q));
    const list = $('#ed-raw-list');
    if (!shown.length) {
      list.innerHTML = '<div class="card"><p class="muted-text">当前层级没有匹配的字段。</p></div>';
      return;
    }
    list.innerHTML = shown.map((e, i) => `
      <div class="raw-item">
        <div style="min-width:0;flex:1">
          <div class="chip-row">
            <span class="pill ${e.is_object ? 'gold' : (e.type_tag === 'bool' ? 'green' : 'arcane')}">${esc(e.type_tag.toUpperCase())}</span>
            <strong>${esc(e.key)}</strong>
          </div>
          <div class="muted-text mono" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(e.display_value)}</div>
        </div>
        ${e.is_object
          ? `<button type="button" class="btn btn-sm" data-raw-open="${esc(e.key)}">展开 →</button>`
          : (e.editable ? `<button type="button" class="btn btn-sm btn-primary" data-raw-edit="${i}">编辑 →</button>` : '')}
      </div>
    `).join('');

    list.querySelectorAll('[data-raw-open]').forEach((b) => b.addEventListener('click', () => {
      state.rawPath.push(b.dataset.rawOpen);
      state.rawSearch = '';
      $('#ed-raw-search').value = '';
      loadRawPath();
    }));
    list.querySelectorAll('[data-raw-edit]').forEach((b) => b.addEventListener('click', () => {
      const entry = shown[Number(b.dataset.rawEdit)];
      openRawEditDialog(entry);
    }));
  }

  function openRawEditDialog(entry) {
    state.editingRaw = { ...entry, draftType: entry.type_tag, draftVal: entry.raw_value };
    renderRawEditDialog();
    $('#dlg-raw').showModal();
  }

  function renderRawEditDialog() {
    const r = state.editingRaw;
    if (!r) return;
    $('#dlg-raw-title').textContent = `编辑节点：${r.key}`;
    $('#dlg-raw-path').textContent = `${state.dsonFile}  /${[...state.rawPath, r.key].join('/')}`;
    const isNum = r.type_tag === 'int' || r.type_tag === 'float';
    $('#dlg-raw-body').innerHTML = `
      <div class="kv-item"><span>当前读值</span><span class="mono">${esc(r.display_value)}</span></div>
      ${isNum ? `
        <div class="rule-row">
          <label>4 字节数值编码类型</label>
          <div class="seg-group">
            <button type="button" class="${r.draftType === 'int' ? 'active' : ''}" data-rt="int">int32 整数</button>
            <button type="button" class="${r.draftType === 'float' ? 'active' : ''}" data-rt="float">float32 浮点</button>
          </div>
        </div>` : ''}
      ${r.draftType === 'bool' ? `
        <div class="rule-row">
          <label>布尔值</label>
          <div class="seg-group">
            <button type="button" class="${r.draftVal === 'true' ? 'active' : ''}" data-rb="true">true (1)</button>
            <button type="button" class="${r.draftVal !== 'true' ? 'active' : ''}" data-rb="false">false (0)</button>
          </div>
        </div>` : `
        <div class="field">
          <label for="dr-val">新值 (${esc(r.draftType)})</label>
          <input type="text" id="dr-val" value="${esc(r.draftVal)}">
        </div>`}
    `;
    $$('[data-rt]').forEach((b) => b.addEventListener('click', () => { r.draftType = b.dataset.rt; renderRawEditDialog(); }));
    $$('[data-rb]').forEach((b) => b.addEventListener('click', () => { r.draftVal = b.dataset.rb; renderRawEditDialog(); }));
    $('#dr-val')?.addEventListener('input', (e) => { r.draftVal = e.target.value; });
  }

  // ==================== VIEW 4: RULES MODIFIER ====================

  async function loadRules() {
    try {
      const data = await apiGet('/api/rules');
      state.rulesInstalled = data.installed;
      state.rulesConfig = data.config;
      state.rulesPresets = data.presets;
      renderRulesView();
    } catch (e) {
      toast(e.message, true);
    }
  }

  function renderRulesView() {
    const cfg = state.rulesConfig;
    if (!cfg) return;
    const pill = $('#rule-status-pill');
    pill.textContent = state.rulesInstalled ? '已安装至 Steam mods/' : '尚未安装';
    pill.className = 'pill ' + (state.rulesInstalled ? 'green' : '');

    $$('.seg-group[data-rule]').forEach((grp) => {
      const key = grp.dataset.rule;
      const cur = Number(cfg[key]);
      grp.querySelectorAll('button').forEach((btn) => {
        btn.classList.toggle('active', Math.abs(Number(btn.dataset.val) - cur) < 0.02);
      });
    });
    $$('[data-rule-check]').forEach((cb) => {
      cb.checked = Boolean(cfg[cb.dataset.ruleCheck]);
    });
    const hitCb = $('[data-rule-hit100]');
    if (hitCb) hitCb.checked = Number(cfg.always_hit_cap) >= 0.999;

    $('#rule-hero-hp').value = String(cfg.hero_hp_multiplier ?? 1);
    $('#rule-hero-dmg').value = String(cfg.hero_dmg_multiplier ?? 1);
    $('#rule-hero-spd').value = String(cfg.hero_speed_bonus ?? 0);
    $('#rule-hero-dodge').value = String(cfg.hero_dodge_bonus ?? 0);
    $('#rule-bld-discount').value = String(cfg.building_cost_discount ?? 0);

    const slots = state.app?.slots || [];
    const sel = $('#rule-enable-slot');
    sel.innerHTML = [
      '<option value="">仅安装 Mod，稍后在游戏内勾选</option>',
      ...slots.map((s) => `<option value="${esc(s.slot)}" ${s.slot === state.activeSlot ? 'selected' : ''}>安装并置顶启用到 ${esc(slotLabel(s.slot))} (${esc(s.estate_name)})</option>`),
    ].join('');
  }

  // ==================== VIEW 5: MOD MANAGER ====================

  async function loadMods() {
    const slots = state.app?.slots || [];
    const sel = $('#mod-slot-select');
    sel.innerHTML = slots.length
      ? slots.map((s) => `<option value="${esc(s.slot)}" ${s.slot === state.modSlot ? 'selected' : ''}>${esc(slotLabel(s.slot))} · ${esc(s.estate_name)}</option>`).join('')
      : '<option value="">无档位</option>';
    if (!state.modSlot) return;
    try {
      state.modView = await apiGet(`/api/mods?slot=${encodeURIComponent(state.modSlot)}`);
      renderModsView();
    } catch (e) {
      toast(e.message, true);
    }
  }

  function renderModsView() {
    const { active = [], inactive = [] } = state.modView;
    $('#mod-active-cnt').textContent = String(active.length);
    $('#mod-inactive-cnt').textContent = String(inactive.length);

    const aList = $('#mod-active-list');
    aList.innerHTML = active.length ? active.map((m, idx) => `
      <div class="mod-item">
        <div style="min-width:0;flex:1">
          <div class="chip-row">
            <span class="pill ${m.source === 'Steam' ? 'arcane' : 'gold'}">${esc(m.source)}</span>
            <strong>${idx + 1}. ${esc(m.title)}</strong>
          </div>
          <div class="muted-text mono">${esc(m.name)}${m.version ? ` · v${esc(m.version)}` : ''}${m.installed ? '' : ' · (本机未找到文件)'}</div>
        </div>
        <div class="btn-row">
          <button type="button" class="btn btn-sm" data-m-up="${idx}" ${idx === 0 ? 'disabled' : ''}>↑</button>
          <button type="button" class="btn btn-sm" data-m-down="${idx}" ${idx === active.length - 1 ? 'disabled' : ''}>↓</button>
          <button type="button" class="btn btn-sm btn-danger" data-m-off="${idx}">停用</button>
        </div>
      </div>
    `).join('') : '<p class="muted-text">当前档位未启用任何 Mod。</p>';

    const q = state.modSearch.trim().toLowerCase();
    const filtered = inactive.filter((m) => !q || m.title.toLowerCase().includes(q) || m.name.toLowerCase().includes(q));
    const iList = $('#mod-inactive-list');
    iList.innerHTML = filtered.length ? filtered.map((m) => `
      <div class="mod-item">
        <div style="min-width:0;flex:1">
          <div class="chip-row">
            <span class="pill ${m.source === 'Steam' ? 'arcane' : 'gold'}">${esc(m.source)}</span>
            <strong>${esc(m.title)}</strong>
          </div>
          <div class="muted-text mono">${esc(m.name)}${m.version ? ` · v${esc(m.version)}` : ''}</div>
        </div>
        <button type="button" class="btn btn-sm btn-primary" data-m-on="${esc(m.name)}" data-m-src="${esc(m.source)}">+ 启用</button>
      </div>
    `).join('') : '<p class="muted-text">没有可添加的未启用 Mod。</p>';

    aList.querySelectorAll('[data-m-up]').forEach((b) => b.addEventListener('click', () => {
      const i = Number(b.dataset.mUp);
      if (i > 0) { [active[i - 1], active[i]] = [active[i], active[i - 1]]; renderModsView(); }
    }));
    aList.querySelectorAll('[data-m-down]').forEach((b) => b.addEventListener('click', () => {
      const i = Number(b.dataset.mDown);
      if (i + 1 < active.length) { [active[i + 1], active[i]] = [active[i], active[i + 1]]; renderModsView(); }
    }));
    aList.querySelectorAll('[data-m-off]').forEach((b) => b.addEventListener('click', () => {
      const [removed] = active.splice(Number(b.dataset.mOff), 1);
      inactive.unshift(removed);
      renderModsView();
    }));
    iList.querySelectorAll('[data-m-on]').forEach((b) => b.addEventListener('click', () => {
      const idx = inactive.findIndex((x) => x.name === b.dataset.mOn && x.source === b.dataset.mSrc);
      if (idx >= 0) {
        const [picked] = inactive.splice(idx, 1);
        active.unshift({ ...picked, installed: true });
        renderModsView();
      }
    }));
  }

  // ==================== EVENT BINDINGS ====================

  function bindEvents() {
    $$('.nav-btn').forEach((b) => b.addEventListener('click', () => switchView(b.dataset.view)));
    $('#btn-refresh-all').addEventListener('click', () => refreshAll(false));
    $('#btn-launch-game').addEventListener('click', async () => {
      try {
        const res = await apiPost('/api/launch-game');
        toast(res.message);
      } catch (e) { toast(e.message, true); }
    });

    $$('[data-open-folder]').forEach((b) => b.addEventListener('click', async () => {
      try {
        const res = await apiPost('/api/open-folder', { kind: b.dataset.openFolder });
        toast(res.message);
      } catch (e) { toast(e.message, true); }
    }));

    $('#ov-account-select').addEventListener('change', (e) => {
      if (e.target.value) $('#ov-saves-root').value = e.target.value;
    });
    $('#btn-save-paths').addEventListener('click', async () => {
      try {
        const res = await apiPost('/api/config', {
          game_dir: $('#ov-game-dir').value.trim(),
          saves_root: $('#ov-saves-root').value.trim(),
        });
        toast(res.message);
        await refreshAll(true);
      } catch (e) { toast(e.message, true); }
    });
    $('#btn-reset-paths').addEventListener('click', async () => {
      try {
        await apiPost('/api/config', { game_dir: '', saves_root: '' });
        toast('已恢复自动探测');
        await refreshAll(true);
      } catch (e) { toast(e.message, true); }
    });

    // Saves vault actions
    $('#btn-toggle-watcher').addEventListener('click', async () => {
      try {
        const next = !Boolean(state.app?.watcher?.running);
        const res = await apiPost('/api/watcher/toggle', { enable: next });
        state.app.watcher = res.watcher;
        renderChrome();
        renderSavesView();
        toast(next ? '实时自动留档已开启' : '实时自动留档已暂停');
      } catch (e) { toast(e.message, true); }
    });
    $('#btn-manual-backup').addEventListener('click', async () => {
      const slot = $('#snap-backup-slot').value;
      if (!slot) return;
      try {
        const res = await apiPost('/api/snapshots/backup', { slot, note: $('#snap-backup-note').value.trim() });
        $('#snap-backup-note').value = '';
        toast(res.message);
        await refreshAll(true);
      } catch (e) { toast(e.message, true); }
    });
    $('#btn-prune-snaps').addEventListener('click', async () => {
      const keep = prompt('每个档位保留最近多少份无备注的自动快照？（带备注和手动快照不受影响）', '30');
      if (keep === null) return;
      try {
        const res = await apiPost('/api/snapshots/prune', { keep_per_slot: Math.max(1, parseInt(keep, 10) || 30) });
        toast(res.message);
        await refreshAll(true);
      } catch (e) { toast(e.message, true); }
    });
    $('#btn-open-import-dialog').addEventListener('click', () => $('#dlg-import').showModal());
    $('#btn-import-submit').addEventListener('click', async () => {
      const file = $('#imp-file').files?.[0];
      if (!file) { toast('请先选择一个 ZIP 存档文件', true); return; }
      try {
        const qs = new URLSearchParams({
          target_slot: $('#imp-slot').value,
          convert_mod_to_steam: $('#imp-convert-steam').checked ? '1' : '0',
          force: forceFlag() ? '1' : '0',
        });
        const res = await fetch(`/api/snapshots/import?${qs}`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/zip', 'X-Torch-Token': TOKEN },
          body: await file.arrayBuffer(),
        });
        const data = await res.json();
        if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
        $('#dlg-import').close();
        toast(data.message);
        await refreshAll(true);
      } catch (e) { toast(e.message, true); }
    });

    // Save editor subtabs
    $$('#ed-subtabs .subtab').forEach((btn) => btn.addEventListener('click', () => {
      state.activeSubTab = btn.dataset.subtab;
      $$('#ed-subtabs .subtab').forEach((b) => b.classList.toggle('active', b === btn));
      $$('.subpanel').forEach((p) => p.classList.toggle('active', p.id === `sub-${state.activeSubTab}`));
      if (state.activeSubTab === 'raw') loadRawPath();
    }));

    // Estate actions
    $$('#ed-mode-grid .mode-tile').forEach((btn) => btn.addEventListener('click', async () => {
      try {
        const res = await apiPost('/api/save/estate', { slot: state.activeSlot, game_mode: btn.dataset.mode });
        toast(res.message);
        await refreshAll(true);
      } catch (e) { toast(e.message, true); }
    }));
    $('#btn-add-100k').addEventListener('click', () => {
      const inp = $('#w-gold');
      if (inp) inp.value = String((parseInt(inp.value, 10) || 0) + 100000);
    });
    $('#btn-add-100h').addEventListener('click', () => {
      ['bust', 'portrait', 'deed', 'crest'].forEach((k) => {
        const inp = $(`#w-${k}`);
        if (inp) inp.value = String((parseInt(inp.value, 10) || 0) + 100);
      });
    });
    $('#btn-max-wallet').addEventListener('click', async () => {
      try {
        const res = await apiPost('/api/save/estate', {
          slot: state.activeSlot,
          wallet: { gold: 999999, bust: 999, portrait: 999, deed: 999, crest: 999, shard: 9999, blueprint: 20 },
        });
        toast(res.message);
        await refreshAll(true);
      } catch (e) { toast(e.message, true); }
    });
    $('#btn-save-estate').addEventListener('click', async () => {
      const wallet = {};
      $$('[data-wallet]').forEach((inp) => { wallet[inp.dataset.wallet] = Math.max(0, parseInt(inp.value, 10) || 0); });
      try {
        const res = await apiPost('/api/save/estate', {
          slot: state.activeSlot,
          estate_name: $('#ed-estate-name').value.trim(),
          endless_wave_highscore: Math.max(0, parseInt($('#ed-endless-hs').value, 10) || 0),
          wallet,
        });
        toast(res.message);
        await refreshAll(true);
      } catch (e) { toast(e.message, true); }
    });

    // Heroes actions
    $('#ed-hero-search').addEventListener('input', (e) => { state.heroSearch = e.target.value; renderHeroesSubtab(); });
    $('#btn-batch-heal').addEventListener('click', async () => {
      try {
        const res = await apiPost('/api/save/heroes/batch', { slot: state.activeSlot, heal_and_purify: true, max_level_and_gear: false });
        toast(res.message);
        await refreshAll(true);
      } catch (e) { toast(e.message, true); }
    });
    $('#btn-batch-max').addEventListener('click', async () => {
      try {
        const res = await apiPost('/api/save/heroes/batch', { slot: state.activeSlot, heal_and_purify: false, max_level_and_gear: true });
        toast(res.message);
        await refreshAll(true);
      } catch (e) { toast(e.message, true); }
    });
    $('#btn-hero-save').addEventListener('click', async () => {
      const d = state.editingHero;
      if (!d) return;
      try {
        const res = await apiPost('/api/save/hero/update', {
          slot: state.activeSlot,
          guid: d.guid,
          name: d.name,
          hp_fraction: d.hpPct / 100.0,
          stress: d.stress,
          resolve_xp: d.xp,
          weapon_rank: d.weapon,
          armour_rank: d.armour,
          virtue: d.virtue,
          affliction: d.affliction,
          revive: d.revive,
        });
        $('#dlg-hero').close();
        toast(res.message);
        await refreshAll(true);
      } catch (e) { toast(e.message, true); }
    });
    $('#btn-hero-to-quirks').addEventListener('click', () => {
      const guid = state.editingHero?.guid;
      $('#dlg-hero').close();
      if (guid) openQuirksDialog(guid);
    });
    $('#btn-quirks-to-hero').addEventListener('click', () => {
      const guid = state.editingHero?.guid;
      $('#dlg-quirks').close();
      if (guid) openHeroDialog(guid);
    });
    $('#btn-quirks-save').addEventListener('click', async () => {
      const h = state.editingHero;
      if (!h) return;
      try {
        const res = await apiPost('/api/save/hero/update', {
          slot: state.activeSlot,
          guid: h.guid,
          quirks: state.draftQuirks.map((q) => ({ id: q.id, locked: q.locked })),
        });
        $('#dlg-quirks').close();
        toast(res.message);
        await refreshAll(true);
      } catch (e) { toast(e.message, true); }
    });

    // Trinkets actions
    $('#ed-trinket-search').addEventListener('input', (e) => { state.trinketSearch = e.target.value; renderTrinketsSubtab(); });
    $('#btn-open-codex').addEventListener('click', () => {
      renderCodexDialog();
      $('#dlg-codex').showModal();
    });

    // Buildings & Dungeons
    $('#btn-unlock-buildings').addEventListener('click', async () => {
      try {
        const res = await apiPost('/api/save/buildings/unlock', { slot: state.activeSlot });
        toast(res.message);
        await refreshAll(true);
      } catch (e) { toast(e.message, true); }
    });
    $('#btn-max-dungeons').addEventListener('click', () => {
      $$('[data-dungeon]').forEach((inp) => { inp.value = '150'; });
    });
    $('#btn-save-dungeons').addEventListener('click', async () => {
      const dungeons = {};
      $$('[data-dungeon]').forEach((inp) => { dungeons[inp.dataset.dungeon] = Math.max(0, parseInt(inp.value, 10) || 0); });
      try {
        const res = await apiPost('/api/save/dungeons/update', { slot: state.activeSlot, dungeons });
        toast(res.message);
        await refreshAll(true);
      } catch (e) { toast(e.message, true); }
    });

    // Raw DSON actions
    $('#ed-raw-search').addEventListener('input', (e) => { state.rawSearch = e.target.value; renderRawSubtab(); });
    $('#btn-raw-save').addEventListener('click', async () => {
      const r = state.editingRaw;
      if (!r) return;
      try {
        const res = await apiPost('/api/save/raw/edit', {
          slot: state.activeSlot,
          file: state.dsonFile,
          path: [...state.rawPath, r.key],
          type: r.draftType,
          value: String(r.draftVal),
        });
        $('#dlg-raw').close();
        toast(res.message);
        await loadRawPath();
        await refreshAll(true);
      } catch (e) { toast(e.message, true); }
    });

    // Rules Modifier actions
    $$('[data-preset]').forEach((b) => b.addEventListener('click', () => {
      if (!state.rulesPresets) return;
      state.rulesConfig = { ...state.rulesPresets[b.dataset.preset] };
      renderRulesView();
      toast(`已套用预设：${b.textContent}（点击下方「生成并安装」生效）`);
    }));
    $$('.seg-group[data-rule]').forEach((grp) => {
      const key = grp.dataset.rule;
      const isFloat = grp.dataset.type === 'float';
      grp.querySelectorAll('button').forEach((btn) => btn.addEventListener('click', () => {
        if (!state.rulesConfig) return;
        state.rulesConfig[key] = isFloat ? parseFloat(btn.dataset.val) : parseInt(btn.dataset.val, 10);
        renderRulesView();
      }));
    });
    $$('[data-rule-check]').forEach((cb) => cb.addEventListener('change', () => {
      if (state.rulesConfig) state.rulesConfig[cb.dataset.ruleCheck] = cb.checked;
    }));
    $('[data-rule-hit100]').addEventListener('change', (e) => {
      if (state.rulesConfig) state.rulesConfig.always_hit_cap = e.target.checked ? 1.0 : 0.95;
    });

    function collectRuleInputs() {
      return {
        ...state.rulesConfig,
        hero_hp_multiplier: parseFloat($('#rule-hero-hp').value) || 1.0,
        hero_dmg_multiplier: parseFloat($('#rule-hero-dmg').value) || 1.0,
        hero_speed_bonus: parseInt($('#rule-hero-spd').value, 10) || 0,
        hero_dodge_bonus: parseInt($('#rule-hero-dodge').value, 10) || 0,
        building_cost_discount: parseInt($('#rule-bld-discount').value, 10) || 0,
      };
    }

    $('#btn-apply-rules').addEventListener('click', async () => {
      try {
        const cfg = collectRuleInputs();
        const res = await apiPost('/api/rules/apply', { config: cfg, enable_slot: $('#rule-enable-slot').value });
        toast(res.message);
        await refreshAll(true);
      } catch (e) { toast(e.message, true); }
    });
    $('#btn-uninstall-rules').addEventListener('click', async () => {
      if (!confirm('确认卸载 torch_content_modifier 并从所有档位取消订阅？')) return;
      try {
        const res = await apiPost('/api/rules/uninstall');
        toast(res.message);
        await refreshAll(true);
      } catch (e) { toast(e.message, true); }
    });
    $('#btn-export-rules-zip').addEventListener('click', async () => {
      try {
        const res = await fetch('/api/rules/export-zip', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Torch-Token': TOKEN },
          body: JSON.stringify({ config: collectRuleInputs() }),
        });
        if (!res.ok) throw new Error('导出 ZIP 失败');
        const blob = await res.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'torch_content_modifier.zip';
        a.click();
        URL.revokeObjectURL(url);
        toast('已导出 torch_content_modifier.zip');
      } catch (e) { toast(e.message, true); }
    });

    // Mod manager actions
    $('#mod-slot-select').addEventListener('change', (e) => { state.modSlot = e.target.value; loadMods(); });
    $('#mod-search').addEventListener('input', (e) => { state.modSearch = e.target.value; renderModsView(); });
    $('#btn-save-mods').addEventListener('click', async () => {
      if (!state.modSlot) return;
      try {
        const mods = state.modView.active.map((m) => ({ name: m.name, source: m.source }));
        const res = await apiPost('/api/mods/save', { slot: state.modSlot, mods });
        toast(res.message);
        await refreshAll(true);
      } catch (e) { toast(e.message, true); }
    });
  }

  // Light background heartbeat to keep watcher count & Darkest.exe status fresh
  setInterval(async () => {
    try {
      const app = await apiGet('/api/state');
      const prevCount = state.app?.snapshot_count;
      state.app = app;
      renderChrome();
      if (state.activeView === 'saves' && prevCount !== app.snapshot_count) {
        await loadSnapshots();
      }
    } catch { /* ignore transient poll errors */ }
  }, 5000);

  setupDialogFallbacks();
  bindEvents();
  refreshAll(true);
})();
