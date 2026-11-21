package com.grove.music

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * (Testing) The YouTube Music website in an in-app web view, with ad/tracker requests blocked and
 * restyled to match Grove. One shared instance, so music keeps playing while you move around the app.
 */
object YtWeb {
    private var view: WebView? = null
    var accent: Int = 0xFF0050EF.toInt()

    // ad & tracking hosts / paths (same idea as uBlock Origin's network filters)
    private val blockedHosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "adservice.google.com", "imasdk.googleapis.com",
        "ads.youtube.com", "adsense.com", "2mdn.net", "moatads.com", "scorecardresearch.com",
    )
    private val blockedPaths = listOf(
        "/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_", "/youtubei/v1/log_event",
        "/api/stats/atr", "ad_break", "/generate_204",
    )

    private fun blocked(r: WebResourceRequest): Boolean {
        val u = r.url; val h = u.host.orEmpty(); val p = u.encodedPath.orEmpty() + "?" + u.encodedQuery.orEmpty()
        return blockedHosts.any { h == it || h.endsWith(".$it") } || blockedPaths.any { p.contains(it) }
    }

    private fun css(): String {
        val a = String.format("#%06X", 0xFFFFFF and accent)
        return """
        html, body, ytmusic-app, #nav-bar-background, ytmusic-player-bar, #player-bar-background { background-color: #000 !important; }
        #nav-bar-background, ytmusic-pivot-bar-renderer { border: none !important; }
        * { font-family: 'Segoe UI', 'Roboto', sans-serif !important; }
        h1, h2, .title, yt-formatted-string.title { font-weight: 300 !important; letter-spacing: -0.5px; }
        #progress-bar #primaryProgress, #sliderBar #primaryProgress, .slider-knob-inner,
        tp-yt-paper-slider .slider-knob-inner { background: $a !important; border-color: $a !important; }
        ytmusic-pivot-bar-item-renderer.iron-selected, .iron-selected yt-icon, #play-pause-button yt-icon { color: $a !important; fill: $a !important; }
        ytmusic-chip-cloud-chip-renderer[is-selected] a, ytmusic-chip-cloud-chip-renderer[chip-style] { border-radius: 0 !important; }
        ytmusic-chip-cloud-chip-renderer[is-selected] { background: $a !important; }
        img, .image, ytmusic-thumbnail-renderer, #thumbnail { border-radius: 0 !important; }
        /* hide ad / promo slots */
        ytmusic-mealbar-promo-renderer, ytmusic-statement-banner-renderer, .ytmusic-popup-container tp-yt-paper-dialog[aria-label*="Premium"],
        ytmusic-ad-slot-renderer, ad-slot-renderer, #player-ads, .video-ads, .ytp-ad-module, ytd-ad-slot-renderer,
        ytmusic-upsell-dialog-renderer { display: none !important; }
        """.trimIndent().replace("\n", " ")
    }

    private fun inject(v: WebView) {
        val js = """(function(){var s=document.getElementById('grove-style');if(!s){s=document.createElement('style');s.id='grove-style';
            (document.head||document.documentElement).appendChild(s);}s.textContent=${org.json.JSONObject.quote(css())};})();"""
        v.evaluateJavascript(js, null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun get(ctx: Context): WebView {
        view?.let { (it.parent as? ViewGroup)?.removeView(it); return it }
        return WebView(ctx).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(v: WebView, p: Int) { if (p > 30) inject(v) }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                    if (blocked(r)) WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) else null
                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) { inject(v) }
                override fun onPageFinished(v: WebView, url: String?) { inject(v) }
                override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) { inject(v) }
            }
            loadUrl("https://music.youtube.com/")
            view = this
        }
    }

    fun restyle() { view?.let { inject(it) } }

    fun go(path: String) { view?.loadUrl("https://music.youtube.com$path") }

    /** go home and scroll to a shelf by its title (e.g. "Speed dial", "Forgotten favorites") */
    fun shelf(vararg titles: String) {
        val v = view ?: return
        val js = """(function(t){var tries=0;function f(){var hs=document.querySelectorAll('ytmusic-carousel-shelf-renderer, ytmusic-shelf-renderer, ytmusic-grid-renderer');
          for(var i=0;i<hs.length;i++){var x=(hs[i].innerText||'').toLowerCase().slice(0,80);for(var j=0;j<t.length;j++){if(x.indexOf(t[j])>=0){hs[i].scrollIntoView({behavior:'smooth'});return;}}}
          if(tries++<40)setTimeout(f,300);}f();})(${org.json.JSONArray(titles.map { it.lowercase() })});"""
        if (v.url?.trimEnd('/')?.endsWith("music.youtube.com") != true) {
            v.loadUrl("https://music.youtube.com/"); v.postDelayed({ v.evaluateJavascript(js, null) }, 1500)
        } else v.evaluateJavascript(js, null)
    }

    /** keep the web view's cache under the limit the user set (in GB) */
    fun trim(ctx: Context, limitGb: Int) {
        fun size(f: java.io.File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { size(it) } ?: 0L
        val dirs = listOf(java.io.File(ctx.cacheDir, "WebView"), java.io.File(ctx.dataDir, "app_webview"))
        val used = dirs.sumOf { size(it) }
        if (used > limitGb * 1024L * 1024 * 1024) view?.clearCache(true)
    }

    fun cacheSize(ctx: Context): Long {
        fun size(f: java.io.File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { size(it) } ?: 0L
        return listOf(java.io.File(ctx.cacheDir, "WebView"), java.io.File(ctx.dataDir, "app_webview")).sumOf { size(it) }
    }
    fun canGoBack() = view?.canGoBack() == true
    fun back(): Boolean = view?.takeIf { it.canGoBack() }?.let { it.goBack(); true } ?: false
}
