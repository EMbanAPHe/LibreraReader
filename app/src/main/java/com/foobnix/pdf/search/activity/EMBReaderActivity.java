package com.foobnix.pdf.search.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import com.foobnix.tts.TTSNotification;
import com.foobnix.tts.TTSService;

import org.ebookdroid.core.codec.CodecDocument;
import org.ebookdroid.core.codec.OutlineLink;

import java.util.ArrayList;
import java.util.List;

/**
 * EMBReaderActivity v7 — chapter-as-article TTS reading view.
 *
 * ARCHITECTURE
 * ------------
 * Launched directly from ExtUtils.showDocumentInner (no VerticalViewActivity intermediary).
 * Opens the epub/book file itself using ImageExtractor.singleCodecContext.
 * lastDC holds the DocumentController from in-book launches for settings access.
 *
 * FIXES vs v6
 * -----------
 * 1. INFINITE LOOP: VerticalViewActivity intermediary removed. EMB now launches directly
 *    from ExtUtils. No onResume redirect means no ping-pong loop.
 *
 * 2. SETTINGS CRASH (ViewRootImpl cast): DragingPopup needs anchor.getParent() to be a View.
 *    The layout now contains emb_dialog_anchor (a FrameLayout inside the root RelativeLayout).
 *    Its parent IS the RelativeLayout (a View). Cast succeeds.
 *
 * 3. CANNOT CLOSE: finish() now also resets readingMode to READING_MODE_SCROLL so that
 *    if the back-stack returns to VerticalViewActivity it doesn't re-launch EMB.
 *
 * 4. TEXT TOO HIGH: fitsSystemWindows="true" on the root layout handles status/nav bar insets.
 *
 * 5. NO AUTO-PLAY: Chapter loads and highlights the resume position but does NOT start TTS.
 *    User must tap ▶ to begin.
 *
 * 6. DOUBLE-TAP TO PLAY: Single tap → highlight sentence only. Double-tap → highlight AND
 *    start playing from that sentence. Implemented via GestureDetector on the WebView.
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

    /**
     * Set to true from onResume, false from onDestroy.
     * Kept only for external code that checks it (e.g. TTSControlsView launch guard).
     */
    public static volatile boolean isActive = false;

    /**
     * Last DocumentController from an in-book launch. Used for settings popup.
     * Null when launched from the library.
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

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    // Launch
    // -------------------------------------------------------------------------

    /** Launch from DocumentController (in-book button / ShareDialog). */
    public static void launch(DocumentController dc) {
        Context ctx = dc.getActivity();
        if (ctx == null) return;
        lastDC = dc;
        stopTTS(ctx);

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

    /** Launch from library / mode dialog / ExtUtils — no DocumentController. */
    public static void launch(Context ctx, String path, int page, int parag,
                              int width, int height, int font) {
        stopTTS(ctx);
        Intent i = new Intent(ctx, EMBReaderActivity.class);
        i.putExtra(EXTRA_PATH,   path);
        i.putExtra(EXTRA_PAGE,   page);
        i.putExtra(EXTRA_PARAG,  parag);
        i.putExtra(EXTRA_WIDTH,  width);
        i.putExtra(EXTRA_HEIGHT, height);
        i.putExtra(EXTRA_FONT,   font);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    private static void stopTTS(Context ctx) {
        try {
            ctx.startService(new Intent(TTSNotification.TTS_STOP_DESTROY,
                    null, ctx, TTSService.class));
        } catch (Exception ignored) {}
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

    @Override protected void onResume() { super.onResume(); isActive = true; }

    @Override
    protected void onPause() {
        super.onPause();
        isActive = false;
        if (synthesisQueue != null && isPlaying) {
            synthesisQueue.pause();
            isPlaying = false;
            updatePlayIcon();
        }
    }

    @Override
    protected void onDestroy() {
        isDestroyed = true;
        isActive    = false;
        if (synthesisQueue != null) synthesisQueue.stop();
        if (webView != null) {
            ViewGroup p = (ViewGroup) webView.getParent();
            if (p != null) p.removeView(webView);
            webView.destroy();
        }
        super.onDestroy();
    }

    /**
     * Override back button: reset readingMode so VerticalViewActivity doesn't
     * re-trigger an EMB launch when it resumes.
     */
    @Override
    public void onBackPressed() {
        finishEMB();
    }

    /** Finish this activity and reset readingMode to avoid re-launch loops. */
    private void finishEMB() {
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
    // WebView — single tap = highlight, double tap = play from here
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

        // GestureDetector for single vs double tap on the WebView
        gestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                // JS getSentenceAt fires via click listener injected after page load
                return false; // let the click propagate to WebView's JS handler
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                // Double tap: JS returns the sentence index, then we play from there
                final float x = e.getX(), y = e.getY();
                webView.evaluateJavascript(
                    "getSentenceAt(" + x + "," + y + ")",
                    value -> {
                        try {
                            int idx = Integer.parseInt(value.trim());
                            if (idx >= 0) {
                                mainHandler.post(() -> {
                                    if (!isDestroyed) seekAndPlay(idx);
                                });
                            }
                        } catch (NumberFormatException ignored) {}
                    });
                return true;
            }
        });

        webView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return false; // still let WebView handle scrolling/clicks
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (isDestroyed || pageLoaded) return;
                pageLoaded = true;

                // Single-tap listener: highlights sentence, does NOT start playback
                webView.evaluateJavascript(
                    "(function(){" +
                    "document.addEventListener('click',function(e){" +
                    "var i=getSentenceAt(e.clientX,e.clientY);" +
                    "if(i>=0){EMB.onSingleTap(i);}" +
                    "});" +
                    "})();", null);

                // Restore saved position — highlight only, no play
                int from = getIntent().getIntExtra(EXTRA_PARAG, 0);
                // Re-read from AppSP in case it was updated since the intent was created
                int saved = AppSP.get().lastBookParagraph;
                if (saved > 0) from = saved;
                if (chapter != null && !chapter.sentences.isEmpty()) {
                    from = Math.max(0, Math.min(from, chapter.sentences.size() - 1));
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
            if (isPlaying) seekAndPlay(t);
            else           justHighlight(t);
        });

        btnNext.setOnClickListener(v -> {
            if (chapter == null) return;
            int t = Math.min(chapter.sentences.size() - 1, currentSentenceIdx + 1);
            if (isPlaying) seekAndPlay(t);
            else           justHighlight(t);
        });

        btnPlayPause.setOnClickListener(v -> {
            if (isPlaying) pausePlayback();
            else           startOrResume();
        });

        btnClose.setOnClickListener(v -> finishEMB());

        btnSettings.setOnClickListener(v -> openSettings());
    }

    // -------------------------------------------------------------------------
    // Settings — uses DragingDialogs.dialogTextToSpeech with the layout anchor
    // -------------------------------------------------------------------------

    private void openSettings() {
        DocumentController dc = lastDC;
        if (dc == null) {
            Toast.makeText(this, "Open a book first to access full TTS settings",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        // dialogAnchor's parent is the root RelativeLayout (a View) ✓
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
                dc = ImageExtractor.singleCodecContext(path, "");
                if (dc == null) { postError("Cannot open: " + path); return; }
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
                postError("Error: " + e.getMessage());
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
        mainHandler.post(() -> {
            if (!isDestroyed) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); finish(); }
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

    private void pausePlayback() {
        if (synthesisQueue != null) synthesisQueue.pause();
        isPlaying = false;
        updatePlayIcon();
    }

    /** Highlight sentence without starting or changing playback state. */
    private void justHighlight(int idx) {
        if (chapter == null || idx < 0 || idx >= chapter.sentences.size()) return;
        currentSentenceIdx = idx;
        webView.evaluateJavascript("highlightSentence(" + idx + ")", null);
        updateProgress(idx);
    }

    /** Move to sentence and immediately (re)start playback. */
    private void seekAndPlay(int idx) {
        if (chapter == null || idx < 0 || idx >= chapter.sentences.size()) return;
        currentSentenceIdx = idx;
        webView.evaluateJavascript("highlightSentence(" + idx + ")", null);
        updateProgress(idx);

        // Stop existing queue and start fresh from this index
        if (synthesisQueue != null) {
            synthesisQueue.stop();
            synthesisQueue = null;
        }
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
                LOG.e(e, TAG, "sentence error at", idx);
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
        /** Called from the single-tap JS listener — highlight only. */
        @JavascriptInterface
        public void onSingleTap(final int idx) {
            mainHandler.post(() -> { if (!isDestroyed) justHighlight(idx); });
        }
    }
}
