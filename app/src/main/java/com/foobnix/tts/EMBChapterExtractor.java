package com.foobnix.tts;

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
 * EMBChapterExtractor — extracts chapter text and builds a self-contained HTML
 * document for EMBReaderActivity's WebView.
 *
 * CHAPTER BOUNDARY DETECTION
 * --------------------------
 * Uses dc.getOutline() (via the caller-supplied List<OutlineLinkWrapper>) to find
 * the page range for the chapter containing currentPage. Each OutlineLinkWrapper
 * has a targetPage (1-indexed). We find the entry whose targetPage <= currentPage+1
 * and the next entry gives the exclusive end.
 *
 * Fallback (outline absent or sparse): a FALLBACK_WINDOW page window centred on
 * the current page. The manual override path passes an explicit [startPage, endPage)
 * from the chapter picker dialog (see EMBReaderActivity).
 *
 * HTML ASSEMBLY
 * -------------
 * 1. For each page in the range: dc.getPage(p).getPageHTML() → raw MuPDF HTML.
 * 2. Parse raw HTML into paragraph blocks (split on <p> boundaries).
 * 3. For each paragraph: strip tags → plain text → BreakIterator sentences.
 * 4. Each sentence becomes:
 *      <span id="sent-N" class="tts-sent">escaped text</span>
 * 5. Sentences and their span IDs are the shared index space used by both the
 *    WebView JS highlight API and SynthesisQueue's callback index.
 *
 * OUTPUT
 * ------
 * ChapterContent.html     — full self-contained HTML (CSS + JS + body)
 * ChapterContent.sentences — List<String> of plain-text sentences, same order as spans
 * ChapterContent.startPage / endPage — 0-indexed, [start, end)
 * ChapterContent.title    — chapter title from outline entry, or ""
 */
public class EMBChapterExtractor {

    private static final String TAG = "EMBChapterExtractor";

