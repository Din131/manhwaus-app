// Penyesuaian halaman ManhwaUS di aplikasi: header, susunan detail, pembaca chapter,
// teks Indonesia, dan penyaring iklan. Gaya (CSS) ada di theme.css.
// Dijalankan saat halaman mulai tampil dan sekali lagi setelah selesai dimuat.
(function () {
  if (document.documentElement.classList.contains('mf-app')) return;
  var path = location.pathname;
  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return [].slice.call((r || document).querySelectorAll(s)); };
  var setText = function (el, t) { if (el && el.textContent.trim() !== t) el.textContent = t; };

  var isHome = path === '/' || path === '';
  var chapterM = /^\/webtoon\/([^\/]+)\/(chapter-[^\/]+)\/?$/.exec(path);
  var detailM = !chapterM && /^\/webtoon\/([^\/]+)\/?$/.exec(path);
  var isList = /^\/(webtoons|genre|completed|raw)(\/|$)/.test(path);
  var isSearch = /^\/search\/?/.test(path);
  var isBookmark = /^\/bookmark\/?/.test(path);
  var isLogin = /^\/login\/?$/.test(path), isRegister = /^\/register\/?$/.test(path);

  // ---------- Penyaring iklan & banner judi ----------
  if (!window.__muAds) {
    window.__muAds = true;
    var base = function (h) { return (h || '').toLowerCase().split('.').slice(-2).join('.'); };
    var SAFE = /(^|\.)(discord\.gg|discord\.com|facebook\.com|instagram\.com|x\.com|twitter\.com|t\.me|tiktok\.com|youtube\.com)$/i;
    var GAMBLE = /slot|gacor|judi|togel|casino|kasino|maxwin|scatter|sbobet|pragmatic|jackpot|\d{2,3}(bet|win)/i;
    var hideBox = function (el) {
      var t = el;
      while (t.parentElement && t.parentElement !== document.body && t.parentElement.children.length === 1) t = t.parentElement;
      t.style.setProperty('display', 'none', 'important');
    };
    var scan = function () {
      $$('a[href]').forEach(function (a) {
        if (a.__muChecked) return;
        a.__muChecked = true;
        var host = '';
        try { host = new URL(a.href, location.href).hostname; } catch (e) {}
        if (!host || base(host) === 'manhwaus.net' || SAFE.test(host)) return;
        var img = a.querySelector('img, video');
        if (img || GAMBLE.test(a.href + ' ' + a.textContent)) hideBox(a);
      });
    };
    scan();
    var waiting = false;
    new MutationObserver(function () {
      if (waiting) return;
      waiting = true;
      setTimeout(function () { waiting = false; scan(); }, 250);
    }).observe(document.documentElement, { childList: true, subtree: true });
  }

  if (!document.body) return;

  // ---------- Header aplikasi ----------
  var addHeader = function (title, withBack) {
    var hd = document.getElementById('mf-header');
    if (!hd) {
      hd = document.createElement('div');
      hd.id = 'mf-header';
      hd.className = 'mf-header' + (withBack ? '' : ' noback');
      if (withBack) {
        var bk = document.createElement('button');
        bk.setAttribute('aria-label', 'Kembali');
        bk.textContent = '←';
        bk.onclick = function () { history.length > 1 ? history.back() : (location.href = '/'); };
        hd.appendChild(bk);
      }
      hd.appendChild(document.createElement('h1'));
      document.body.insertBefore(hd, document.body.firstChild);
    }
    setText(hd.querySelector('h1'), title);
  };
  var LIST_NAMES = { 'All Manhwa': 'Semua Manhwa', 'Completed': 'Tamat', 'Raw': 'RAW' };
  if (isHome) addHeader('Beranda', false);
  else if (isBookmark) addHeader('Bookmark', false);
  else if (isLogin) addHeader('Masuk', true);
  else if (isRegister) addHeader('Daftar Akun', true);
  else if (isSearch) {
    var q = new URLSearchParams(location.search).get('s') || '';
    addHeader(q ? 'Cari: ' + q : 'Cari', true);
  } else if (isList) {
    var h1 = $('.block-heading h1');
    var name = h1 ? h1.textContent.replace(/\s+/g, ' ').trim() : 'Daftar';
    name = name.replace(/\s+Manhwa$/i, '') === name ? name : name;
    addHeader(LIST_NAMES[name] || name, true);
  } else if (detailM && $('.profile-manga')) {
    var t = $('.post-title h1');
    addHeader(t ? t.textContent.trim() : 'Detail', true);
  }

  // ---------- Teks Indonesia ----------
  var SORT = { 'Latest': 'Terbaru', 'A-Z': 'A–Z', 'Rating': 'Rating', 'Trending': 'Populer' };
  $$('.c-tabs-content li a').forEach(function (a) {
    var k = a.textContent.trim();
    if (SORT[k]) setText(a, SORT[k]);
    // Tandai urutan yang sedang dipakai
    var o = new URLSearchParams(location.search).get('orderby') || 'latest';
    if ((a.getAttribute('href') || '').indexOf('orderby=' + o) >= 0) a.classList.add('active');
  });
  $$('.section-heading h2, .section-heading h4, .block-heading h2, .block-heading h4, .block-heading h1').forEach(function (h) {
    var tx = h.textContent.replace(/\s+/g, ' ').trim();
    var map = { 'popular': 'Populer', 'latest webtoon updated': 'Update terbaru', 'list chapters releases': 'Daftar chapter',
                'you may also like': 'Mungkin kamu suka', 'all manhwa': 'Semua manhwa', 'discussion': 'Diskusi' };
    if (map[tx.toLowerCase()]) setText(h, map[tx.toLowerCase()]);
    var rf = /^RESULTS FOR "(.*)"$/i.exec(tx);
    if (rf) setText(h, 'Hasil untuk "' + rf[1] + '"');
  });
  $$('.blog-pager li a, .blog-pager li span').forEach(function (a) {
    if (/PREV/i.test(a.textContent)) setText(a, '‹ Sebelumnya');
    else if (/NEXT/i.test(a.textContent)) setText(a, 'Berikutnya ›');
  });
  $$('.manga-listing-item > p').forEach(function (p) { if (/^No result/i.test(p.textContent.trim())) setText(p, 'Tidak ada hasil.'); });

  // ---------- Detail manhwa ----------
  if (detailM && $('.profile-manga') && !window.__muDetail) {
    window.__muDetail = true;
    var wrap = $('.profile-manga .summary_content');
    // Status & tipe dari bawah sampul -> label kecil di kolom kanan
    var minfo = $$('.profile-manga .summary_image .minfo');
    if (wrap && minfo.length) {
      var box = document.createElement('div');
      box.className = 'mf-minfo';
      minfo.forEach(function (m) {
        var v = m.querySelector('i');
        var label = v ? v.textContent.trim() : '';
        if (!label) return;
        var tr = { 'Ongoing': 'Berlanjut', 'Completed': 'Tamat' };
        var s = document.createElement('span');
        s.textContent = tr[label] || label;
        box.appendChild(s);
      });
      var rating = $('.post-rating', wrap);
      wrap.insertBefore(box, rating ? rating.nextSibling : wrap.firstChild);
    }
    var vd = $('.vote-details');
    if (vd) {
      var cnt = $('#countrate');
      setText(vd, cnt ? '(' + cnt.textContent.trim() + ' suara)' : '');
    }
    var thx = $('.is_rate');
    if (thx) thx.textContent = 'Terima kasih atas nilaimu!';
    // Sinopsis & genre selebar kartu, di bawah sampul
    var below = document.createElement('div');
    below.className = 'mf-below';
    $$('.profile-manga .summary_content .post-content_item').forEach(function (it) {
      var b = it.querySelector('b');
      if (it.querySelector('.entry-content')) {
        var h2 = it.querySelector('h2');
        if (h2) h2.textContent = 'Sinopsis';
        below.appendChild(it);
      } else if (b && /Genres/i.test(b.textContent)) {
        it.classList.add('mf-genres');
        below.appendChild(it);
      } else if (b && /Alternative/i.test(b.textContent)) {
        b.textContent = 'Judul lain:';
      }
    });
    // Tombol chapter pertama / terbaru
    var le = $('.lastend');
    if (le) {
      $$('.lastend .inepcx span').forEach(function (s) {
        if (/First/i.test(s.textContent)) s.textContent = 'Mulai baca';
        else if (/Latest/i.test(s.textContent)) s.textContent = 'Chapter terbaru';
      });
      below.insertBefore(le, below.firstChild);
    }
    $('.profile-manga').appendChild(below);
    // Bookmark
    $$('.profile-manga .book-mark').forEach(function (b) {
      var ic = b.querySelector('i');
      var label = b.classList.contains('ybk') ? ' Diikuti' : ' Bookmark';
      b.textContent = '';
      if (ic) b.appendChild(ic);
      b.appendChild(document.createTextNode(label));
    });
    $$('.profile-manga .b_count').forEach(function (c) {
      var n = /(\d+)/.exec(c.textContent);
      if (n) c.textContent = n[1] + ' pengguna mem-bookmark';
    });
    var more = $('.more__chapters span');
    if (more) more.textContent = 'Tampilkan semua chapter';
  }

  // ---------- Masuk & Daftar ----------
  if ((isLogin || isRegister) && $('.signup-form') && !window.__muAuth) {
    window.__muAuth = true;
    var form = $('.signup-form');
    var av = document.createElement('div');
    av.className = 'mf-auth-avatar';
    av.innerHTML = '<svg viewBox="0 0 24 24"><path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/></svg>';
    var tt = document.createElement('div');
    tt.className = 'mf-auth-title';
    tt.textContent = isLogin ? 'Akun ManhwaUS' : 'Buat akun baru';
    var sub = document.createElement('p');
    sub.className = 'mf-auth-sub';
    sub.textContent = isLogin ? 'Masuk untuk menyimpan bookmark' : 'Gratis, cukup beberapa data saja';
    form.insertBefore(sub, form.firstChild);
    form.insertBefore(tt, form.firstChild);
    form.insertBefore(av, form.firstChild);
    var PH = { 'User name': 'Nama pengguna', 'Password': 'Kata sandi', 'Display name': 'Nama tampilan',
               'Email to recover Password': 'Email (untuk lupa kata sandi)', 'Email': 'Email' };
    $$('input[placeholder]', form).forEach(function (i) { var p = i.getAttribute('placeholder'); if (PH[p]) i.setAttribute('placeholder', PH[p]); });
    $$('input[type=submit]', form).forEach(function (s) { s.value = isLogin ? 'Masuk' : 'Daftar'; });
    $$('a', form).forEach(function (a) { if (/^\s*Login\s*$/i.test(a.textContent)) a.style.display = 'none'; });
    var alt = document.createElement('a');
    alt.className = 'mf-auth-alt';
    alt.href = isLogin ? '/register/' : '/login/';
    alt.textContent = isLogin ? 'Daftar akun baru' : 'Sudah punya akun? Masuk';
    form.appendChild(alt);
  }

  // ---------- Pembaca chapter ----------
  if (chapterM && $('.read-content') && !window.__muReader) {
    window.__muReader = true;
    document.body.classList.add('mf-reader');
    var slug = chapterM[1];
    var bc = $$('.breadcrumb li a');
    var mangaTitle = bc[1] ? bc[1].textContent.trim() : slug.replace(/-/g, ' ');
    var chLabel = bc[2] ? bc[2].textContent.trim() : chapterM[2].replace(/-/g, ' ');
    var prevA = $('.navi-change-chapter-btn-prev'), nextA = $('.navi-change-chapter-btn-next');
    // Bilah atas: kembali ke detail, judul, chapter
    var top = document.createElement('div');
    top.className = 'mf-rbar top';
    top.innerHTML = '<a class="back" aria-label="Kembali">←</a><div class="t"><b></b><span></span></div>';
    top.querySelector('.back').href = '/webtoon/' + slug + '/';
    top.querySelector('b').textContent = mangaTitle;
    top.querySelector('span').textContent = chLabel.replace(/^chapter/i, 'Chapter');
    document.body.appendChild(top);
    // Bilah bawah: chapter sebelumnya, penanda halaman, chapter berikutnya
    var bot = document.createElement('div');
    bot.className = 'mf-rbar bot';
    bot.innerHTML = '<a class="prev">‹ Seb.</a><span class="ind"></span><a class="next">Ber. ›</a>';
    var bp = bot.querySelector('.prev'), bn = bot.querySelector('.next');
    if (prevA) bp.href = prevA.href; else bp.classList.add('off');
    if (nextA) bn.href = nextA.href; else bn.classList.add('off');
    document.body.appendChild(bot);
    // Navigasi atas situs disembunyikan; yang di bawah chapter dirapikan
    var cc = $$('.chapchange');
    if (cc.length > 1) cc[0].style.setProperty('display', 'none', 'important');
    $$('.navi-change-chapter-btn a').forEach(function (a) {
      if (a.classList.contains('navi-change-chapter-btn-prev')) a.textContent = '‹ Sebelumnya';
      else if (a.classList.contains('navi-change-chapter-btn-next')) a.textContent = 'Berikutnya ›';
    });

    var pages = function () { return $$('.read-content img'); };
    var ind = bot.querySelector('.ind');
    var updateInd = function () {
      var ps = pages();
      if (!ps.length) { ind.textContent = ''; return; }
      var mid = window.innerHeight / 2, cur = 1;
      for (var i = 0; i < ps.length; i++) {
        var r = (ps[i].__mfBox || ps[i]).getBoundingClientRect();
        if (r.top <= mid) cur = i + 1; else break;
      }
      ind.textContent = cur + ' / ' + ps.length;
    };

    // Gambar gagal dimuat: coba ulang otomatis 2 kali, lalu tombol "Muat ulang gambar"
    var clearBox = function (img) {
      if (!img.__mfBox) return;
      if (img.__mfBox.parentNode) img.__mfBox.parentNode.removeChild(img.__mfBox);
      img.__mfBox = null;
      img.style.removeProperty('display');
    };
    var showRetry = function (img, again) {
      var box = img.__mfBox;
      if (!box) {
        box = document.createElement('div');
        box.className = 'mf-retry';
        box.innerHTML = '<div></div><button>Muat ulang gambar</button>';
        img.parentNode.insertBefore(box, img);
        img.__mfBox = box;
        img.style.setProperty('display', 'none', 'important');
        box.lastChild.onclick = function () {
          box.firstChild.textContent = 'Memuat…';
          box.lastChild.style.display = 'none';
          tryLoad(img, 3);
        };
      }
      box.firstChild.textContent = again ? 'Masih gagal. Periksa koneksi lalu coba lagi.' : 'Halaman ini gagal dimuat.';
      box.lastChild.style.display = '';
    };
    var tryLoad = function (img, attempt) {
      var url = img.getAttribute('data-src') || img.getAttribute('src');
      if (!url) return;
      var probe = new Image();
      probe.onload = function () { img.removeAttribute('src'); img.setAttribute('src', url); clearBox(img); };
      probe.onerror = function () {
        if (attempt < 2) setTimeout(function () { tryLoad(img, attempt + 1); }, 1500);
        else showRetry(img, attempt > 2);
      };
      probe.src = url + (url.indexOf('?') < 0 ? '?' : '&') + 'r=' + attempt;
    };
    var watchImg = function (img) {
      if (img.__mfWatch) return;
      img.__mfWatch = true;
      var ok = function () { img.setAttribute('data-mf-ok', ''); };
      if (img.complete && img.naturalWidth) ok();
      img.addEventListener('load', ok);
      img.addEventListener('error', function () { if (!img.__mfBox) tryLoad(img, 1); });
      if (img.complete && !img.naturalWidth && img.getAttribute('src')) tryLoad(img, 1);
    };
    pages().forEach(watchImg);

    // Lanjut otomatis ke chapter berikutnya di akhir chapter
    var card = null, timer = null, dismissed = false, SECS = 4;
    var stopCountdown = function () {
      if (timer) { clearInterval(timer); timer = null; }
      if (card) card.classList.remove('show');
    };
    var startCountdown = function () {
      if (!nextA || dismissed || timer) return;
      if (!card) {
        card = document.createElement('div');
        card.className = 'mf-next';
        card.innerHTML = '<div class="txt"><b></b><span></span></div><button class="no">Batal</button><button class="go">Baca</button><div class="bar"></div>';
        var m = /chapter-([\d-]+)/.exec(nextA.getAttribute('href') || '');
        card.querySelector('b').textContent = m ? 'Chapter ' + m[1].replace(/-/g, '.') : 'Chapter berikutnya';
        card.querySelector('.no').onclick = function () { dismissed = true; stopCountdown(); };
        card.querySelector('.go').onclick = function () { stopCountdown(); location.href = nextA.href; };
        document.body.appendChild(card);
      }
      var left = SECS, bar = card.querySelector('.bar'), sp = card.querySelector('span');
      sp.textContent = 'Lanjut otomatis dalam ' + left + ' detik';
      bar.style.transition = 'none'; bar.style.width = '0';
      void card.offsetWidth;
      card.classList.add('show');
      bar.style.transition = 'width ' + SECS + 's linear'; bar.style.width = '100%';
      timer = setInterval(function () {
        left--;
        if (left <= 0) { stopCountdown(); location.href = nextA.href; return; }
        sp.textContent = 'Lanjut otomatis dalam ' + left + ' detik';
      }, 1000);
    };
    var atEnd = function () {
      var docH = document.documentElement.scrollHeight;
      if (docH < window.innerHeight * 2) return false;
      var ps = pages();
      if (!ps.length) return false;
      var last = ps[ps.length - 1];
      if (!last.complete || !last.naturalWidth) return false;
      return window.innerHeight + window.scrollY >= docH - 40;
    };
    var rTimer = null;
    window.addEventListener('scroll', function () {
      if (rTimer) return;
      rTimer = setTimeout(function () {
        rTimer = null; updateInd();
        if (atEnd()) startCountdown(); else if (timer) stopCountdown();
      }, 120);
    }, { passive: true });
    setTimeout(updateInd, 200);
    setInterval(function () { pages().forEach(watchImg); updateInd(); }, 1500);
  }

  // ---------- Zoom dua jari hanya di halaman baca ----------
  var want = chapterM
    ? 'width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=5, user-scalable=yes'
    : 'width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no';
  var vp = $('meta[name=viewport]');
  if (!vp) { vp = document.createElement('meta'); vp.name = 'viewport'; (document.head || document.documentElement).appendChild(vp); }
  if (vp.content !== want) vp.content = want;
})();
