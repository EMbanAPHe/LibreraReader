package com.foobnix.pdf.search.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.foobnix.android.utils.LOG;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.TintUtil;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.pdf.info.model.OutlineLinkWrapper;
import com.foobnix.pdf.info.view.DragingDialogs;
import com.foobnix.pdf.info.wrapper.DocumentController;
import com.foobnix.pdf.info.wrapper.MagicHelper;
import com.foobnix.sys.ImageExtractor;
import com.foobnix.tts.EMBChapterExtractor;
import com.foobnix.tts.SynthesisQueue;
import com.foobnix.tts.TTSEngine;

import org.ebookdroid.core.codec.CodecDocument;
import org.ebookdroid.core.codec.OutlineLink;

import java.util.ArrayList;
import java.util.List;

/**
 * EMBReaderActivity v8 — chapter-as-article TTS reading view.
 *
 * ARCHITECTURE
 * ------------
 * For library→EMB launches: VerticalViewActivity opens first (building the epub cache),
 * then DocumentWrapperUI.onResume redirects here via launch(dc). This avoids the
 * "PDF file not found" error that occurred when EMBReaderActivity tried to open an
 * epub whose cache hadn't been built yet.
 *
 * For in-book launches (TTSControlsView, ShareDialog): launch(dc) is called directly.
 * The epub cache is already built since VerticalViewActivity had the book open.
 *
 * LOOP PREVENTION
 * ---------------
 * launchInFlight: set true in launch(dc), cleared in onResume() and onDestroy().
 * isActive: set true in onResume(), false in onDestroy().
 * DocumentWrapperUI.onResume checks both: !isActive && !launchInFlight before launching.
 * finishEMB() (back button) sets readingMode=SCROLL so VerticalViewActivity.onResume
 * doesn't trigger the redirect again.
 *
 * TTS
 * ---
 * We do NOT send TTS_STOP_DESTROY. That would shut down the engine (ttsEngine=null),
 * making SynthesisQueue unable to speak. Instead we call tts.stop() to stop playback
 * while keeping the engine alive and initialized.
 * SynthesisQueue uses speak(QUEUE_ADD) with PREFETCH_AHEAD sentences pre-queued
 * for sherpa-onnx's pre-synthesis pipeline.
 *
 * SETTINGS
 * --------
 * emb_dialog_anchor is a FrameLayout inside the root RelativeLayout. Its parent is
 * the RelativeLayout (a View), satisfying DragingPopup's cast on line 107:
 * ((View) anchor.getParent()).getWidth().
 */
@SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
public class EMBReaderActivity extends Activity {

    public static final String EXTRA_PATH   = "emb_path";
    public static final String EXTRA_PAGE   = "emb_page";
    public static final String EXTRA_PARAG  = "emb_parag";
    public static final String EXTRA_WIDTH  = "emb_width";
    public static final String EXTRA_HEIGHT = "emb_height";
    public static final String EXTRA_FONT   = "emb_font";

    private static final String TAG = "EMBReaderActivity";

    // -------------------------------------------------------------------------
    // Static state shared with DocumentWrapperUI
    // -------------------------------------------------------------------------

    /**
     * True while EMBReaderActivity is in the foreground.
     * Set in onResume(), cleared in onDestroy().
     */
    public static volatile boolean isActive = false;

    /**
     * True from the moment launch(dc) is called until onResume() fires.
     * Prevents DocumentWrapperUI.onResume from launching a second instance
     * during the Android activity transition window.
     */
    public static volatile boolean launchInFlight = false;

    /**
     * Last DocumentController used to launch. Used for TTS settings popup.
     * Null when launched from library with no in-book dc context.
     */
    public static volatile DocumentController lastDC = null;

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    private WebView     webView;
    private FrameLayout dialogAnchor;
    private ImageView   btnSettings;
    private ImageView   btnPrev;
    private ImageView   btnPlayPause;
    private ImageView   btnNext;
    private ImageView   btnClose;
    private TextView    tvProgress;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private EMBChapterExtractor.ChapterContent chapter;
    private SynthesisQueue synthesisQueue;
    private GestureDetector gestureDetector;

