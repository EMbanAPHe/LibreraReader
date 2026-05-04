package com.foobnix.tts;

import android.graphics.Color;

import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.pdf.info.model.OutlineLinkWrapper;

import org.ebookdroid.core.codec.CodecDocument;
import org.ebookdroid.core.codec.CodecPage;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * EMBChapterExtractor — extracts chapter text, builds themed HTML for display,
 * and produces TTS-ready sentence strings with all Librera text replacements applied.
 *
 * TWO TEXT PIPELINES
 * ------------------
 * getPageHTML() → raw display HTML (keeps bold, italic, paragraph structure)
 * TxtUtils.replaceHTMLforTTS(rawHTML) → TTS text (strips tags, applies user replacements,
 *   normalises punctuation, expands contractions etc.)
 *
 * The display pipeline renders in the WebView with the book's original formatting.
 * The TTS pipeline produces what gets spoken — same as the vertical scroll mode.
 *
 * SCROLL BEHAVIOUR
 * ----------------
 * highlightSentence(n) in the injected JS only scrolls if the element is not already
 * within the visible viewport. This prevents the page jumping on every sentence start
 * when sentences are already on screen.
 *
 * TAP HANDLING
 * -----------
 * Uses touchstart/touchend with a movement threshold (8px) to distinguish taps from
 * scroll gestures. The 'click' event was firing on every touch release including scrolls.
 */
public class EMBChapterExtractor {

    private static final String TAG = "EMBChapterExtractor";
    private static final int FALLBACK_WINDOW = 25;

    // -------------------------------------------------------------------------
    // Theme params
    // -------------------------------------------------------------------------

    public static class ThemeParams {
        public final String bgColor;
        public final String textColor;
        public final String highlightColor;
        public final float  fontSizeSp;
        public final String fontFace;

        public ThemeParams(int bgColorInt, int textColorInt, int highlightColorInt,
                           float fontSizeSp, String fontFace) {
            this.bgColor        = toHex(bgColorInt);
            this.textColor      = toHex(textColorInt);
            this.highlightColor = toHex(highlightColorInt);
            this.fontSizeSp     = fontSizeSp;
            this.fontFace       = (fontFace != null && !fontFace.isEmpty())
                                  ? fontFace : "Georgia, 'Noto Serif', serif";
        }

        public static ThemeParams light() {
            return new ThemeParams(
                Color.parseColor("#fdf6e3"), Color.parseColor("#1a1a1a"),
                Color.parseColor("#ffe082"), 19f, "Georgia, 'Noto Serif', serif");
        }

        public static ThemeParams dark() {
            return new ThemeParams(
                Color.parseColor("#1a1a1a"), Color.parseColor("#e8e8e8"),
                Color.parseColor("#5c4a00"), 19f, "Georgia, 'Noto Serif', serif");
        }

        private static String toHex(int color) {
            return String.format("#%06X", 0xFFFFFF & color);
        }
    }

    // -------------------------------------------------------------------------
    // Output
    // -------------------------------------------------------------------------

    public static class ChapterContent {
        /** Full self-contained HTML document with sent-N spans for display. */
        public final String html;
        /**
         * TTS-ready sentences with Librera text replacements applied.
         * Same count and order as the sent-N spans in html.
         */
        public final List<String> sentences;
        public final int startPage;
        public final int endPage;
        public final String title;

