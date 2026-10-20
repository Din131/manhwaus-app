package com.din131.manhwaus

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    companion object {
        /**
         * Alamat halaman Riwayat Baca. Isinya dari assets/history.html, tapi dibuka "atas nama"
         * manhwaus.net supaya halaman bisa mengambil data dan sampul dari situs
         * dan halaman bisa mengambil sampul dari situs. Alamat ini tidak pernah diminta ke server.
         */
        private const val HISTORY_URL = "https://manhwaus.net/__riwayat-baca__"

        /** Halaman Cari (Jelajah + Ranking) dari assets/search.html, dengan cara yang sama. */
        private const val SEARCH_URL = "https://manhwaus.net/__cari__"

        /** Halaman Akun (status login, Log Out, info aplikasi) dari assets/account.html. */
        private const val ACCOUNT_URL = "https://manhwaus.net/__akun__"

        /** Alamat Log Out situs (mengakhiri sesi, lalu dialihkan ke halaman login). */
        private const val LOGOUT_URL = "https://manhwaus.net/logout/"

        /** Domain yang boleh diambil lewat jembatan MFNet. */
        private val NET_HOSTS = setOf("manhwaus.net")
    }

    private lateinit var webView: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorView: TextView

    @Volatile private var keepSplash = true
    /** Dibaca dari thread jaringan WebView, jadi disimpan sekali di sini. */
    @Volatile private var userAgent = ""

    // Bilah navigasi bawah
    private lateinit var bottomNav: View
    private lateinit var bottomNavLine: View
    private lateinit var navSearch: View
    private lateinit var navHome: View
    private lateinit var navHistory: View
    private lateinit var navBookmark: View
    private lateinit var navAccount: View
    private var imeVisible = false
    private var onReaderPage = false
    private var historyJs = ""

    /** Tandai tab aktif dan sembunyikan bilah di halaman baca chapter / saat keyboard muncul. */
    private fun updateBottomNav(url: String? = webView.url) {
        val u = url ?: ""
        val path = Uri.parse(u).path ?: ""
        val isHistory = u.startsWith(HISTORY_URL)
        val isSearch = u.startsWith(SEARCH_URL)
        val isAccount = u.startsWith(ACCOUNT_URL)
        navAccount.isSelected = isAccount || (!isHistory && !isSearch && (path.startsWith("/login") || path.startsWith("/register")))
        onReaderPage = !isHistory && !isSearch && !isAccount && readerPath.containsMatchIn(path)
        navSearch.isSelected = isSearch || (!isHistory && path.startsWith("/search"))
        navHome.isSelected = !isHistory && !isSearch && (path == "" || path == "/" || path.startsWith("/index"))
        navHistory.isSelected = isHistory
        navBookmark.isSelected = !isHistory && path.startsWith("/bookmark")
        val show = !onReaderPage && !imeVisible
        val vis = if (show) View.VISIBLE else View.GONE
        if (bottomNav.visibility != vis) {
            bottomNav.visibility = vis
            bottomNavLine.visibility = vis
        }
    }

    private fun openSearch() {
        val html = assets.open("search.html").bufferedReader().use { it.readText() }
        webView.loadDataWithBaseURL(SEARCH_URL, html, "text/html", "utf-8", SEARCH_URL)
    }

    private val netPool = java.util.concurrent.Executors.newFixedThreadPool(4)

    /**
     * Mengambil halaman manhwaus.net di belakang layar untuk fitur Cari,
     * Ranking, rating, dan "chapter baru". Hasil dikirim balik ke window.__mfNet(id, ok, teks).
     */
    private inner class NetBridge {
        @JavascriptInterface
        fun fetch(url: String, id: String) {
            val host = try { Uri.parse(url).host?.lowercase() } catch (e: Exception) { null }
            if (host == null || host !in NET_HOSTS || !url.startsWith("https://")) {
                deliver(id, null); return
            }
            // Versi desktop dipakai untuk direktori & pencarian lanjutan (tidak dialihkan ke m.)
            val ua = userAgent
            netPool.execute { deliver(id, DohClient.getText(url, ua)) }
        }

        private fun deliver(id: String, text: String?) {
            val js = "window.__mfNet&&window.__mfNet(" + org.json.JSONObject.quote(id) + "," +
                (text != null) + "," + org.json.JSONObject.quote(text ?: "") + ")"
            runOnUiThread { if (!isDestroyed) webView.evaluateJavascript(js, null) }
        }
    }

    private fun openHistory() {
        val html = assets.open("history.html").bufferedReader().use { it.readText() }
        webView.loadDataWithBaseURL(HISTORY_URL, html, "text/html", "utf-8", HISTORY_URL)
    }

    private fun openAccount() {
        val html = assets.open("account.html").bufferedReader().use { it.readText() }
        webView.loadDataWithBaseURL(ACCOUNT_URL, html, "text/html", "utf-8", ACCOUNT_URL)
    }

    /** Sedang menunggu Log Out selesai; setelah itu kembali ke halaman Akun. */
    @Volatile private var pendingLogout = false
    /** Setelah Log Out, riwayat "kembali" dikosongkan supaya halaman Log Out tidak terbuka lagi. */
    private var clearHistoryAfterLoad = false

    private fun startLogout() {
        AlertDialog.Builder(this)
            .setTitle("Log Out")
            .setMessage("Keluar dari akun ManhwaUS?")
            .setPositiveButton("Log Out") { _, _ ->
                pendingLogout = true
                webView.loadUrl(LOGOUT_URL)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /**
     * Setelah /logout selesai dimuat: hapus semua cookie aplikasi (cookie login ManhwaUS ikut
     * terhapus walaupun situs tidak menghapusnya), lalu kembali ke halaman Akun.
     */
    private fun finishLogout() {
        if (!pendingLogout) return
        pendingLogout = false
        val cm = CookieManager.getInstance()
        cm.removeAllCookies {
            cm.flush()
            clearHistoryAfterLoad = true
            openAccount()
        }
    }

    /** Dipanggil dari halaman Akun. */
    private inner class AppBridge {
        @JavascriptInterface
        fun version(): String = try {
            "v" + packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "" }

        @JavascriptInterface
        fun checkUpdate() = runOnUiThread { UpdateChecker(this@MainActivity).checkNow() }

        @JavascriptInterface
        fun clearCache() = runOnUiThread {
            webView.clearCache(true)
            Toast.makeText(this@MainActivity, "Cache dibersihkan", Toast.LENGTH_SHORT).show()
        }

        @JavascriptInterface
        fun logout() = runOnUiThread { startLogout() }

        @JavascriptInterface
        fun getTheme(): String = themePref()

        @JavascriptInterface
        fun setTheme(mode: String) {
            if (mode !in setOf("system", "light", "dark")) return
            settings.edit().putString("theme", mode).apply()
            runOnUiThread { applyTheme() }
        }
    }

    // ---------- Tema (Sistem / Terang / Gelap) ----------

    private val settings by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val themeCss by lazy { assets.open("theme.css").bufferedReader().use { it.readText() } }
    private var themeScript: ScriptHandler? = null

    /** "system", "light", atau "dark" */
    private fun themePref(): String = settings.getString("theme", "system") ?: "system"

    private fun isDark(): Boolean = when (themePref()) {
        "dark" -> true
        "light" -> false
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** Skrip yang memasang/melepas gaya gelap di halaman (situs maupun halaman aplikasi). */
    private fun themeJs(dark: Boolean): String =
        "(function(){var go=function(){var d=document.documentElement;if(!d)return false;" +
            (if (dark) "d.classList.add('mf-dark');" else "d.classList.remove('mf-dark');") +
            "if(!document.getElementById('__mf_theme')){var s=document.createElement('style');s.id='__mf_theme';" +
            "s.textContent=" + org.json.JSONObject.quote(themeCss) + ";(document.head||d).appendChild(s);}return true;};" +
            // Saat dokumen baru dibuat, elemen <html> belum ada: tunggu sampai parser membuatnya
            "if(!go()){var mo=new MutationObserver(function(){if(go())mo.disconnect();});mo.observe(document,{childList:true,subtree:true});}})()"

    private fun applyTheme() {
        val dark = isDark()
        val bg = if (dark) 0xFF121216.toInt() else getColor(R.color.bg)
        val navBg = if (dark) 0xFF1C1C22.toInt() else 0xFFFFFFFF.toInt()
        val line = if (dark) 0xFF2C2C35.toInt() else 0xFFE6E6EA.toInt()
        findViewById<View>(R.id.root).setBackgroundColor(bg)
        webView.setBackgroundColor(bg)
        bottomNav.setBackgroundColor(navBg)
        bottomNavLine.setBackgroundColor(line)
        errorView.setBackgroundColor(bg)
        errorView.setTextColor(if (dark) 0xFFE8E8EE.toInt() else getColor(R.color.text))
        swipe.setProgressBackgroundColorSchemeColor(navBg)
        val navColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(if (dark) 0xFFFF6B7D.toInt() else 0xFFC9243A.toInt(), if (dark) 0xFF8A8A94.toInt() else 0xFF7A7A84.toInt())
        )
        for (item in listOf(navHome, navSearch, navHistory, navBookmark, navAccount)) {
            val group = item as LinearLayout
            for (i in 0 until group.childCount) {
                when (val c = group.getChildAt(i)) {
                    is ImageView -> c.imageTintList = navColors
                    is TextView -> c.setTextColor(navColors)
                }
            }
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        // Dipasang sebelum halaman mulai digambar, supaya tidak berkedip putih
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            themeScript?.remove()
            themeScript = WebViewCompat.addDocumentStartJavaScript(
                webView, themeJs(dark), setOf("https://manhwaus.net", "https://*.manhwaus.net"))
        }
        webView.evaluateJavascript(themeJs(dark), null)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (themePref() == "system") applyTheme()
    }

    private val historyPrefs by lazy { getSharedPreferences("history", MODE_PRIVATE) }

    /** Penyimpanan riwayat baca (JSON). Dipanggil dari JavaScript di thread terpisah. */
    private inner class HistoryBridge {
        @JavascriptInterface
        fun get(): String = historyPrefs.getString("data", "{}") ?: "{}"

        @JavascriptInterface
        fun set(json: String) {
            historyPrefs.edit().putString("data", json).apply()
        }
    }

    private var popupView: WebView? = null

    /** Domain yang boleh dibuka di halaman utama (beserta subdomainnya). */
    private val allowedDomains = mutableSetOf<String>()
    private var initialLoad = true
    private var lastBackPress = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash screen: tampil sampai halaman pertama siap (maksimal 4 detik)
        val splash = installSplashScreen()
        keepSplash = savedInstanceState == null
        splash.setKeepOnScreenCondition { keepSplash }
        splash.setOnExitAnimationListener { provider ->
            provider.view.animate().alpha(0f).setDuration(300L)
                .withEndAction { provider.remove() }.start()
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AdBlocker.init(this)
        DohClient.init(cacheDir)

        webView = findViewById(R.id.webView)
        swipe = findViewById(R.id.swipe)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.errorView)

        // Jarak untuk status bar, navigasi, notch, dan keyboard (Android 15+)
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            // Sembunyikan bilah bawah saat keyboard muncul, supaya ruang ketik lega
            val imeNow = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeNow != imeVisible) {
                imeVisible = imeNow
                v.post { updateBottomNav() }
            }
            WindowInsetsCompat.CONSUMED
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        val startUrl = getString(R.string.start_url)
        Uri.parse(startUrl).host?.let { allowedDomains.add(baseDomain(it)) }

        // Layar tidak mati selama membaca
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            // Zoom dengan dua jari untuk halaman komik
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = userAgentString.replace("; wv", "")
        }
        userAgent = webView.settings.userAgentString
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.setBackgroundColor(getColor(R.color.bg))
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        webView.setDownloadListener { _, _, _, _, _ -> /* abaikan unduhan (biasanya iklan) */ }
        // Riwayat baca: disimpan di aplikasi, diakses dari history.js dan history.html
        webView.addJavascriptInterface(HistoryBridge(), "MFHistory")
        webView.addJavascriptInterface(NetBridge(), "MFNet")
        webView.addJavascriptInterface(AppBridge(), "MFApp")
        historyJs = assets.open("history.js").bufferedReader().use { it.readText() }
        bottomNav = findViewById(R.id.bottomNav)
        bottomNavLine = findViewById(R.id.bottomNavLine)
        navSearch = findViewById(R.id.navSearch)
        navHome = findViewById(R.id.navHome)
        navHistory = findViewById(R.id.navHistory)
        navBookmark = findViewById(R.id.navBookmark)
        navAccount = findViewById(R.id.navAccount)
        navSearch.setOnClickListener { if (webView.url?.startsWith(SEARCH_URL) != true) openSearch() }
        navHome.setOnClickListener { webView.loadUrl(getString(R.string.start_url)) }
        navHistory.setOnClickListener { if (webView.url?.startsWith(HISTORY_URL) != true) openHistory() }
        navBookmark.setOnClickListener { webView.loadUrl("https://manhwaus.net/bookmark/") }
        navAccount.setOnClickListener { if (webView.url?.startsWith(ACCOUNT_URL) != true) openAccount() }
        applyTheme()

        // Tarik untuk refresh hanya saat halaman berada di paling atas
        swipe.setColorSchemeColors(getColor(R.color.accent))
        swipe.setProgressBackgroundColorSchemeColor(getColor(R.color.bg))
        swipe.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
        swipe.setOnRefreshListener {
            if (webView.url?.startsWith(HISTORY_URL) == true) {
                swipe.isRefreshing = false
                openHistory()
            } else if (webView.url?.startsWith(SEARCH_URL) == true) {
                swipe.isRefreshing = false
                openSearch()
            } else if (webView.url?.startsWith(ACCOUNT_URL) == true) {
                swipe.isRefreshing = false
                openAccount()
            } else {
                webView.reload()
            }
        }

        webView.postDelayed({ keepSplash = false }, 4000)

        errorView.setOnClickListener {
            errorView.visibility = View.GONE
            webView.reload()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    popupView != null -> popupView!!.let { if (it.canGoBack()) it.goBack() else closePopup() }
                    webView.canGoBack() -> webView.goBack()
                    System.currentTimeMillis() - lastBackPress < 2000 -> finish()
                    else -> {
                        lastBackPress = System.currentTimeMillis()
                        Toast.makeText(this@MainActivity, R.string.press_back_again, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
            initialLoad = false
        } else {
            webView.loadUrl(startUrl)
        }
        // Cek versi baru di GitHub Releases setiap aplikasi dibuka
        if (savedInstanceState == null) UpdateChecker(this).checkInBackground()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        // Simpan cookie login supaya tetap login setelah aplikasi ditutup
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    // ---------- Helper ----------

    /** "ii001.manhwaus.net" -> "manhwaus.net" */
    private fun baseDomain(host: String): String {
        val parts = host.lowercase().split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host.lowercase()
    }

    /** Halaman baca chapter: /webtoon/judul/chapter-12/ */
    private val readerPath = Regex("""^/webtoon/[^/]+/chapter-[^/]+/?$""")

    private fun isAllowed(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        return allowedDomains.any { h == it || h.endsWith(".$it") }
    }

    /** Halaman login & captcha pihak ketiga yang boleh dibuka. */
    private val authDomains = setOf(
        "accounts.google.com", "accounts.youtube.com", "www.google.com",
        "discord.com", "discordapp.com",
        "facebook.com", "x.com", "twitter.com", "appleid.apple.com",
        "challenges.cloudflare.com", "hcaptcha.com",
        // Firebase Authentication (dipakai login Google di banyak situs, misalnya Komiku)
        "firebaseapp.com", "web.app", "securetoken.googleapis.com", "identitytoolkit.googleapis.com"
    )

    private fun isAuth(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        return authDomains.any { h == it || h.endsWith(".$it") }
    }

    private fun blockedToast(host: String?) {
        if (host.isNullOrEmpty()) return
        Toast.makeText(this, getString(R.string.link_blocked, host), Toast.LENGTH_SHORT).show()
    }

    private val emptyResponse: WebResourceResponse
        get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** Penyesuaian halaman situs (header, detail, pembaca, teks Indonesia, iklan): assets/page.js */
    private val pageJs by lazy { assets.open("page.js").bufferedReader().use { it.readText() } }

    // ---------- WebViewClient: adblock + kunci domain ----------

    private inner class Client : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val host = request.url.host
            // Jangan blokir situs utama maupun layanan login
            if (!isAllowed(host) && !isAuth(host) && AdBlocker.isBlocked(host)) return emptyResponse
            // Halaman login pihak ketiga dibiarkan memakai jaringan biasa
            if (isAuth(host)) return null
            // Ambil lewat DNS-over-HTTPS supaya tidak terkena blokir DNS operator
            return DohClient.fetch(request, userAgent)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            val scheme = url.scheme?.lowercase()

            // intent://, market://, dll. biasanya redirect iklan ke aplikasi lain
            if (scheme != "http" && scheme != "https") {
                return scheme != "about" && scheme != "blob" && scheme != "data" && scheme != "javascript"
            }

            val host = url.host
            if (AdBlocker.isBlocked(host) && !isAllowed(host)) return true
            if (!request.isForMainFrame) return false

            if (isAllowed(host) || isAuth(host)) return false

            // Saat pertama buka, ikuti redirect domain (misalnya situs pindah domain)
            if (initialLoad && request.isRedirect && host != null) {
                allowedDomains.add(baseDomain(host))
                return false
            }

            blockedToast(host)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            errorView.visibility = View.GONE
            updateBottomNav(url)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            updateBottomNav(url)
        }

        // Jalankan penyaring sedini mungkin, saat halaman mulai tampil
        override fun onPageCommitVisible(view: WebView, url: String?) {
            view.evaluateJavascript(themeJs(isDark()), null)
            view.evaluateJavascript(pageJs, null)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            keepSplash = false
            initialLoad = false
            swipe.isRefreshing = false
            view.evaluateJavascript(themeJs(isDark()), null)
            view.evaluateJavascript(pageJs, null)
            if (clearHistoryAfterLoad && url?.startsWith(ACCOUNT_URL) == true) {
                clearHistoryAfterLoad = false
                view.clearHistory()
            }
            // Log Out selesai begitu halaman /logout (dan pengalihannya) selesai dimuat
            if (pendingLogout && url != null && !url.startsWith(ACCOUNT_URL)) {
                finishLogout()
                return
            }
            // Catat riwayat baca (hanya di halaman ManhwaUS)
            if (url != null && isAllowed(Uri.parse(url).host)) view.evaluateJavascript(historyJs, null)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                errorView.visibility = View.VISIBLE
                swipe.isRefreshing = false
            }
        }
    }

    // ---------- WebChromeClient: progress & pop-up ----------

    private inner class Chrome : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (newProgress >= 80) keepSplash = false
            progress.progress = newProgress
            progress.visibility = if (newProgress < 100 && !swipe.isRefreshing) View.VISIBLE else View.GONE
        }

        /**
         * Tab/jendela baru: tangkap URL tujuannya.
         * - Situs yang sama  -> buka di jendela utama
         * - Halaman login (Google, Discord, dll.) -> tampilkan sebagai jendela pop-up
         * - Domain lain (iklan) -> buang
         */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false
            val temp = WebView(this@MainActivity)
            setupWebView(temp)
            temp.webViewClient = object : WebViewClient() {
                private var decided = false

                /** true = batalkan pemuatan */
                private fun decide(url: Uri?): Boolean {
                    val scheme = url?.scheme?.lowercase()
                    if (url == null || scheme == "about" || scheme == "javascript") return false
                    decided = true
                    return when {
                        isAuth(url.host) -> { showPopup(temp); false }
                        isAllowed(url.host) -> {
                            webView.loadUrl(url.toString())
                            temp.post { temp.destroy() }
                            true
                        }
                        else -> {
                            blockedToast(url.host)
                            temp.post { temp.destroy() }
                            true
                        }
                    }
                }

                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    if (decided) return false
                    return decide(request.url)
                }

                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    if (!decided && decide(url?.let { Uri.parse(it) })) v.stopLoading()
                }
            }
            temp.webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView) = closePopup()
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = temp
            resultMsg.sendToTarget()
            return true
        }
    }

    // ---------- Pop-up login ----------

    private fun showPopup(popup: WebView) {
        if (popupView === popup) return
        closePopup()
        popupView = popup
        findViewById<FrameLayout>(R.id.root).addView(popup, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun closePopup() {
        val p = popupView ?: return
        popupView = null
        findViewById<FrameLayout>(R.id.root).removeView(p)
        p.post { p.destroy() }
        CookieManager.getInstance().flush()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(w: WebView) {
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.setBackgroundColor(getColor(R.color.bg))
    }
}