    private volatile int     currentSentenceIdx = 0;
    private volatile boolean isPlaying          = false;
    private volatile boolean pageLoaded         = false;
    private volatile boolean isDestroyed        = false;

    /** Set by finishEMB() so onDestroy knows not to reset readingMode twice. */
    private boolean userExited = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    // Launch — always from a DocumentController
    // -------------------------------------------------------------------------

    /**
     * Launch from DocumentController. Works for both:
     * - In-book launches (TTSControlsView, ShareDialog): dc is the live book controller
     * - Library→EMB redirect from DocumentWrapperUI.onResume: dc is from VerticalViewActivity
     *
     * In both cases the epub cache is already built by VerticalViewActivity's decoder,
     * so singleCodecContext(dc.getCurrentBook().getPath()) will succeed.
     */
    public static void launch(DocumentController dc) {
        Context ctx = dc.getActivity();
        if (ctx == null) return;

        launchInFlight = true;
        lastDC = dc;

        // Stop TTS speech without destroying the engine (critical!).
        // TTS_STOP_DESTROY would null out TTSEngine.ttsEngine, making speak() fail.
        try {
            TextToSpeech tts = TTSEngine.get().getTTS();
            if (tts != null) tts.stop();
        } catch (Exception ignored) {}

        int page  = dc.getCurentPageFirst1() - 1;
        int parag = AppSP.get().lastBookParagraph;

        Intent i = new Intent(ctx, EMBReaderActivity.class);
        i.putExtra(EXTRA_PATH,   dc.getCurrentBook().getPath());
        i.putExtra(EXTRA_PAGE,   page);
        i.putExtra(EXTRA_PARAG,  parag);
        i.putExtra(EXTRA_WIDTH,  dc.getBookWidth());
        i.putExtra(EXTRA_HEIGHT, dc.getBookHeight());
        i.putExtra(EXTRA_FONT,   BookCSS.get().fontSizeSp);
        ctx.startActivity(i);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emb_reader);

        webView      = findViewById(R.id.emb_webview);
        dialogAnchor = findViewById(R.id.emb_dialog_anchor);
        btnSettings  = findViewById(R.id.emb_btn_settings);
        btnPrev      = findViewById(R.id.emb_btn_prev);
        btnPlayPause = findViewById(R.id.emb_btn_play_pause);
        btnNext      = findViewById(R.id.emb_btn_next);
        btnClose     = findViewById(R.id.emb_btn_close);
        tvProgress   = findViewById(R.id.emb_tv_progress);