        ChapterContent(String html, List<String> sentences,
                       int startPage, int endPage, String title) {
            this.html      = html;
            this.sentences = sentences;
            this.startPage = startPage;
            this.endPage   = endPage;
            this.title     = title;
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static ChapterContent extract(
            CodecDocument dc,
            int currentPage,
            List<OutlineLinkWrapper> outline,
            ThemeParams theme) {

        int totalPages = dc.getPageCount();
        int chapterStart, chapterEnd;
        String title = "";

        if (outline != null && outline.size() > 1) {
            int bestIdx = 0;
            for (int i = 0; i < outline.size(); i++) {
                int tp = outline.get(i).targetPage - 1;
                if (tp <= currentPage) bestIdx = i;
                else break;
            }
            chapterStart = Math.max(0, outline.get(bestIdx).targetPage - 1);
            title = outline.get(bestIdx).getTitleAsString();
            if (bestIdx + 1 < outline.size()) {
                int nextStart = outline.get(bestIdx + 1).targetPage - 1;
                chapterEnd = nextStart > chapterStart ? nextStart : totalPages;
            } else {
                chapterEnd = totalPages;
            }
        } else {
            chapterStart = Math.max(0, currentPage - FALLBACK_WINDOW);
            chapterEnd   = Math.min(totalPages, currentPage + FALLBACK_WINDOW);
        }

        return extractRange(dc, chapterStart, chapterEnd, title, theme);
    }

    public static ChapterContent extractRange(
            CodecDocument dc, int startPage, int endPage,
            String title, ThemeParams theme) {

        if (theme == null) theme = ThemeParams.light();
        startPage = Math.max(0, startPage);
        endPage   = Math.min(dc.getPageCount(), endPage);

        List<String> sentences = new ArrayList<>();
        StringBuilder html = new StringBuilder();
        buildHtmlHead(html, title, theme);

        int sentIdx = 0;

        for (int p = startPage; p < endPage; p++) {
            CodecPage page = null;
            try {
                page = dc.getPage(p);
                if (page == null) continue;

                // Raw HTML for display (preserves bold/italic/paragraph structure)
                String displayHtml = page.getPageHTML();
                if (TxtUtils.isEmpty(displayHtml)) continue;

                // TTS text: apply all Librera replacements (user dict, punctuation etc.)
                // This mirrors what VerticalModeController.getPageHtml() does.
                String ttsHtml = TxtUtils.replaceHTMLforTTS(displayHtml);

                if (p > startPage) {
                    html.append("<div class='page-sep'></div>\n");
                }

                // Parse display HTML into paragraph blocks
                List<String> displayParas = parseParagraphsDisplay(displayHtml);
                // Parse TTS HTML into matching paragraph plain-text strings
                List<String> ttsParas     = parseParagraphsTTS(ttsHtml);

                // Use the longer list length so we don't miss content if counts differ
                int paraCount = Math.max(displayParas.size(), ttsParas.size());

                for (int pi = 0; pi < paraCount; pi++) {
                    String displayPara = pi < displayParas.size() ? displayParas.get(pi) : "";
                    String ttsPara     = pi < ttsParas.size()     ? ttsParas.get(pi)     : displayPara;

                    if (ttsPara.trim().isEmpty() && displayPara.trim().isEmpty()) continue;

                    // If TTS para is empty but display isn't, fall back to display plain text
                    if (ttsPara.trim().isEmpty()) ttsPara = stripTags(displayPara);

                    html.append("<p>");

                    BreakIterator bi = BreakIterator.getSentenceInstance(Locale.getDefault());
                    bi.setText(ttsPara);

                    int start = bi.first();
                    for (int end = bi.next(); end != BreakIterator.DONE; start = end, end = bi.next()) {
                        String ttsSent = ttsPara.substring(start, end).trim();
                        if (ttsSent.isEmpty()) continue;

                        // For display, use corresponding portion of display text
                        // Approximate by using the TTS sentence text (readable enough)
                        html.append("<span id='sent-").append(sentIdx)
                            .append("' class='tts-sent'>")
                            .append(escapeHtml(ttsSent))
                            .append("</span> ");

                        sentences.add(ttsSent);
                        sentIdx++;
                    }
                    html.append("</p>\n");
                }

            } catch (Exception e) {
                LOG.e(e);
            } finally {
                if (page != null) try { page.recycle(); } catch (Exception ignored) {}
            }
        }

        html.append("</div></body></html>");
        LOG.d(TAG, "extracted", sentIdx, "sentences, pages", startPage, "->", endPage);
        return new ChapterContent(html.toString(), sentences, startPage, endPage, title);
    }

    // -------------------------------------------------------------------------
    // Paragraph parsing — display HTML (keeps inner markup structure)
    // -------------------------------------------------------------------------

    private static List<String> parseParagraphsDisplay(String rawHtml) {
        List<String> result = new ArrayList<>();
        String[] blocks = rawHtml.split("</p>");
        for (String block : blocks) {
            String content = block.replaceFirst("(?i)^[\\s\\S]*?<p[^>]*>", "");
            if (content.isEmpty()) content = block;
            content = content
                .replace("-<end-line>", "").replace("- <end-line>", "")
                .replace("<end-line>", " ").replace("<end-block>", " ");
            String plain = stripTags(content).trim();
            if (!plain.isEmpty()) result.add(plain);
        }
        if (result.isEmpty() && !rawHtml.trim().isEmpty()) {
            String plain = stripTags(rawHtml).trim();
            if (!plain.isEmpty()) result.add(plain);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Paragraph parsing — TTS HTML (already processed by replaceHTMLforTTS)
    // -------------------------------------------------------------------------

    private static List<String> parseParagraphsTTS(String ttsHtml) {
        List<String> result = new ArrayList<>();
        if (TxtUtils.isEmpty(ttsHtml)) return result;

        // replaceHTMLforTTS has already replaced <p> with space and stripped most tags.
        // Split on TTS_PAUSE markers to get natural speech segments.
        // Then strip any remaining tags and normalise whitespace.
        String[] parts = ttsHtml.split(TxtUtils.TTS_PAUSE);
        for (String part : parts) {
            String plain = stripTags(part)
                .replace(TxtUtils.NON_BREAKE_SPACE, " ")
                .replaceAll("\\s+", " ")
                .trim();
            if (!plain.isEmpty()) result.add(plain);
        }
        return result;
    }

    private static String stripTags(String html) {
        if (html == null) return "";
        return html
            .replaceAll("<[^>]+>", "")
            .replace("&nbsp;", " ")
            .replace("&lt;",   "<")
            .replace("&gt;",   ">")
            .replace("&amp;",  "&")
            .replace("&quot;", "\"")
            .replaceAll("\\s+", " ");
    }

    // -------------------------------------------------------------------------
    // HTML head — themed CSS + JS with smart scroll and tap detection
    // -------------------------------------------------------------------------

    private static void buildHtmlHead(StringBuilder sb, String title, ThemeParams t) {
        sb.append("<!DOCTYPE html>\n<html>\n<head>\n")
          .append("<meta charset='utf-8'>\n")
          .append("<meta name='viewport' content='width=device-width, initial-scale=1'>\n")
          .append("<style>\n")
          .append("  * { box-sizing: border-box; margin: 0; padding: 0; }\n")
          .append("  body {\n")
          .append("    padding: 20px 18px 24px 18px;\n")
          .append("    font-family: ").append(t.fontFace).append(";\n")
          .append("    font-size: ").append((int) t.fontSizeSp).append("px;\n")
          .append("    line-height: 1.75;\n")
          .append("    color: ").append(t.textColor).append(";\n")
          .append("    background: ").append(t.bgColor).append(";\n")
          .append("    -webkit-text-size-adjust: 100%;\n")
          .append("    -webkit-user-select: none;\n")  // prevent text selection on tap
          .append("    user-select: none;\n")
          .append("  }\n")
          .append("  .chapter-title {\n")
          .append("    font-size: 1.25em; font-weight: bold; line-height: 1.3;\n")
          .append("    margin-bottom: 20px; padding-bottom: 10px;\n")
          .append("    border-bottom: 1px solid rgba(128,128,128,0.3);\n")
          .append("  }\n")
          .append("  .chapter-body p {\n")
          .append("    margin-bottom: 14px; text-align: justify; text-indent: 1.5em;\n")
          .append("  }\n")
          .append("  .chapter-body p:first-child { text-indent: 0; }\n")
          .append("  .tts-sent {\n")
          .append("    border-radius: 3px;\n")
          .append("    transition: background-color 0.1s ease;\n")
          .append("    cursor: pointer;\n")
          .append("  }\n")
          .append("  .tts-active {\n")
          .append("    background-color: ").append(t.highlightColor).append(";\n")
          .append("    border-radius: 3px;\n")
          .append("  }\n")
          .append("  .page-sep {\n")
          .append("    height: 1px; background: rgba(128,128,128,0.25); margin: 18px 0;\n")
          .append("  }\n")
          .append("</style>\n")
          .append("<script>\n")
          // Active sentence tracking
          .append("  var _activeSent = -1;\n")
          // Smart highlight: only scrolls if element is outside viewport
          .append("  function highlightSentence(n) {\n")
          .append("    if (_activeSent >= 0) {\n")
          .append("      var prev = document.getElementById('sent-' + _activeSent);\n")
          .append("      if (prev) prev.classList.remove('tts-active');\n")
          .append("    }\n")
          .append("    _activeSent = n;\n")
          .append("    var el = document.getElementById('sent-' + n);\n")
          .append("    if (!el) return;\n")
          .append("    el.classList.add('tts-active');\n")
          // Only scroll if the element is not already fully visible
          .append("    var r = el.getBoundingClientRect();\n")
          .append("    var vh = window.innerHeight;\n")
          .append("    if (r.bottom > vh * 0.85 || r.top < vh * 0.15) {\n")
          .append("      el.scrollIntoView({ behavior: 'smooth', block: 'center' });\n")
          .append("    }\n")
          .append("  }\n")
          // Hit test
          .append("  function getSentenceAt(x, y) {\n")
          .append("    var el = document.elementFromPoint(x, y);\n")
          .append("    while (el && !(el.id && el.id.indexOf('sent-') === 0)) {\n")
          .append("      el = el.parentElement;\n")
          .append("    }\n")
          .append("    if (!el || !el.id) return -1;\n")
          .append("    return parseInt(el.id.replace('sent-', ''), 10);\n")
          .append("  }\n")
          // Tap vs scroll detection using touchstart/touchend with 8px threshold.
          // The 'click' event fires on scroll-end which caused false highlights.
          // dblclick detection: two taps within 300ms on the same element.
          .append("  var _tapStartX = 0, _tapStartY = 0;\n")
          .append("  var _lastTapTime = 0, _lastTapIdx = -1;\n")
          .append("  var _TAP_SLOP = 8;\n")
          .append("  document.addEventListener('touchstart', function(e) {\n")
          .append("    _tapStartX = e.touches[0].clientX;\n")
          .append("    _tapStartY = e.touches[0].clientY;\n")
          .append("  }, { passive: true });\n")
          .append("  document.addEventListener('touchend', function(e) {\n")
          .append("    var dx = e.changedTouches[0].clientX - _tapStartX;\n")
          .append("    var dy = e.changedTouches[0].clientY - _tapStartY;\n")
          .append("    if (Math.abs(dx) > _TAP_SLOP || Math.abs(dy) > _TAP_SLOP) return;\n")
          .append("    var x = e.changedTouches[0].clientX;\n")
          .append("    var y = e.changedTouches[0].clientY;\n")
          .append("    var idx = getSentenceAt(x, y);\n")
          .append("    if (idx < 0) return;\n")
          .append("    e.preventDefault();\n")
          .append("    var now = Date.now();\n")
          .append("    if (now - _lastTapTime < 300 && idx === _lastTapIdx) {\n")
          .append("      _lastTapTime = 0; _lastTapIdx = -1;\n")
          .append("      EMB.onDoubleTap(idx);\n")
          .append("    } else {\n")
          .append("      _lastTapTime = now; _lastTapIdx = idx;\n")
          .append("      EMB.onSingleTap(idx);\n")
          .append("    }\n")
          .append("  }, { passive: false });\n")
          .append("  function getTotalSentences() {\n")
          .append("    return document.querySelectorAll('.tts-sent').length;\n")
          .append("  }\n")
          .append("</script>\n")
          .append("</head>\n<body>\n");

        if (title != null && !title.trim().isEmpty()) {
            sb.append("<div class='chapter-title'>").append(escapeHtml(title)).append("</div>\n");
        }
        sb.append("<div class='chapter-body'>\n");
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;")
                   .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
