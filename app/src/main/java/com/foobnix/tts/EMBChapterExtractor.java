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
 * EMBChapterExtractor — extracts chapter text and builds a themed, self-contained
 * HTML document for EMBReaderActivity's WebView.
 *
 * CHAPTER BOUNDARY DETECTION
 * --------------------------
 * Uses the caller-supplied List<OutlineLinkWrapper> (from dc.getOutline()) to find
 * the page range for the chapter that contains currentPage. OutlineLinkWrapper.targetPage
 * is 1-indexed. We find the entry whose targetPage <= currentPage+1, and the next
 * entry gives the exclusive end.
 *
 * Fallback when outline is absent or sparse: a FALLBACK_WINDOW page window.
 *
 * HTML / SENTENCE STRUCTURE
 * -------------------------
 * Each sentence is wrapped in:
 *   <span id="sent-N" class="tts-sent">text</span>
 *
 * JS functions injected inline:
 *   highlightSentence(n)  — add .tts-active, smooth scroll into view
 *   getSentenceAt(x, y)   — hit-test a tap coordinate → sent index
 *   getTotalSentences()   — returns sentence count (Java sanity check)
 *
 * THEME
 * -----
 * Call extract() or extractRange() with a ThemeParams. EMBReaderActivity
 * reads the live AppState/BookCSS values and passes them here.
 */
public class EMBChapterExtractor {

    private static final String TAG = "EMBChapterExtractor";
    private static final int FALLBACK_WINDOW = 25;

    // -------------------------------------------------------------------------
    // Theme params
    // -------------------------------------------------------------------------

    public static class ThemeParams {
        public final String bgColor;       // CSS hex e.g. "#1a1a1a"
        public final String textColor;     // CSS hex e.g. "#f0f0f0"
        public final String highlightColor;// CSS hex e.g. "#5c4a00"
        public final String barBgColor;    // control bar background
        public final float fontSizeSp;     // body font size
        public final String fontFace;      // font-family CSS value

        public ThemeParams(int bgColorInt, int textColorInt, int highlightColorInt,
                           float fontSizeSp, String fontFace) {
            this.bgColor        = toHex(bgColorInt);
            this.textColor      = toHex(textColorInt);
            this.highlightColor = toHex(highlightColorInt);
            // Control bar is always dark regardless of theme
            this.barBgColor     = "#CC1a1a1a";
            this.fontSizeSp     = fontSizeSp;
            this.fontFace       = (fontFace != null && !fontFace.isEmpty())
                                  ? fontFace : "Georgia, 'Noto Serif', serif";
        }

        /** Default light theme — used when AppState is not available. */
        public static ThemeParams light() {
            return new ThemeParams(
                Color.parseColor("#fdf6e3"),
                Color.parseColor("#1a1a1a"),
                Color.parseColor("#ffe082"),
                19f, "Georgia, 'Noto Serif', serif");
        }

        /** Default dark theme. */
        public static ThemeParams dark() {
            return new ThemeParams(
                Color.parseColor("#1a1a1a"),
                Color.parseColor("#e8e8e8"),
                Color.parseColor("#5c4a00"),
                19f, "Georgia, 'Noto Serif', serif");
        }

        private static String toHex(int color) {
            return String.format("#%06X", 0xFFFFFF & color);
        }
    }

    // -------------------------------------------------------------------------
    // Output
    // -------------------------------------------------------------------------

    public static class ChapterContent {
        /** Full self-contained HTML document. */
        public final String html;
        /** Plain-text sentences, same order as sent-N spans. */
        public final List<String> sentences;
        /** First page of extracted range (0-indexed, inclusive). */
        public final int startPage;
        /** Last page of extracted range (0-indexed, exclusive). */
        public final int endPage;
        /** Chapter title from outline, or "". */
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

    /**
     * Extract chapter content using automatic boundary detection.
     *
     * @param dc          open CodecDocument (caller recycles it)
     * @param currentPage 0-indexed current page
     * @param outline     loaded outline wrappers (may be null/empty)
     * @param theme       visual theme params
     */
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
                int tp = outline.get(i).targetPage - 1; // to 0-indexed
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
            LOG.d(TAG, "chapter pages:", chapterStart, "->", chapterEnd, "|", title);
        } else {
            chapterStart = Math.max(0, currentPage - FALLBACK_WINDOW);
            chapterEnd   = Math.min(totalPages, currentPage + FALLBACK_WINDOW);
        }