        applyTint();
        setupWebView();
        setupControls();
        loadChapterAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isActive       = true;
        launchInFlight = false; // EMB is now running; allow future re-launches if needed
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (synthesisQueue != null && isPlaying) {
            synthesisQueue.pause();
            isPlaying = false;
            updatePlayIcon();
        }
    }

    @Override
    protected void onDestroy() {
        isDestroyed    = true;
        isActive       = false;
        launchInFlight = false;

        // If EMB died unexpectedly (crash/error, not user Back press), reset the reading
        // mode so VerticalViewActivity.onResume doesn't immediately re-launch EMB.
        if (!userExited) {
            AppSP.get().readingMode = AppState.READING_MODE_SCROLL;
            AppSP.get().save();
        }

        if (synthesisQueue != null) synthesisQueue.stop();

        if (webView != null) {
            ViewGroup p = (ViewGroup) webView.getParent();
            if (p != null) p.removeView(webView);
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        finishEMB();
    }

    /**
     * Finish and reset readingMode so VerticalViewActivity.onResume won't re-launch EMB.
     */
    private void finishEMB() {
        userExited = true;
        AppSP.get().readingMode = AppState.READING_MODE_SCROLL;
        AppSP.get().save();
        finish();
    }

    // -------------------------------------------------------------------------
    // Tinting
    // -------------------------------------------------------------------------

    private void applyTint() {
        int tint = MagicHelper.getTintColor(), alpha = 230;
        TintUtil.setTintImageWithAlpha(btnSettings,  tint, alpha);
        TintUtil.setTintImageWithAlpha(btnPrev,      tint, alpha);
        TintUtil.setTintImageWithAlpha(btnPlayPause, tint, alpha);
        TintUtil.setTintImageWithAlpha(btnNext,      tint, alpha);
        TintUtil.setTintImageWithAlpha(btnClose,     tint, alpha);
    }

    // -------------------------------------------------------------------------
    // WebView — single tap highlights, double tap plays from here
    // -------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(false);
        ws.setBuiltInZoomControls(false);
        ws.setSupportZoom(false);
        ws.setTextZoom(100);
        ws.setLoadsImagesAutomatically(false);

        webView.addJavascriptInterface(new JsBridge(), "EMB");

        gestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                final float x = e.getX(), y = e.getY();
                webView.evaluateJavascript("getSentenceAt(" + x + "," + y + ")", value -> {
                    try {
                        int idx = Integer.parseInt(value.trim());
                        if (idx >= 0) mainHandler.post(() -> {
                            if (!isDestroyed) seekAndPlay(idx);
                        });
                    } catch (NumberFormatException ignored) {}
                });
                return true;
            }
        });

        webView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return false;
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (isDestroyed || pageLoaded) return;
                pageLoaded = true;

                // Single-tap: highlight only, no playback
                webView.evaluateJavascript(
                    "(function(){" +
                    "document.addEventListener('click',function(e){" +
                    "var i=getSentenceAt(e.clientX,e.clientY);" +
                    "if(i>=0){EMB.onSingleTap(i);}" +
                    "});" +
                    "})();", null);

                // Restore saved position without auto-playing
                int from = Math.max(0, AppSP.get().lastBookParagraph);
                if (chapter != null && !chapter.sentences.isEmpty()) {
                    from = Math.min(from, chapter.sentences.size() - 1);
                }
                currentSentenceIdx = from;
                updateProgress(from);
                final int f = from;
                mainHandler.postDelayed(() -> {
                    if (!isDestroyed) {
                        webView.evaluateJavascript("highlightSentence(" + f + ")", null);
                    }
                }, 300);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Controls
    // -------------------------------------------------------------------------

    private void setupControls() {
        btnPrev.setOnClickListener(v -> {
            int t = Math.max(0, currentSentenceIdx - 1);
            if (isPlaying) seekAndPlay(t); else justHighlight(t);
        });

        btnNext.setOnClickListener(v -> {
            if (chapter == null) return;
            int t = Math.min(chapter.sentences.size() - 1, currentSentenceIdx + 1);
            if (isPlaying) seekAndPlay(t); else justHighlight(t);
        });

        btnPlayPause.setOnClickListener(v -> {
            if (isPlaying) pausePlayback(); else startOrResume();
        });

        btnClose.setOnClickListener(v -> finishEMB());
        btnSettings.setOnClickListener(v -> openSettings());
    }

    // -------------------------------------------------------------------------
    // Settings — opens the full existing TTS dialog
    // -------------------------------------------------------------------------

    private void openSettings() {
        DocumentController dc = lastDC;
        if (dc == null) {
            Toast.makeText(this, "No book context for TTS settings", Toast.LENGTH_SHORT).show();
            return;
        }
        // dialogAnchor is a FrameLayout whose parent is the root RelativeLayout (a View).
        // This satisfies DragingPopup's cast: ((View) anchor.getParent()).getWidth()
        DragingDialogs.dialogTextToSpeech(dialogAnchor, dc, "");
    }

    // -------------------------------------------------------------------------
    // Chapter loading
    // -------------------------------------------------------------------------

    private void loadChapterAsync() {
        final String path   = getIntent().getStringExtra(EXTRA_PATH);
        final int    page   = getIntent().getIntExtra(EXTRA_PAGE,   0);
        final int    width  = getIntent().getIntExtra(EXTRA_WIDTH,  1080);
        final int    height = getIntent().getIntExtra(EXTRA_HEIGHT, 1920);
        final int    font   = getIntent().getIntExtra(EXTRA_FONT,   18);

        if (path == null || path.isEmpty()) {
            Toast.makeText(this, "No book path", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        final EMBChapterExtractor.ThemeParams theme = buildTheme();

        new Thread(() -> {
            CodecDocument dc = null;
            try {
                // VerticalViewActivity has already built the epub cache, so this should
                // succeed on first try. The cache file at CACHE_BOOK_DIR/hash.epub exists.
                dc = ImageExtractor.singleCodecContext(path, "");
                if (dc == null) { postError("Cannot open book at: " + path); return; }

                dc.getPageCount(width, height, font);

                List<OutlineLinkWrapper> outline = convertOutline(dc.getOutline());
                final EMBChapterExtractor.ChapterContent result =
                    EMBChapterExtractor.extract(dc, page, outline, theme);

                if (isDestroyed) return;
                chapter = result;

                mainHandler.post(() -> {
                    if (isDestroyed) return;
                    tvProgress.setText("0 / " + result.sentences.size());
                    webView.loadDataWithBaseURL(
                        "file:///android_asset/",
                        result.html, "text/html", "utf-8", null);
                });

            } catch (Exception e) {
                LOG.e(e);
                postError("Error loading chapter: " + e.getMessage());
            } finally {
                if (dc != null) try { dc.recycle(); } catch (Exception ignored) {}
            }
        }, "@EMB ChapterLoad").start();
    }

    private EMBChapterExtractor.ThemeParams buildTheme() {
        try {
            int bg   = MagicHelper.getBgColor();
            int text = MagicHelper.getTextColor();
            boolean dark = !AppState.get().isDayNotInvert;
            int hl = dark
                ? android.graphics.Color.parseColor("#5c4a00")
                : android.graphics.Color.parseColor("#ffe082");
            return new EMBChapterExtractor.ThemeParams(
                bg, text, hl, BookCSS.get().fontSizeSp,
                buildFontCSS(BookCSS.get().normalFont));
        } catch (Exception e) {
            LOG.e(e);
            return EMBChapterExtractor.ThemeParams.light();
        }
    }

    private String buildFontCSS(String f) {
        if (f == null || f.isEmpty() || f.contains("/") || f.endsWith(".ttf") || f.endsWith(".otf"))
            return "Georgia, 'Noto Serif', serif";
        return "'" + f + "', Georgia, serif";
    }

    private List<OutlineLinkWrapper> convertOutline(List<? extends OutlineLink> raw) {
        List<OutlineLinkWrapper> out = new ArrayList<>();
        if (raw == null) return out;
        for (OutlineLink link : raw) {
            try {
                OutlineLinkWrapper w = new OutlineLinkWrapper(
                    link.getTitle(), link.getLink(), link.getLevel(), link.linkUri);
                if (w.targetPage > 0) out.add(w);
            } catch (Exception ignored) {}
        }
        return out;
    }

    private void postError(String msg) {
        LOG.d(TAG, "postError:", msg);
        mainHandler.post(() -> {
            if (!isDestroyed) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                // Don't finish() - let user see the error and dismiss
                // finishEMB will be called if they press Back or Close
            }
        });
    }

    // -------------------------------------------------------------------------
    // Playback
    // -------------------------------------------------------------------------

    private void startOrResume() {
        if (chapter == null || chapter.sentences.isEmpty()) {
            Toast.makeText(this, "Chapter still loading…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ensureTTSReady()) return;

        if (synthesisQueue == null) {
            createQueue();
            isPlaying = true;
            updatePlayIcon();
            synthesisQueue.start(chapter.sentences, currentSentenceIdx);
        } else {
            synthesisQueue.resume();
            isPlaying = true;
            updatePlayIcon();
        }
    }

    /**
     * Verify the TTS engine is ready. If it was just created (async init), wait for onInit.
     * Returns true if ready immediately, false if we need to wait (will retry automatically).
     */
    private boolean ensureTTSReady() {
        if (TTSEngine.get().isInit()) return true;

        // Engine needs initialisation — getTTS() creates it and fires onInit asynchronously.
        // Use an anonymous class to stay compatible with the existing OnInitListener API.
        TTSEngine.get().getTTS(new android.speech.tts.TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    mainHandler.postDelayed(() -> {
                        if (!isDestroyed && !isPlaying) startOrResume();
                    }, 500);
                } else {
                    mainHandler.post(() -> Toast.makeText(EMBReaderActivity.this,
                        "TTS engine failed to initialise", Toast.LENGTH_SHORT).show());
                }
            }
        });
        Toast.makeText(this, "Initialising TTS engine…", Toast.LENGTH_SHORT).show();
        return false;
    }

    private void pausePlayback() {
        if (synthesisQueue != null) synthesisQueue.pause();
        isPlaying = false;
        updatePlayIcon();
    }

    private void justHighlight(int idx) {
        if (chapter == null || idx < 0 || idx >= chapter.sentences.size()) return;
        currentSentenceIdx = idx;
        webView.evaluateJavascript("highlightSentence(" + idx + ")", null);
        updateProgress(idx);
    }

    private void seekAndPlay(int idx) {
        if (chapter == null || idx < 0 || idx >= chapter.sentences.size()) return;
        currentSentenceIdx = idx;
        webView.evaluateJavascript("highlightSentence(" + idx + ")", null);
        updateProgress(idx);

        if (synthesisQueue != null) { synthesisQueue.stop(); synthesisQueue = null; }
        if (!ensureTTSReady()) return;
        createQueue();
        isPlaying = true;
        updatePlayIcon();
        synthesisQueue.start(chapter.sentences, idx);
    }

    private void createQueue() {
        synthesisQueue = new SynthesisQueue(new SynthesisQueue.Callback() {
            @Override public void onSentenceStart(int idx, String text) {
                currentSentenceIdx = idx;
                isPlaying = true;
                mainHandler.post(() -> {
                    if (isDestroyed) return;
                    webView.evaluateJavascript("highlightSentence(" + idx + ")", null);
                    updateProgress(idx);
                    updatePlayIcon();
                    AppSP.get().lastBookParagraph = idx;
                    AppSP.get().save();
                });
            }
            @Override public void onFinished() {
                isPlaying = false;
                mainHandler.post(() -> { if (!isDestroyed) updatePlayIcon(); });
            }
            @Override public void onError(int idx, Exception e) {
                LOG.e(e, TAG, "TTS error at sentence", idx);
            }
        });
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private void updateProgress(int idx) {
        if (chapter == null) return;
        tvProgress.setText((idx + 1) + " / " + chapter.sentences.size());
    }

    private void updatePlayIcon() {
        btnPlayPause.setImageResource(
            isPlaying ? R.drawable.glyphicons_174_pause
                      : R.drawable.glyphicons_175_play);
        TintUtil.setTintImageWithAlpha(btnPlayPause, MagicHelper.getTintColor(), 230);
    }

    // -------------------------------------------------------------------------
    // JS bridge
    // -------------------------------------------------------------------------

    private class JsBridge {
        @JavascriptInterface
        public void onSingleTap(final int idx) {
            mainHandler.post(() -> { if (!isDestroyed) justHighlight(idx); });
        }
    }
}
