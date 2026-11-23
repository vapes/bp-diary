package io.github.vapes.bpdiary

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.util.AtomicFile
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import java.io.File

/**
 * Native shell for the diary: the UI is the bundled web app (assets/index.html) in a WebView.
 * Records are also written to the app's private files (records.json), which Android Auto Backup
 * includes, so they survive "clear browser data" and come back after a reinstall from Google backup.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingExport: String? = null

    private val recordsFile by lazy { AtomicFile(File(filesDir, "records.json")) }

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(uri?.let { arrayOf(it) })
        fileCallback = null
    }
    private val saveCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { writeExport(it) }
    private val saveJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { writeExport(it) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        web = WebView(this)
        val root = FrameLayout(this).apply { addView(web) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            // Everything is local; never navigate away from the app.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                pickFile.launch("*/*")
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setOnCancelListener { result.confirm() }
                    .show()
                return true
            }
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    private fun callJs(script: String) = runOnUiThread { web.evaluateJavascript(script, null) }

    private fun writeExport(uri: Uri?) {
        val content = pendingExport
        pendingExport = null
        if (uri == null || content == null) return
        val ok = runCatching {
            contentResolver.openOutputStream(uri)!!.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        }.isSuccess
        callJs("window.onNativeSaved && onNativeSaved($ok)")
    }

    private fun printPage(title: String) {
        val printManager = getSystemService(PRINT_SERVICE) as PrintManager
        val base = web.createPrintDocumentAdapter(title)
        val adapter = object : PrintDocumentAdapter() {
            override fun onStart() = base.onStart()
            override fun onLayout(
                old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal?,
                callback: LayoutResultCallback, extras: Bundle?,
            ) = base.onLayout(old, new, cancel, callback, extras)

            override fun onWrite(
                pages: Array<out PageRange>, dest: ParcelFileDescriptor, cancel: CancellationSignal?,
                callback: WriteResultCallback,
            ) = base.onWrite(pages, dest, cancel, callback)

            override fun onFinish() {
                base.onFinish()
                callJs("window.onNativePrintDone && onNativePrintDone()")
            }
        }
        val attrs = PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build()
        printManager.print(title, adapter, attrs)
    }

    /** Called from JavaScript as window.AndroidBridge. */
    inner class Bridge {
        @JavascriptInterface
        fun loadData(): String =
            runCatching { recordsFile.readFully().toString(Charsets.UTF_8) }.getOrDefault("")

        @JavascriptInterface
        fun saveData(json: String) {
            val out = recordsFile.startWrite()
            try {
                out.write(json.toByteArray(Charsets.UTF_8))
                recordsFile.finishWrite(out)
            } catch (e: Exception) {
                recordsFile.failWrite(out)
            }
        }

        @JavascriptInterface
        fun saveFile(name: String, mime: String, content: String) = runOnUiThread {
            pendingExport = content
            if (mime.contains("csv")) saveCsv.launch(name) else saveJson.launch(name)
        }

        @JavascriptInterface
        fun print(title: String) = runOnUiThread { printPage(title) }
    }
}

