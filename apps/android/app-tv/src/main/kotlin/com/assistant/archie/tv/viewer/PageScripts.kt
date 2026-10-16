package com.assistant.archie.tv.viewer

import com.assistant.archie.tv.control.MediaAction
import com.assistant.archie.tv.control.ScrollDirection

/*
 * The JavaScript the Viewer evaluates in the page (`WebView.evaluateJavascript`). One-way calls with a
 * string result only: ArchieWebView's rule "no addJavascriptInterface, ever" stands (spec 14 §4.2).
 */
object PageScripts {
    /** Number of elements spatial navigation can reach; 0 → Pointer mode switches on by itself. */
    const val FOCUSABLES = "(function(){try{return document.querySelectorAll(" +
        "'a[href],button,input,select,textarea,summary,[tabindex]:not([tabindex=\"-1\"]),[contenteditable=\"true\"],[onclick],[role=button]'" +
        ").length}catch(e){return -1}})()"

    /**
     * Scrolls the page's main scroller — the document, or (dashboards with `body{overflow:hidden}`)
     * the largest scrollable box — and returns its new scrollTop.
     */
    fun scroll(direction: ScrollDirection, fraction: Double = 0.8): String {
        val d = direction.name.lowercase()
        return "(function(d,f){" +
            "var e=document.scrollingElement||document.documentElement;" +
            "function sc(x){var s=getComputedStyle(x);return /(auto|scroll)/.test(s.overflowY)&&x.scrollHeight>x.clientHeight+4}" +
            "if(e.scrollHeight<=e.clientHeight+4){var b=null,a=0;[].forEach.call(document.querySelectorAll('body *'),function(x){if(sc(x)){var r=x.clientWidth*x.clientHeight;if(r>a){a=r;b=x}}});if(b)e=b}" +
            "var h=e.clientHeight||innerHeight;" +
            "var t=d==='top'?0:d==='bottom'?e.scrollHeight:e.scrollTop+(d==='up'?-f:f)*h;" +
            "e.scrollTo({top:t,behavior:'smooth'});return Math.round(t)})('$d',$fraction)"
    }

    /**
     * Play / pause the page's media: pause what plays (toggle / pause), else play the first visible
     * `video` / `audio`. Returns "playing", "paused" or "none".
     */
    fun media(action: MediaAction): String {
        val a = action.name.lowercase()
        return "(function(a){var m=[].slice.call(document.querySelectorAll('video,audio'));if(!m.length)return 'none';" +
            "var p=m.filter(function(e){return !e.paused&&!e.ended});" +
            "if(a!=='play'&&p.length){p.forEach(function(e){e.pause()});return 'paused'}" +
            "if(a==='pause')return 'paused';if(p.length)return 'playing';" +
            "var v=m.filter(function(e){var r=e.getBoundingClientRect();return r.width>0&&r.bottom>0&&r.top<innerHeight})[0]||m[0];" +
            "var r=v.play();if(r&&r.catch)r.catch(function(){});return 'playing'})('$a')"
    }

    /** CSS zoom of the whole page (reflows like a browser's zoom; re-applied after every load). */
    fun zoom(percent: Int): String =
        "(function(){document.documentElement.style.zoom=${if (percent == 100) "''" else "'${percent / 100.0}'"};return 1})()"

    /** Local / session storage of the page's origin (Hard reset). */
    const val CLEAR_STORAGE = "(function(){try{localStorage.clear();sessionStorage.clear()}catch(e){}return 1})()"

    /** `"playing"` (a JSON string from evaluateJavascript) → playing. */
    fun parseMedia(raw: String?): Boolean? = when (raw?.trim()?.trim('"')) {
        "playing" -> true
        "paused" -> false
        else -> null
    }

    /** Zoom steps of Menu → Zoom (Left/Right), as in desktop browsers. */
    val ZOOM_STEPS = listOf(50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200)

    fun zoomStep(current: Int, delta: Int): Int {
        val i = ZOOM_STEPS.indexOf(current).takeIf { it >= 0 } ?: ZOOM_STEPS.indexOf(100)
        return ZOOM_STEPS[(i + delta).coerceIn(0, ZOOM_STEPS.lastIndex)]
    }
}