    /** Used when the outline is absent or has only one entry. */
    private static final int FALLBACK_WINDOW = 25;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static class ChapterContent {
        /** Full HTML document with sent-N spans and inline CSS/JS. */
        public final String html;
        /** Plain-text sentences in the same order as the sent-N spans. For TTS. */
        public final List<String> sentences;
        /** First page of the extracted range (0-indexed, inclusive). */
        public final int startPage;
        /** Last page of the extracted range (0-indexed, exclusive). */
        public final int endPage;
        /** Chapter title from the outline entry, or "" if unknown. */
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

    /**
     * Extract chapter content using automatic boundary detection.
     *
     * @param dc          open CodecDocument (caller is responsible for recycle)
     * @param currentPage 0-indexed current page in the document
     * @param outline     loaded outline wrappers (may be null or empty)
     */
    public static ChapterContent extract(
            CodecDocument dc,
            int currentPage,
            List<OutlineLinkWrapper> outline) {

        int totalPages = dc.getPageCount();
        int chapterStart;
        int chapterEnd;
        String title = "";

        if (outline != null && outline.size() > 1) {
            // Find outline entry containing currentPage (targetPage is 1-indexed)
            int bestIdx = 0;
            for (int i = 0; i < outline.size(); i++) {
                int tp = outline.get(i).targetPage - 1; // to 0-indexed
                if (tp <= currentPage) {
                    bestIdx = i;
                } else {
                    break;
                }
            }
            chapterStart = Math.max(0, outline.get(bestIdx).targetPage - 1);
            title = outline.get(bestIdx).getTitleAsString();

            // Next entry gives exclusive end; last entry → end of book
            if (bestIdx + 1 < outline.size()) {
                int nextStart = outline.get(bestIdx + 1).targetPage - 1;
                chapterEnd = (nextStart > chapterStart) ? nextStart : totalPages;
            } else {
                chapterEnd = totalPages;
            }
            LOG.d(TAG, "outline chapter:", chapterStart, "->", chapterEnd, "|", title);
        } else {
            // Fallback: symmetric window
            chapterStart = Math.max(0, currentPage - FALLBACK_WINDOW);
            chapterEnd   = Math.min(totalPages, currentPage + FALLBACK_WINDOW);
            LOG.d(TAG, "fallback window:", chapterStart, "->", chapterEnd);
        }

        return extractRange(dc, chapterStart, chapterEnd, title);
    }

    /**
     * Extract an explicit page range — used by the manual chapter picker.
     */
    public static ChapterContent extractRange(
            CodecDocument dc, int startPage, int endPage, String title) {

        int totalPages = dc.getPageCount();
        startPage = Math.max(0, startPage);
        endPage   = Math.min(totalPages, endPage);

        List<String> sentences = new ArrayList<>();
        StringBuilder html = new StringBuilder();

        buildHtmlHead(html, title);

        int sentIdx = 0;

        for (int p = startPage; p < endPage; p++) {
            CodecPage page = null;
            try {
                page = dc.getPage(p);
                if (page == null) continue;

                String rawHtml = page.getPageHTML();
                if (TxtUtils.isEmpty(rawHtml)) continue;

                // Visual separator between pages (subtle horizontal rule)
                if (p > startPage) {
                    html.append("<div class='page-sep'></div>\n");
                }

                // Parse this page's HTML into (plainText, innerHtml) paragraph pairs
                List<String[]> paragraphs = parseParagraphs(rawHtml);

                for (String[] para : paragraphs) {
                    String paraPlain = para[0];
                    if (paraPlain.trim().isEmpty()) continue;

                    html.append("<p>");

                    // Split paragraph plain text into sentences
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

        LOG.d(TAG, "extracted", sentIdx, "sentences across pages", startPage, "->", endPage);
        return new ChapterContent(html.toString(), sentences, startPage, endPage, title);
    }

    // -------------------------------------------------------------------------
    // HTML parsing
    // -------------------------------------------------------------------------

    /**
     * Parse a MuPDF page HTML string into paragraph plain-text strings.
     *
     * MuPDF EPUB HTML structure (typical):
     *   <p>Normal text <b>bold</b> more text<end-line>continued</p>
     *   <p>Next paragraph.</p>
     *
     * Strategy:
     *   1. Split on </p> to get paragraph blocks.
     *   2. Strip all tags → plain text for BreakIterator.
     *   3. Clean up hyphenation artifacts and whitespace.
     *
     * Returns list of [plainText] strings. One entry per non-empty paragraph.
     */
    private static List<String[]> parseParagraphs(String rawHtml) {
        List<String[]> result = new ArrayList<>();

        // Split on paragraph end tags (keep both <p>...</p> and bare text blocks)
        String[] blocks = rawHtml.split("</p>");

        for (String block : blocks) {
            // Strip opening <p> tag and any leading whitespace
            String content = block.replaceFirst("(?i)^[\\s\\S]*?<p>", "");
            if (content.isEmpty()) content = block;

            // Resolve soft-hyphens: "-<end-line>" means the word continues on next line
            content = content.replace("-<end-line>", "")
                             .replace("- <end-line>", "")
                             .replace("<end-line>", " ")
                             .replace("<end-block>", " ");

            // Strip all remaining HTML tags
            String plain = content
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&lt;",   "<")
                .replace("&gt;",   ">")
                .replace("&amp;",  "&")
                .replace("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();

            if (!plain.isEmpty()) {
                result.add(new String[]{plain});
            }
        }

        // Fallback: if no </p> found, treat whole page as one block
        if (result.isEmpty() && !rawHtml.trim().isEmpty()) {
            String plain = rawHtml
                .replace("-<end-line>", "").replace("<end-line>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
            if (!plain.isEmpty()) {
                result.add(new String[]{plain});
            }
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // HTML generation
    // -------------------------------------------------------------------------

    /**
     * Append the HTML head, CSS, and JS to the builder.
     * The body and chapter-body div are opened here; caller closes them.
     */
    private static void buildHtmlHead(StringBuilder sb, String title) {
        sb.append("<!DOCTYPE html>\n<html>\n<head>\n")
          .append("<meta charset='utf-8'>\n")
          .append("<meta name='viewport' content='width=device-width, initial-scale=1'>\n")
          .append("<style>\n")
          // Base typography — matches comfortable reading apps
          .append("  * { box-sizing: border-box; }\n")
          .append("  body {\n")
          .append("    margin: 0;\n")
          .append("    padding: 20px 18px 80px 18px;\n") // bottom pad clears TTS bar
          .append("    font-family: Georgia, 'Noto Serif', serif;\n")
          .append("    font-size: 19px;\n")
          .append("    line-height: 1.75;\n")
          .append("    color: #1a1a1a;\n")
          .append("    background: #fdf6e3;\n") // warm off-white, easy on eyes
          .append("    -webkit-text-size-adjust: 100%;\n")
          .append("  }\n")
          .append("  .chapter-title {\n")
          .append("    font-size: 1.35em;\n")
          .append("    font-weight: bold;\n")
          .append("    line-height: 1.3;\n")
          .append("    margin-bottom: 20px;\n")
          .append("    padding-bottom: 10px;\n")
          .append("    border-bottom: 1px solid #d4c5a9;\n")
          .append("    color: #333;\n")
          .append("  }\n")
          .append("  .chapter-body p {\n")
          .append("    margin: 0 0 14px 0;\n")
          .append("    text-align: justify;\n")
          .append("    text-indent: 1.5em;\n")
          .append("  }\n")
          .append("  .chapter-body p:first-child { text-indent: 0; }\n")
          // Sentence spans — invisible by default, highlighted when TTS is on that sentence
          .append("  .tts-sent {\n")
          .append("    border-radius: 3px;\n")
          .append("    transition: background-color 0.12s ease;\n")
          .append("    cursor: pointer;\n")
          .append("  }\n")
          .append("  .tts-active {\n")
          .append("    background-color: #ffe082;\n")
          .append("    border-radius: 3px;\n")
          .append("  }\n")
          // Subtle page separator between source pages
          .append("  .page-sep {\n")
          .append("    height: 1px;\n")
          .append("    background: #d4c5a9;\n")
          .append("    margin: 18px 0;\n")
          .append("    opacity: 0.5;\n")
          .append("  }\n")
          .append("</style>\n")
          // Inline JS — highlight API used by EMBReaderActivity via evaluateJavascript
          .append("<script>\n")
          // highlightSentence(n): activate span sent-n, deactivate previous, scroll to it
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
          // getSentenceAt(x, y): returns the sent-N index at touch coordinates, or -1
          .append("  function getSentenceAt(x, y) {\n")
          .append("    var el = document.elementFromPoint(x, y);\n")
          .append("    while (el && !(el.id && el.id.indexOf('sent-') === 0)) {\n")
          .append("      el = el.parentElement;\n")
          .append("    }\n")
          .append("    if (!el || !el.id) return -1;\n")
          .append("    return parseInt(el.id.replace('sent-', ''), 10);\n")
          .append("  }\n")
          // getTotalSentences(): lets Java verify sentence count after load
          .append("  function getTotalSentences() {\n")
          .append("    return document.querySelectorAll('.tts-sent').length;\n")
          .append("  }\n")
          .append("</script>\n")
          .append("</head>\n<body>\n");

        // Chapter title header (skipped if empty — fallback window case)
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