        return extractRange(dc, chapterStart, chapterEnd, title, theme);
    }

    /** Extract an explicit page range — used by the manual chapter picker. */
    public static ChapterContent extractRange(
            CodecDocument dc, int startPage, int endPage,
            String title, ThemeParams theme) {

        if (theme == null) theme = ThemeParams.light();
        int totalPages = dc.getPageCount();
        startPage = Math.max(0, startPage);
        endPage   = Math.min(totalPages, endPage);

        List<String> sentences = new ArrayList<>();
        StringBuilder html = new StringBuilder();

        buildHtmlHead(html, title, theme);

        int sentIdx = 0;

        for (int p = startPage; p < endPage; p++) {
            CodecPage page = null;
            try {
                page = dc.getPage(p);
                if (page == null) continue;

                String rawHtml = page.getPageHTML();
                if (TxtUtils.isEmpty(rawHtml)) continue;

                if (p > startPage) {
                    html.append("<div class='page-sep'></div>\n");
                }

                List<String> paragraphs = parseParagraphs(rawHtml);

                for (String paraPlain : paragraphs) {
                    if (paraPlain.trim().isEmpty()) continue;

                    html.append("<p>");
                    BreakIterator bi = BreakIterator.getSentenceInstance(Locale.getDefault());
                    bi.setText(paraPlain);

                    int start = bi.first();
                    for (int end = bi.next(); end != BreakIterator.DONE; start = end, end = bi.next()) {
                        String sent = paraPlain.substring(start, end).trim();
                        if (sent.isEmpty()) continue;

                        html.append("<span id='sent-").append(sentIdx)
                            .append("' class='tts-sent'>")
                            .append(escapeHtml(sent))
                            .append("</span> ");

                        sentences.add(sent);
                        sentIdx++;
                    }
                    html.append("</p>\n");
                }

            } catch (Exception e) {
                LOG.e(e);
            } finally {
                if (page != null) {
                    try { page.recycle(); } catch (Exception ignored) {}
                }
            }
        }

        html.append("</div></body></html>");

        LOG.d(TAG, "extracted", sentIdx, "sentences, pages", startPage, "->", endPage);
        return new ChapterContent(html.toString(), sentences, startPage, endPage, title);
    }

    // -------------------------------------------------------------------------
    // Paragraph parsing
    // -------------------------------------------------------------------------

    private static List<String> parseParagraphs(String rawHtml) {
        List<String> result = new ArrayList<>();
        String[] blocks = rawHtml.split("</p>");

        for (String block : blocks) {
            String content = block.replaceFirst("(?i)^[\\s\\S]*?<p>", "");
            if (content.isEmpty()) content = block;

            content = content
                .replace("-<end-line>", "")
                .replace("- <end-line>", "")
                .replace("<end-line>", " ")
                .replace("<end-block>", " ");

            String plain = content
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&lt;",   "<")
                .replace("&gt;",   ">")
                .replace("&amp;",  "&")
                .replace("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();

            if (!plain.isEmpty()) result.add(plain);
        }

        // Fallback: whole page as one block
        if (result.isEmpty() && !rawHtml.trim().isEmpty()) {
            String plain = rawHtml
                .replace("-<end-line>", "")
                .replace("<end-line>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
            if (!plain.isEmpty()) result.add(plain);
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // HTML head + CSS + JS
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
          .append("  }\n")
          .append("  .chapter-title {\n")
          .append("    font-size: 1.25em;\n")
          .append("    font-weight: bold;\n")
          .append("    line-height: 1.3;\n")
          .append("    margin-bottom: 20px;\n")
          .append("    padding-bottom: 10px;\n")
          .append("    border-bottom: 1px solid rgba(128,128,128,0.3);\n")
          .append("  }\n")
          .append("  .chapter-body p {\n")
          .append("    margin-bottom: 14px;\n")
          .append("    text-align: justify;\n")
          .append("    text-indent: 1.5em;\n")
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
          .append("    height: 1px;\n")
          .append("    background: rgba(128,128,128,0.25);\n")
          .append("    margin: 18px 0;\n")
          .append("  }\n")
          .append("</style>\n")
          // Inline JS
          .append("<script>\n")
          .append("  var _activeSent = -1;\n")
          .append("  function highlightSentence(n) {\n")
          .append("    if (_activeSent >= 0) {\n")
          .append("      var prev = document.getElementById('sent-' + _activeSent);\n")
          .append("      if (prev) prev.classList.remove('tts-active');\n")
          .append("    }\n")
          .append("    _activeSent = n;\n")
          .append("    var el = document.getElementById('sent-' + n);\n")
          .append("    if (el) {\n")
          .append("      el.classList.add('tts-active');\n")
          .append("      el.scrollIntoView({ behavior: 'smooth', block: 'center' });\n")
          .append("    }\n")
          .append("  }\n")
          .append("  function getSentenceAt(x, y) {\n")
          .append("    var el = document.elementFromPoint(x, y);\n")
          .append("    while (el && !(el.id && el.id.indexOf('sent-') === 0)) {\n")
          .append("      el = el.parentElement;\n")
          .append("    }\n")
          .append("    if (!el || !el.id) return -1;\n")
          .append("    return parseInt(el.id.replace('sent-', ''), 10);\n")
          .append("  }\n")
          .append("  function getTotalSentences() {\n")
          .append("    return document.querySelectorAll('.tts-sent').length;\n")
          .append("  }\n")
          .append("</script>\n")
          .append("</head>\n<body>\n");

        if (title != null && !title.trim().isEmpty()) {
            sb.append("<div class='chapter-title'>")
              .append(escapeHtml(title))
              .append("</div>\n");
        }

        sb.append("<div class='chapter-body'>\n");
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}
