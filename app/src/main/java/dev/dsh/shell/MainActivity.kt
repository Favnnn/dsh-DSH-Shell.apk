package dev.dsh.shell

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private companion object {
        const val PREFS = "dsh_shell"
        const val KEY_URL = "start_url"
        const val KEY_SAVED = "saved_urls"
        const val EXTRA_CHANGE_URL = "change_url"
        const val EXTRA_REFRESH = "do_refresh"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorText: TextView

    private var startHost: String? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var safeTopPx = 0
    private var safeBottomPx = 0
    /** Основная загрузка упала с ошибкой — адрес с токеном не стирать. */
    private var pageFailed = false

    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var notifPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Настоящий edge-to-edge: панели прозрачные (тема), WebView рисуется на
        // весь экран, поэтому фон страницы (цвет или фотография) виден и за
        // статус-баром, и за кнопками навигации. Отступ для контента задаётся
        // внутри страницы (padding рамки приложения, см. applyInsetsToPage) —
        // чуть меньше высоты панелей.
        runCatching {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= 29) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
        }
        runCatching {
            // Слушатель на корне разметки.
            val rootLayout = findViewById<View>(R.id.rootLayout)
            ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
                consumeInsets(insets)
            }
            rootLayout.post { ViewCompat.requestApplyInsets(rootLayout) }
        }

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        web = findViewById(R.id.web)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.errorView)
        errorText = findViewById(R.id.errorText)

        // Проверенный в v1.7 путь — слушатель прямо на WebView.
        ViewCompat.setOnApplyWindowInsetsListener(web) { _, insets ->
            consumeInsets(insets)
        }

        fileChooserLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val callback = filePathCallback
                filePathCallback = null
                callback?.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                )
            }
        notifPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        setupWebView()

        findViewById<View>(R.id.btnRetry).setOnClickListener {
            errorView.visibility = View.GONE
            loadStored()
        }
        findViewById<View>(R.id.btnChange).setOnClickListener { askServer(initial = false) }

        val wantsChange = intent?.getStringExtra(EXTRA_CHANGE_URL) == "true"
        val wantsRefresh = intent?.getStringExtra(EXTRA_REFRESH) == "true"
        val saved = prefs.getString(KEY_URL, null)
        if (saved.isNullOrEmpty()) {
            askUrl(initial = true)
        } else {
            // Активный адрес начинаем грузить сразу (быстрый старт), но выбор
            // сервера показываем ПРИ КАЖДОМ входе — даже если адрес один
            // (иначе не добавить второй). Шорткат «Обновить» выбор пропускает.
            startHost = Uri.parse(saved).host
            web.loadUrl(saved)
            if (wantsChange) {
                askServer(initial = false)
            } else if (!wantsRefresh) {
                askServer(initial = true)
            } else {
                web.reload()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra(EXTRA_CHANGE_URL) == "true" && !isFinishing) {
            askServer(initial = false)
        }
        if (intent.getStringExtra(EXTRA_REFRESH) == "true") {
            if (web.url.isNullOrEmpty()) loadStored() else web.reload()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        web.setBackgroundColor(getColor(R.color.background))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().setAcceptCookie(true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase()
                return when {
                    url.host != null && url.host == startHost -> false
                    scheme == "http" || scheme == "https" -> { openExternal(url); true }
                    scheme == "intent" -> { openIntentUri(url.toString()); true }
                    else -> false
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                // Токен из адреса стираем ТОЛЬКО после успешной загрузки: сервер
                // харнеса подтверждает токен, обменивает его на куку и делает
                // редирект на чистый './'. Если загрузка упала с ошибкой — адрес
                // с токеном обязан остаться для повторной попытки.
                if (!pageFailed) rememberWithoutToken(url)
                flushCookies()
                updateSystemBarColors()
                applyInsetsToPage()
                // Страница гидратируется асинхронно — подстраховаться повтором.
                web.postDelayed({ applyInsetsToPage() }, 1200)
                web.postDelayed({ applyInsetsToPage() }, 4000)
                detectAuthRequired()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    pageFailed = true
                    val u = request.url
                    val where = (u.host ?: "") + if (u.port != -1) ":${u.port}" else ""
                    val details = error.description?.toString().orEmpty()
                    showError(if (where.isEmpty()) details else "$details\n($where)")
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    fileChooserLauncher.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }

        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("http://") || url.startsWith("https://")) {
                enqueueDownload(url, userAgent, contentDisposition, mimeType)
            } else {
                openExternal(Uri.parse(url))
            }
        }
    }

    private fun loadStored() {
        val url = prefs.getString(KEY_URL, null)
        if (url.isNullOrEmpty()) {
            askUrl(initial = true)
            return
        }
        errorView.visibility = View.GONE
        startHost = Uri.parse(url).host
        web.loadUrl(url)
    }

    /** Сохранённые адреса (порядок сохранён, максимум 4, дубли по host:port убраны). */
    private fun savedUrls(): MutableList<String> =
        prefs.getString(KEY_SAVED, "")
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.toMutableList()
            ?: mutableListOf()

    private fun addSaved(url: String) {
        val uri = Uri.parse(url)
        val key = "${uri.scheme}://${uri.host}:${uri.port}"
        val list = savedUrls()
        list.removeAll { u ->
            val x = Uri.parse(u)
            "${x.scheme}://${x.host}:${x.port}" == key
        }
        list.add(url)
        while (list.size > 4) list.removeAt(0)
        prefs.edit().putString(KEY_SAVED, list.joinToString("\n")).apply()
    }

    /** Подключиться к адресу: активный адрес + добавление в список выбора. */
    private fun connect(url: String) {
        prefs.edit().putString(KEY_URL, url).apply()
        addSaved(url)
        errorView.visibility = View.GONE
        startHost = Uri.parse(url).host
        // Тот же адрес, что уже грузится/загружен, — перезагрузка не нужна.
        if (web.url != url) web.loadUrl(url)
    }

    /**
     * Выбор одного из сохранённых адресов (локальный / VPN-туннель). Тап —
     * подключение; «Другой адрес…» — ручной ввод; отмена = выход при первом
     * запуске.
     */
    private fun askServer(initial: Boolean) {
        val list = savedUrls()
        if (list.isEmpty()) {
            askUrl(initial = initial)
            return
        }
        val labels = list.map { u ->
            val x = Uri.parse(u)
            val host = x.host ?: u
            if (x.port != -1) "$host:${x.port}" else host
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (initial) R.string.pick_server_title else R.string.change_address_long)
            .setItems(labels) { _, which -> connect(list[which]) }
            .setNeutralButton(R.string.other_address) { _, _ -> askUrl(initial = initial) }
            .setNegativeButton(if (initial) R.string.exit else R.string.cancel) { _, _ ->
                if (initial) finish()
            }
            .setCancelable(!initial)
            .create()
            .show()
    }

    /**
     * Первый вход обычно делают по адресу с ?token=... — сервер обменивает токен
     * на cookie. После первой успешной загрузки сохраняем адрес уже без query,
     * чтобы дальше приложение открывало чистый адрес (как в браузере на ПК).
     */
    private fun rememberWithoutToken(currentUrl: String?) {
        val stored = prefs.getString(KEY_URL, null)
        if (stored.isNullOrEmpty() || currentUrl.isNullOrEmpty()) return
        val storedUri = Uri.parse(stored)
        if (storedUri.query.isNullOrEmpty()) return
        val current = Uri.parse(currentUrl)
        if (storedUri.host == current.host && storedUri.path == current.path) {
            val clean = buildString {
                append(storedUri.scheme).append("://").append(storedUri.host)
                if (storedUri.port != -1) append(':').append(storedUri.port)
                append(storedUri.path ?: "/")
            }
            if (clean != stored) {
                prefs.edit().putString(KEY_URL, clean).apply()
                // Обновить этот адрес и в списке выбора (там он мог быть с токеном).
                val list = savedUrls()
                val idx = list.indexOfFirst { u ->
                    val x = Uri.parse(u)
                    x.host == storedUri.host && x.port == storedUri.port && x.scheme == storedUri.scheme
                }
                if (idx >= 0) {
                    list[idx] = clean
                    prefs.edit().putString(KEY_SAVED, list.joinToString("\n")).apply()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateSystemBarColors()
    }

    /**
     * Отступ контента внутри самой страницы: его получает рамка приложения
     * (первый элемент с боксом внутри #root, спуск сквозь служебные обёртки) —
     * чуть меньше высоты системных панелей. WebView остаётся на весь экран
     * ВСЕГДА, поэтому фон страницы (цвет или фото) никогда не сдвигается и
     * тёмных полос не появляется. Высоту клавиатуры отдаём тем же механизмом:
     * рамка поджимается, поле ввода поднимается над клавиатурой.
     */
    private fun applyInsetsToPage() {
        if (!this::web.isInitialized) return
        val density = resources.displayMetrics.density
        val top = ((safeTopPx / density).toInt()).toString() + "px"
        val bottom = ((safeBottomPx / density).toInt()).toString() + "px"
        // Прямой ребёнок #root — служебная обёртка без бокса (display:contents,
        // offsetHeight=0): паддинг на ней физически не рисуется. Спускаемся к
        // первому элементу с реальным боксом — рамке приложения — и даём отступ
        // ей: padding + border-box + grid-template-rows minmax(0,1fr), как в
        // самом интерфейсе для заголовка Windows (AppFrame.module.css).
        // Актуальные значения лежат в глобалах окна: таймер и наблюдатель
        // создаются один раз и при каждом вызове читают СВЕЖИЕ значения
        // (раньше замыкание держало старый отступ и откатывало его).
        web.evaluateJavascript(
            "(function(){try{" +
                "var d=document;sched=false;" +
                "window.__dshSafeTop='" + top + "';" +
                "window.__dshSafeBottom='" + bottom + "';" +
                "function target(){var r=d.getElementById('root');if(!r){return null;}" +
                "var el=r.firstElementChild,g=0;" +
                "while(el&&el.children.length===1&&g++<6){var s=getComputedStyle(el);" +
                "if(s.display==='contents'||el.offsetHeight===0){el=el.firstElementChild;}else{break;}}" +
                "return el;}" +
                "window.__dshSafeApply=function(){" +
                "var k=target();if(!k){return;}" +
                "var top=window.__dshSafeTop,bottom=window.__dshSafeBottom;" +
                "k.classList.add('dsh-safe-target');" +
                "if(k.style.paddingTop!==top){k.style.setProperty('padding-top',top,'important');}" +
                "if(k.style.paddingBottom!==bottom){k.style.setProperty('padding-bottom',bottom,'important');}" +
                "k.style.setProperty('box-sizing','border-box','important');" +
                "k.style.setProperty('grid-template-rows','minmax(0,1fr)','important');};" +
                "window.__dshSafeApply();" +
                "var st=d.getElementById('dsh-shell-safe-style');" +
                "if(!st){st=d.createElement('style');st.id='dsh-shell-safe-style';" +
                "(d.head||d.documentElement).appendChild(st);}" +
                "st.textContent='#root .dsh-safe-target{padding-top:'+window.__dshSafeTop+' !important;" +
                "padding-bottom:'+window.__dshSafeBottom+' !important;box-sizing:border-box !important;" +
                "grid-template-rows:minmax(0,1fr) !important;}';" +
                "if(!window.__dshSafeObserver){var mo=new MutationObserver(function(){" +
                "if(sched){return;}sched=true;" +
                "requestAnimationFrame(function(){sched=false;window.__dshSafeApply();});});" +
                "mo.observe(d.documentElement,{childList:true,subtree:true});" +
                "window.__dshSafeObserver=mo;}" +
                "if(!window.__dshSafeTimer){window.__dshSafeTimer=setInterval(window.__dshSafeApply,2000);}" +
                "}catch(e){}})()",
            null
        )
    }

    /**
     * Зона за прозрачными панелями: если фоном страницы картинка — не красим
     * ничего. Если фон — цвет, берём реальный цвет верха интерфейса для фона
     * WebView на время загрузки и для контраста иконок статус-бара.
     */
    private fun updateSystemBarColors() {
        val density = resources.displayMetrics.density
        val topPx = (safeTopPx / density).toInt()
        web.evaluateJavascript(
            "(function(topPx){try{" +
                "var d=document,b=d.body,r=d.getElementById('root');if(!b||!r){return '';}" +
                "var bi=getComputedStyle(b).backgroundImage;" +
                "if(!bi||bi==='none'){bi=getComputedStyle(r).backgroundImage;}" +
                "if(bi&&bi!=='none'){return '';}" +
                "function sample(y){var el=d.elementFromPoint(Math.floor(window.innerWidth/2),y);" +
                "while(el){var s=getComputedStyle(el).backgroundColor;" +
                "var m=(s||'').match(/rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)(?:,\\s*([\\d.]+))?\\)/);" +
                "if(m&&(m[4]===undefined||parseFloat(m[4])>0.5)){return [parseInt(m[1]),parseInt(m[2]),parseInt(m[3])];}" +
                "el=el.parentElement;}return null;}" +
                "var t=sample(topPx+6);" +
                "if(!t){return '';}" +
                "return 'rgb('+t[0]+','+t[1]+','+t[2]+')';" +
                "}catch(e){return '';}})($topPx)"
        ) { json -> applyPageSurfaceColors(json) }
    }

    private fun applyPageSurfaceColors(json: String?) {
        val raw = json?.trim()?.removeSurrounding("\"") ?: return
        val m = Regex("rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)(?:,\\s*([\\d.]+))?\\)").find(raw) ?: return
        val alpha = m.groupValues[4].toFloatOrNull() ?: 1f
        if (alpha < 0.5f) return
        val r = m.groupValues[1].toInt()
        val g = m.groupValues[2].toInt()
        val b = m.groupValues[3].toInt()
        web.setBackgroundColor(Color.rgb(r, g, b))
        val light = 0.299f * r + 0.587f * g + 0.114f * b > 160f
        runCatching {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = light
        }
    }

    /** Единая обработка insets: заполняет safe-зоны и проталкивает их в страницу. */
    private fun consumeInsets(insets: WindowInsetsCompat): WindowInsetsCompat {
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val shave = (6 * resources.displayMetrics.density).toInt()
        safeTopPx = maxOf(0, bars.top - shave)
        // Клавиатура рисуется НАД панелью навигации, а её inset включает высоту
        // этой панели. Сжимаем WebView только на клавиатуру (без зоны навигации):
        // клавиатура накрывает нижний край WebView ровно встык — тёмной полосы
        // нет, а страница получает обычный resize и поднимает поле ввода.
        // WebView остаётся на весь экран всегда — фото/фон не двигаются, тёмных
        // полос нет. Высоту клавиатуры (она рисуется над зоной навигации, inset
        // включает её) отдаём внутрь страницы: рамка поджимается, поле ввода
        // поднимается над клавиатурой. Зазор — для красоты.
        val kbGap = Math.round(4 * resources.displayMetrics.density)
        safeBottomPx = if (ime.bottom > 0) {
            ime.bottom + kbGap
        } else {
            maxOf(0, bars.bottom - shave)
        }
        if (this::progress.isInitialized) {
            progress.translationY = safeTopPx.toFloat()
        }
        applyInsetsToPage()
        return insets
    }

    /**
     * Сервер харнеса отдаёт на индекс 401 с этим текстом, когда у запроса нет
     * валидной куки для связки host:port (нет входа по токену, сменился
     * экземпляр сервера, хранилище WebView очищено). Показываем понятный экран
     * с инструкцией вместо сырого текста.
     */
    private fun detectAuthRequired() {
        web.evaluateJavascript(
            "(function(){try{return ((document.body&&document.body.innerText)||'')" +
                ".slice(0,200);}catch(e){return '';}})()"
        ) { text ->
            val body = text?.trim()?.removeSurrounding("\"") ?: return@evaluateJavascript
            if (body.contains("authentication required", ignoreCase = true)) {
                showError(getString(R.string.auth_required_hint))
            }
        }
    }

    /**
     * WebView сбрасывает куки на диск лениво, по своему таймеру. Если процесс
     * убит до этого (свайп из недавних, обновление, система) — свежая кука
     * входа теряется, и сервер отвечает «authentication required». Принудительно
     * сохраняем куки после каждого входа/успешной загрузки.
     */
    private fun flushCookies() {
        CookieManager.getInstance().flush()
    }

    override fun onPause() {
        super.onPause()
        flushCookies()
    }

    private fun showError(details: String?) {
        progress.visibility = View.GONE
        errorText.text = details
        errorView.visibility = View.VISIBLE
    }

    private fun askUrl(initial: Boolean) {
        val input = EditText(this).apply {
            hint = getString(R.string.url_hint)
            setSingleLine(true)
            setText(
                if (initial) clipboardText().takeIf { looksLikeUrl(it) }
                else prefs.getString(KEY_URL, "")
            )
        }
        val density = resources.displayMetrics.density
        val wrap = FrameLayout(this).apply {
            setPadding((20 * density).toInt(), (10 * density).toInt(), (20 * density).toInt(), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (initial) R.string.url_title_first else R.string.url_title_change)
            .setView(wrap)
            .setPositiveButton(R.string.open, null)
            .setNegativeButton(if (initial) R.string.exit else R.string.cancel, null)
            .setCancelable(!initial)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val normalized = normalizeUrl(input.text.toString())
                if (normalized == null) {
                    input.error = getString(R.string.invalid_url)
                } else {
                    dialog.dismiss()
                    connect(normalized)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
                if (initial) finish()
            }
        }
        dialog.show()
    }

    private fun clipboardText(): String {
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return ""
        return cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: ""
    }

    private fun looksLikeUrl(text: String?): Boolean = !text.isNullOrEmpty() && normalizeUrl(text) != null

    private fun normalizeUrl(raw: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if (!text.startsWith("http://", ignoreCase = true) &&
            !text.startsWith("https://", ignoreCase = true)
        ) {
            text = "http://$text"
        }
        val uri = Uri.parse(text)
        val host = uri.host ?: return null
        if (host.isEmpty() || !host.contains('.')) return null
        return uri.normalizeScheme().toString()
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openIntentUri(text: String) {
        try {
            val intent = Intent.parseUri(text, Intent.URI_INTENT_SCHEME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallback = Intent.parseUri(text, Intent.URI_INTENT_SCHEME)
                val pkg = fallback.`package`
                if (!pkg.isNullOrEmpty()) {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (ignored: Exception) {
            }
        }
    }

    private fun enqueueDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                setTitle(fileName)
                setDescription(getString(R.string.downloading))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                userAgent?.let { addRequestHeader("User-Agent", it) }
            }
            getSystemService(DownloadManager::class.java)?.enqueue(request)
            Toast.makeText(this, getString(R.string.download_started, fileName), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            openExternal(Uri.parse(url))
        }
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}
