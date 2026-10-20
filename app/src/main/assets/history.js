// Riwayat baca ManhwaUS.
// - Halaman baca (/webtoon/judul/chapter-N/): catat judul + chapter + halaman terakhir, lanjutkan dari situ.
// - Halaman detail (/webtoon/judul/): simpan judul & sampul, tandai chapter yang sudah dibaca, tombol "Lanjut baca".
// Data disimpan di aplikasi lewat window.MFHistory (get/set JSON).
(function () {
  if (window.__mfHist || !window.MFHistory) return;
  window.__mfHist = true;

  var MAX_MANGA = 200;
  var path = location.pathname;

  function load() {
    try { return JSON.parse(window.MFHistory.get() || '{}') || {}; } catch (e) { return {}; }
  }
  function save(h) {
    var keys = Object.keys(h).filter(function (k) { return k !== '__meta'; });
    if (keys.length > MAX_MANGA) {
      keys.sort(function (a, b) { return (h[a].at || 0) - (h[b].at || 0); });
      for (var i = 0; i < keys.length - MAX_MANGA; i++) delete h[keys[i]];
    }
    if (h.__meta) {
      var mk = Object.keys(h.__meta);
      if (mk.length > 300) {
        mk.sort(function (a, b) { return (h.__meta[a].at || 0) - (h.__meta[b].at || 0); });
        for (var j = 0; j < mk.length - 300; j++) delete h.__meta[mk[j]];
      }
    }
    try { window.MFHistory.set(JSON.stringify(h)); } catch (e) {}
  }
  function abs(u) {
    if (!u) return '';
    u = u.trim();
    if (u.indexOf('//') === 0) return 'https:' + u;
    if (u.charAt(0) === '/') return 'https://manhwaus.net' + u;
    return u;
  }
  // "chapter-12" -> "12", "chapter-10-5" -> "10.5"
  function chNum(u) {
    var m = /chapter-([\d]+(?:-[\d]+)?)/.exec(u || '');
    return m ? m[1].replace('-', '.') : null;
  }
  function toast(text) {
    var el = document.createElement('div');
    el.textContent = text;
    el.style.cssText = 'position:fixed;left:50%;bottom:70px;transform:translateX(-50%);z-index:2147483647;' +
      'background:rgba(0,0,0,.78);color:#fff;font:600 14px sans-serif;padding:9px 16px;border-radius:20px;' +
      'pointer-events:none;transition:opacity .4s;white-space:nowrap';
    document.body.appendChild(el);
    setTimeout(function () { el.style.opacity = '0'; }, 2500);
    setTimeout(function () { el.remove(); }, 3000);
  }

  // ---------- Halaman baca ----------
  var rm = /^\/webtoon\/([^\/]+)\/(chapter-[^\/]+)\/?$/.exec(path);
  if (rm && document.querySelector('.read-content')) {
    var slug = rm[1], ch = chNum(rm[2]);
    if (!ch) return;
    var h = load();
    var m = h[slug] || (h[slug] = { ch: {} });
    m.ch = m.ch || {};
    var meta = (h.__meta || {})[slug] || {};
    var crumbs = document.querySelectorAll('.breadcrumb li a');
    var pageTitle = crumbs[1] ? crumbs[1].textContent.trim() : '';
    m.t = meta.t || pageTitle || m.t || slug;
    if (!m.c) m.c = meta.c || ('https://manhwaus.net/thumbnail/' + slug + '-thumbnail.jpg');
    var label = 'Chapter ' + ch;
    var prev = m.ch[ch];
    var chUrl = 'https://manhwaus.net/webtoon/' + slug + '/' + rm[2] + '/';
    m.ch[ch] = { p: prev ? prev.p : 0, n: prev ? prev.n : 0, done: prev ? prev.done : false, at: Date.now(), u: chUrl, l: label };
    m.last = ch;
    m.newCh = 0; m.chkAt = 0;
    m.lastLabel = label;
    m.lastUrl = chUrl;
    m.at = Date.now();
    save(h);

    var pages = function () { return document.querySelectorAll('.read-content img'); };
    var current = function () {
      var imgs = pages();
      for (var i = 0; i < imgs.length; i++) {
        if (imgs[i].getBoundingClientRect().bottom > 60) return i;
      }
      return imgs.length - 1;
    };
    var saveTimer = null;
    var record = function () {
      var imgs = pages();
      if (!imgs.length) return;
      var hh = load(), mm = hh[slug];
      if (!mm || !mm.ch || !mm.ch[ch]) return;
      var e = mm.ch[ch], idx = current();
      var docH = document.documentElement.scrollHeight;
      var atEnd = docH > window.innerHeight * 2 && window.innerHeight + window.scrollY >= docH - 500;
      e.p = idx; e.n = imgs.length; e.at = Date.now();
      if (atEnd || (imgs.length > 1 && idx >= imgs.length - 1)) e.done = true;
      mm.at = Date.now();
      save(hh);
    };
    window.addEventListener('scroll', function () {
      clearTimeout(saveTimer);
      saveTimer = setTimeout(record, 700);
    }, { passive: true });
    document.addEventListener('visibilitychange', function () { if (document.hidden) record(); });
    window.addEventListener('pagehide', record);
    window.addEventListener('beforeunload', record);
    setTimeout(record, 1500);

    // Lanjutkan dari halaman terakhir kalau chapter ini belum selesai dibaca
    if (prev && !prev.done && prev.p > 0) {
      var jump = function () {
        var imgs = pages();
        if (imgs.length <= prev.p) return false;
        imgs[prev.p].scrollIntoView();
        window.scrollBy(0, -56);
        return true;
      };
      setTimeout(function () {
        if (window.scrollY > 200 || !jump()) return;
        toast('Lanjut dari halaman ' + (prev.p + 1));
        var y = window.scrollY;
        // Ulangi sekali setelah gambar di atasnya selesai dimuat (posisi bisa bergeser)
        setTimeout(function () { if (Math.abs(window.scrollY - y) < 30) jump(); }, 1500);
        setTimeout(function () { if (Math.abs(window.scrollY - y) < 30) jump(); }, 3500);
      }, 600);
    }
    return;
  }

  // ---------- Halaman detail ----------
  var dm = /^\/webtoon\/([^\/]+)\/?$/.exec(path);
  if (dm && document.querySelector('.profile-manga')) {
    var s = dm[1];
    var hist = load();
    var titleEl = document.querySelector('.post-title h1');
    var cover = document.querySelector('.profile-manga .summary_image img');
    var t = titleEl ? titleEl.textContent.trim() : '';
    var c = cover ? abs(cover.getAttribute('data-src') || cover.getAttribute('src')) : '';
    hist.__meta = hist.__meta || {};
    hist.__meta[s] = { t: t, c: c, at: Date.now() };
    var info = hist[s];
    if (info) { if (t) info.t = t; if (c) { info.c = c; info.cAt = Date.now(); } }
    save(hist);
    if (!info || !info.ch) return; // belum pernah dibaca

    // Tandai chapter yang sudah dibaca
    var links = document.querySelectorAll('.row-content-chapter a.chapter-name');
    for (var i = 0; i < links.length; i++) {
      var n = chNum(links[i].getAttribute('href'));
      if (!n || !info.ch[n]) continue;
      links[i].classList.add('mf-read');
      if (n === info.last) links[i].classList.add('mf-last');
    }
    // Tombol "Lanjut baca"
    if (info.lastUrl && !document.querySelector('.mf-continue')) {
      var e2 = info.ch[info.last] || {};
      var a = document.createElement('a');
      a.className = 'mf-continue';
      a.href = info.lastUrl;
      a.textContent = 'Lanjut baca: Chapter ' + info.last +
        (e2.done ? ' (selesai)' : (e2.p ? ' • hal. ' + (e2.p + 1) : ''));
      var host = document.querySelector('.mf-below') || document.querySelector('.profile-manga');
      var le = host.querySelector('.lastend');
      if (le && le.nextSibling) host.insertBefore(a, le.nextSibling); else host.appendChild(a);
    }
  }
})();
