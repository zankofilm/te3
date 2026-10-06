package ir.madreseyar.teacher

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val baseUrl = BuildConfig.API_BASE_URL.trimEnd('/') + "/"

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(WebView(this), true)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = settings.userAgentString + " MadreseyarTeacherAndroid/1.1.1"
            isVerticalScrollBarEnabled = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    return if (uri.host.equals("scooljavanrood.ir", ignoreCase = true)) false
                    else {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        true
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    injectPersianDateLayer(view)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = filePathCallback
                    return try {
                        val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                        filePicker.launch(intent)
                        true
                    } catch (_: Exception) {
                        fileCallback = null
                        Toast.makeText(this@MainActivity, "انتخاب فایل در این دستگاه در دسترس نیست.", Toast.LENGTH_SHORT).show()
                        false
                    }
                }
            }
            setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                try {
                    val request = DownloadManager.Request(Uri.parse(url))
                        .setMimeType(mimeType)
                        .addRequestHeader("User-Agent", userAgent)
                        .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType))
                    (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                    Toast.makeText(this@MainActivity, "دانلود شروع شد.", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            })
        }
        setContentView(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (savedInstanceState == null) webView.loadUrl(baseUrl) else webView.restoreState(savedInstanceState)
    }

    /**
     * Presentation-only Jalali layer. Server/API values are never modified.
     * Converts Gregorian dates in visible text and date/datetime-local controls to Persian calendar.
     */
    private fun injectPersianDateLayer(view: WebView) {
        val js = """
            (() => {
              if (window.__madreseyarJalali111) { window.__madreseyarJalali111.scan(); return; }
              const faDigits = s => String(s).replace(/\d/g, d => '۰۱۲۳۴۵۶۷۸۹'[Number(d)]);
              const fmt = new Intl.DateTimeFormat('fa-IR-u-ca-persian-nu-latn', {year:'numeric',month:'2-digit',day:'2-digit'});
              const toJ = (y,m,d) => {
                const dt = new Date(Date.UTC(+y,+m-1,+d,12,0,0));
                if (Number.isNaN(dt.getTime())) return null;
                const parts = Object.fromEntries(fmt.formatToParts(dt).filter(x=>x.type!=='literal').map(x=>[x.type,x.value]));
                return faDigits(parts.year + '/' + parts.month + '/' + parts.day);
              };
              const re = /\b(19\d{2}|20\d{2}|21\d{2})[-\/](0?[1-9]|1[0-2])[-\/](0?[1-9]|[12]\d|3[01])\b/g;
              const convert = s => String(s||'').replace(re, (all,y,m,d) => toJ(y,m,d) || all);
              const skip = el => el && (el.closest('script,style,code,pre') || el.isContentEditable);
              const textNode = n => { if (!n.parentElement || skip(n.parentElement)) return; const v=convert(n.nodeValue); if(v!==n.nodeValue)n.nodeValue=v; };
              const control = el => {
                if (!(el instanceof HTMLElement)) return;
                ['title','aria-label','placeholder'].forEach(a=>{ const v=el.getAttribute(a); if(v){const x=convert(v);if(x!==v)el.setAttribute(a,x);} });
                if (el instanceof HTMLInputElement && (el.type==='date'||el.type==='datetime-local')) {
                  const raw=el.value; if(raw && !el.dataset.gregorianValue){
                    el.dataset.gregorianValue=raw;
                    const m=raw.match(/^(\d{4})-(\d{2})-(\d{2})/);
                    if(m){ el.type='text'; el.value=toJ(m[1],m[2],m[3]) + (raw.length>10 ? '  '+raw.slice(11,16) : ''); el.readOnly=true; el.dataset.madreseyarJalali='1'; }
                  }
                }
              };
              const scan = root => {
                const r=root||document.body; if(!r)return;
                if(r.nodeType===3){textNode(r);return;}
                control(r);
                const w=document.createTreeWalker(r,NodeFilter.SHOW_TEXT); let n; while(n=w.nextNode())textNode(n);
                if(r.querySelectorAll)r.querySelectorAll('input,time,[title],[aria-label],[placeholder]').forEach(control);
              };
              let timer=null;
              const obs=new MutationObserver(ms=>{ clearTimeout(timer); timer=setTimeout(()=>ms.forEach(m=>m.addedNodes.forEach(scan)),60); });
              scan(document.body); obs.observe(document.documentElement,{childList:true,subtree:true,characterData:false});
              window.__madreseyarJalali111={scan:()=>scan(document.body)};
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
