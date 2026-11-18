package com.example.screenshotmanager

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.concurrent.thread

/**
 * Shows pCloud's own login page, so any extra step pCloud asks for (codes, new-device checks...)
 * is handled by pCloud itself. Once logged in, the session token the page keeps is checked
 * against the API and adopted by [Pcloud].
 */
class PcloudWebLoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private val triedTokens = HashSet<String>()
    private var loggedIn = false

    private val poll = object : Runnable {
        override fun run() {
            checkForSession()
            if (!loggedIn) handler.postDelayed(this, POLL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pcloud_web_login)
        findViewById<View>(R.id.root).padForSystemBars()
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        val progress = findViewById<LinearProgressIndicator>(R.id.progress)

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) = checkForSession()
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
        }

        // Start from a clean page so the account that ends up connected is the one the user logs in with.
        CookieManager.getInstance().removeAllCookies {
            webView.clearCache(true)
            webView.loadUrl(LOGIN_URL)
            handler.postDelayed(poll, POLL_MS)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        webView.destroy()
        super.onDestroy()
    }

    /** The page keeps its session token in a cookie or in local storage; collect anything token-like. */
    private fun checkForSession() {
        if (loggedIn) return
        val candidates = mutableSetOf<String>()
        for (url in COOKIE_URLS) {
            CookieManager.getInstance().getCookie(url)?.split(';')?.forEach { cookie ->
                val parts = cookie.trim().split('=', limit = 2)
                if (parts.size == 2 && parts[0].contains("auth", ignoreCase = true)) candidates += Uri.decode(parts[1])
            }
        }
        webView.evaluateJavascript(READ_STORAGE_JS) { result ->
            runCatching {
                val json = JSONObject(JSONTokener(result).nextValue() as String)
                json.keys().forEach { key -> candidates += json.getString(key).trim('"') }
            }
            tryTokens(candidates.filter { TOKEN_PATTERN.matches(it) }.toSet())
        }
    }

    private fun tryTokens(tokens: Set<String>) {
        val fresh = tokens - triedTokens
        if (fresh.isEmpty()) return
        triedTokens += fresh
        thread {
            val accepted = fresh.any { Pcloud.loginWithToken(applicationContext, it) }
            if (accepted) {
                runOnUiThread {
                    if (loggedIn) return@runOnUiThread
                    loggedIn = true
                    Toast.makeText(applicationContext, R.string.pcloud_login_ok, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private companion object {
        const val LOGIN_URL = "https://my.pcloud.com/#page=login"
        val COOKIE_URLS = listOf("https://my.pcloud.com", "https://e.pcloud.com", "https://www.pcloud.com", "https://pcloud.com")
        val TOKEN_PATTERN = Regex("[A-Za-z0-9]{20,}")
        const val POLL_MS = 1500L
        const val READ_STORAGE_JS = """(function() {
            var found = {};
            for (var i = 0; i < localStorage.length; i++) {
                var key = localStorage.key(i);
                if (/auth|token/i.test(key)) found[key] = String(localStorage.getItem(key));
            }
            return JSON.stringify(found);
        })()"""
    }
}
