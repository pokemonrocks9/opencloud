/**
 * openCloud Android client application
 *
 * Shows a web office editor (e.g. Euro-Office) full screen inside the app,
 * instead of handing the user off to a browser tab.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 */

package eu.opencloud.android.presentation.weboffice

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import eu.opencloud.android.domain.files.usecases.ManageDeepLinkUseCase
import eu.opencloud.android.presentation.authentication.AccountUtils
import eu.opencloud.android.presentation.sharing.ShareActivity
import eu.opencloud.android.ui.activity.FileActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import android.widget.Toast
import java.net.URI
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder

class WebOfficeActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val manageDeepLinkUseCase: ManageDeepLinkUseCase by inject()
    private var editorHost: String? = null
    /** host of the OpenCloud server; the editor's "back to folder" button navigates there */
    private var serverHost: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val payload = intent.getStringExtra(EXTRA_PAYLOAD)
        if (payload == null) {
            finish()
            return
        }

        val root = FrameLayout(this)
        webView = WebView(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        supportActionBar?.hide()

        // keep the editor out from under the status / navigation bars
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            WindowInsetsCompat.CONSUMED
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            // the editor uses window.open for "go to folder" and hyperlinks; catch those in onCreateWindow
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame || request.url.host == editorHost) return false
                handleExternalUrl(request.url)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
                view.evaluateJavascript(EDITOR_TWEAKS_JS, null)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // window.open(): load it in a throwaway WebView just to learn the URL, then decide what to do
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val catcher = WebView(this@WebOfficeActivity)
                catcher.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        handleExternalUrl(request.url)
                        v.destroy()
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = catcher
                resultMsg.sendToTarget()
                return true
            }

            // "insert image" etc. in the editor
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(params.createIntent(), REQUEST_FILE_CHOOSER)
                    true
                } catch (e: Exception) {
                    fileChooserCallback = null
                    false
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // the editor is a single page app: back closes it (changes autosave server-side)
                finish()
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            loadEditor(payload)
        }
    }

    private fun loadEditor(payload: String) {
        try {
            val json = JSONObject(payload)
            val appUrl = json.getString("app_url")
            val method = json.optString("method", "GET").uppercase()
            val params = json.optJSONObject("form_parameters")
            editorHost = Uri.parse(appUrl).host
            // WOPI apps get the server's address in the WOPISrc parameter
            serverHost = Uri.parse(appUrl).getQueryParameter("WOPISrc")?.let { Uri.parse(it).host }
                ?: Uri.parse(appUrl).getQueryParameter("wopisrc")?.let { Uri.parse(it).host }

            val form = buildString {
                params?.keys()?.forEach { key ->
                    if (isNotEmpty()) append('&')
                    append(URLEncoder.encode(key, "UTF-8")).append('=')
                    append(URLEncoder.encode(params.getString(key), "UTF-8"))
                }
            }

            if (method == "POST") {
                webView.postUrl(appUrl, form.toByteArray())
            } else {
                val sep = if (appUrl.contains('?')) '&' else '?'
                webView.loadUrl(if (form.isEmpty()) appUrl else "$appUrl$sep$form")
            }
        } catch (e: Exception) {
            Timber.e(e, "Could not open web office editor")
            finish()
        }
    }

    /** Links leaving the editor: back to OpenCloud closes the editor, anything else opens in a browser tab. */
    private fun handleExternalUrl(url: Uri) {
        // the editor's Share button points at the file's private link with ?details=sharing:
        // open the app's own share screen for that file instead of leaving the editor
        if (url.host != null && url.host == serverHost && url.getQueryParameter("details") == "sharing" &&
            url.pathSegments.size >= 2 && url.pathSegments[url.pathSegments.size - 2] == "f"
        ) {
            openShareScreen(url)
        } else if (url.host != null && url.host == serverHost) {
            finish()
        } else if (url.scheme == "http" || url.scheme == "https") {
            CustomTabsIntent.Builder().build().launchUrl(this, url)
        }
    }

    private fun openShareScreen(url: Uri) {
        val account = AccountUtils.getCurrentOpenCloudAccount(this) ?: return
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val privateLink = url.buildUpon().clearQuery().fragment(null).build().toString()
                manageDeepLinkUseCase(ManageDeepLinkUseCase.Params(URI(privateLink), account.name)).getDataOrNull()
            }
            if (file == null) {
                Toast.makeText(this@WebOfficeActivity, "Couldn't find this file to share", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // the editor stays open underneath; back from the share screen returns to it
            startActivity(
                Intent(this@WebOfficeActivity, ShareActivity::class.java)
                    .putExtra(FileActivity.EXTRA_FILE, file)
                    .putExtra(FileActivity.EXTRA_ACCOUNT, account)
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE_CHOOSER) {
            fileChooserCallback?.onReceiveValue(
                if (resultCode == Activity.RESULT_OK) WebChromeClient.FileChooserParams.parseResult(resultCode, data) else null
            )
            fileChooserCallback = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PAYLOAD = "web_office_payload"
        private const val REQUEST_FILE_CHOOSER = 4711

        /**
         * Small layout tweaks injected into the editor page (and its same-origin editor iframe):
         *  - spreadsheets: move the cell input bar from the top to just above the sheet tabs,
         *    Google Sheets style, so it sits right above the keyboard while typing.
         * Re-applied whenever the editor iframe (re)loads.
         */
        private val EDITOR_TWEAKS_JS = """
            (function () {
              var css = '#idx-celleditor { order: 1 !important; }' +
                        '.page[data-name="home"] .statusbar { order: 2 !important; }';
              function inject(doc) {
                try {
                  if (!doc || !doc.head || doc.getElementById('oc-editor-tweaks')) return;
                  var s = doc.createElement('style');
                  s.id = 'oc-editor-tweaks';
                  s.textContent = css;
                  doc.head.appendChild(s);
                } catch (e) {}
              }
              function scan() {
                inject(document);
                var frames = document.querySelectorAll('iframe');
                for (var i = 0; i < frames.length; i++) {
                  var f = frames[i];
                  try { inject(f.contentDocument); } catch (e) {}
                  if (!f.dataset.ocTweaks) {
                    f.dataset.ocTweaks = '1';
                    f.addEventListener('load', function () { try { inject(this.contentDocument); } catch (e) {} });
                  }
                }
              }
              scan();
              if (!window.__ocTweaksObserver) {
                window.__ocTweaksObserver = new MutationObserver(scan);
                window.__ocTweaksObserver.observe(document.documentElement, { childList: true, subtree: true });
              }
            })();
        """.trimIndent()

        /** true if [data] is an /app/open payload (JSON) rather than a plain browser URL */
        fun isEditorPayload(data: String?): Boolean = data?.trimStart()?.startsWith("{") == true

        /** Opens the editor in-app for a JSON payload, or falls back to a browser tab for a plain URL. */
        fun open(context: Context, data: String) {
            if (isEditorPayload(data)) {
                context.startActivity(Intent(context, WebOfficeActivity::class.java).putExtra(EXTRA_PAYLOAD, data))
            } else {
                CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(data))
            }
        }
    }
}
