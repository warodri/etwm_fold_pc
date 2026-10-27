package com.etmw.foldpc.browser

import android.graphics.Bitmap

class BrowserTab(
    val id: Long,
    val webView: NoImeWebView
) {
    var title: String = ""
    var url: String = ""
    var favicon: Bitmap? = null
    var isLoading: Boolean = false
}
