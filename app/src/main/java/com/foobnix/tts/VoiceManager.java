package com.foobnix.tts;

import android.os.Handler;
import android.os.Looper;

import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.pdf.search.activity.PageImageState;
import com.foobnix.pdf.search.activity.msg.InvalidateMessage;

import org.ebookdroid.droids.mupdf.codec.TextWord;
import org.greenrobot.eventbus.EventBus;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VoiceManager — accurate TTS sentence building and highlighting.
 *
 * PROBLEM WITH THE OLD APPROACH
 * ------------------------------
 * The old system used dc.doSearch(queryText) to find and highlight the current
 * sentence. This had three unfixable problems:
 *
 *   1. Text mismatch: TTS replacements mutate the text (e.g. "n'"→" " makes
 *      "wasn't" → "was t"). Stripped for search: "wast" ≠ "wasnt" (page) → no match.
 *
 *   2. Wrong sentence on duplicates: a text search for "She was alone" matches the
 *      first occurrence on the page regardless of which one TTS is speaking.
 *
 *   3. Async race: doSearch runs on a background thread and posts InvalidateMessage
 *      when done. The next onStart event may arrive before the search finishes,
 *      leaving the old highlight visible or overwriting with the wrong result.
 *
 * THE NEW APPROACH
 * ----------------
 * 1. Build sentences from TextWord[][] (the rendering engine's word list).
 *    BreakIterator.getSentenceInstance() splits on actual sentence boundaries,
 *    not arbitrary TTS_PAUSE markers from HTML tag changes or triple-spaces.
 *
 * 2. Each Sentence stores its exact List<TextWord> — the same objects that
 *    EventDraw reads from page.selectedText. Highlighting is a direct assignment:
 *    page.selectedText = sentence.words — no text search, no async, no mismatch.
 *
 * 3. Tap-to-sentence uses the TextWord bounding boxes (0..1 normalized page coords)
 *    directly. The tap Y is converted to a page fraction and compared to each
 *    sentence's yCenter. Exact, no Y-fraction-of-view approximation.
 *
 * 4. TTS text: each sentence's speakText is built from the raw word strings
 *    (not from HTML), so it never has HTML artifacts. replaceHTMLforTTS is NOT
 *    called — instead we pass the raw sentence text to TTSEngine.speak directly.
 *    This means TTSEngine.speek() is bypassed for VoiceManager-driven playback;
 *    instead VoiceManager calls tts.speak() directly with per-sentence utterance IDs
 *    matching FINISHED_SIGNAL+index so onStart in TTSService fires correctly.
 *
 * COORDINATE SPACE
 * ----------------
 * TextWord extends RectF. For EPUB (text format), MuPDF returns coords in the
 * range [0..1] (normalized page space). For PDF the coords are in points (pt).
 * For EPUB, word.top ≈ 0.05 means 5% from the top of the page.
 * The tap hit-test uses tapY / viewHeight as the Y fraction, which matches.
 */
public class VoiceManager {

    private static final String TAG = "VoiceManager";

    // -------------------------------------------------------------------------
    // Sentence data model
    // -------------------------------------------------------------------------

    public static class Sentence {
        /** Position in the sentence list for this page. Used as TTS utterance index. */
        public final int index;
        /** Exact TextWord objects from page.texts. Assigned directly to page.selectedText. */
        public final List<TextWord> words;
        /** Raw text for TTS speech — joined word strings, no HTML, no replacements. */
        public final String speakText;
        /**
         * Vertical centre of this sentence in normalized page coords (0=top, 1=bottom).
         * Average of the first and last word's vertical centre.
         * Used for tap hit-testing: compare to tapY/viewHeight.
         */
        public final float yCenter;

        Sentence(int index, List<TextWord> words, String speakText, float yCenter) {
            this.index   = index;
            this.words   = words;
            this.speakText = speakText;
            this.yCenter = yCenter;
        }
    }

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private static final VoiceManager INSTANCE = new VoiceManager();
    public static VoiceManager get() { return INSTANCE; }
    private VoiceManager() {}

    /** Sentences for the most recently built page. */
    private List<Sentence> currentSentences = new ArrayList<>();
    /** The page number (1-indexed getCurentPage()) for which sentences were built. */
    private int builtForPage = -1;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    // Build sentences from TextWord[][]
    // -------------------------------------------------------------------------

    /**
     * Build (or return cached) sentence list for the current page.
     *
     * @param pageWords  TextWord[][] from dc.getPageWords()
     * @param pageNumber dc.getCurentPage() — used only for cache invalidation
     */
    public List<Sentence> getSentences(TextWord[][] pageWords, int pageNumber) {
        if (pageNumber == builtForPage && !currentSentences.isEmpty()) {
            return currentSentences;
        }
        currentSentences = buildSentences(pageWords);
        builtForPage = pageNumber;
        LOG.d(TAG, "built", currentSentences.size(), "sentences for page", pageNumber);
        return currentSentences;
    }

    /** Invalidate cache — call when the page changes. */
    public void invalidate() {
        builtForPage = -1;
    }

    /**
     * Build sentences from a page's TextWord[][] grid.
     *
     * Algorithm:
     * 1. Flatten TextWord[][] → ordered List<TextWord>, skipping nulls and empty words.
     * 2. Join word.w strings with spaces → plain text string with char-offset tracking.
     * 3. BreakIterator.getSentenceInstance() → sentence boundaries as char ranges.
     * 4. Map each char range back to the TextWord slice.
     * 5. Compute yCenter from the first and last word's bounding boxes.
     */
    static List<Sentence> buildSentences(TextWord[][] pageWords) {
        List<Sentence> result = new ArrayList<>();
        if (pageWords == null || pageWords.length == 0) return result;

        // Step 1 & 2: flatten and build joined text with offset tracking
        List<TextWord> flat = new ArrayList<>();
        List<Integer> charOffsets = new ArrayList<>(); // start char offset of each word in joined string
        StringBuilder sb = new StringBuilder();

        for (TextWord[] line : pageWords) {
            if (line == null) continue;
            for (TextWord word : line) {
                if (word == null || word.w == null || word.w.trim().isEmpty()) continue;
                charOffsets.add(sb.length());
                sb.append(word.w);
                sb.append(" ");
                flat.add(word);
            }
        }

        if (flat.isEmpty()) return result;

        String fullText = sb.toString();

        // Step 3: BreakIterator sentence boundaries
        BreakIterator bi = BreakIterator.getSentenceInstance(Locale.getDefault());
        bi.setText(fullText);

        int sentenceIndex = 0;
        int start = bi.first();
        for (int end = bi.next(); end != BreakIterator.DONE; start = end, end = bi.next()) {
            if (end <= start) continue;

            // Step 4: find which words fall within [start, end)
            List<TextWord> sentenceWords = new ArrayList<>();
            StringBuilder speakSb = new StringBuilder();

            for (int wi = 0; wi < flat.size(); wi++) {
                int wordStart = charOffsets.get(wi);
                int wordEnd   = wordStart + flat.get(wi).w.length();
                // Word is in this sentence if it overlaps [start, end)
                if (wordEnd > start && wordStart < end) {
                    sentenceWords.add(flat.get(wi));
                    if (speakSb.length() > 0) speakSb.append(" ");
                    speakSb.append(flat.get(wi).w);
                }
            }

            if (sentenceWords.isEmpty()) continue;

            // Step 5: yCenter from first and last word
            float yFirst = (sentenceWords.get(0).top + sentenceWords.get(0).bottom) / 2f;
            float yLast  = (sentenceWords.get(sentenceWords.size() - 1).top
                          + sentenceWords.get(sentenceWords.size() - 1).bottom) / 2f;
            float yCenter = (yFirst + yLast) / 2f;

            // Apply user TTS replacements to speakText only (not to word list)
            String raw = speakSb.toString().trim();
            String speakText = applyTtsReplacements(raw);

            if (!speakText.isEmpty()) {
                result.add(new Sentence(sentenceIndex++, sentenceWords, speakText, yCenter));
            }
        }

        return result;
    }

    /**
     * Apply the user's TTS text replacements to a plain string.
     * This is a simplified version that handles the most common case (regex patterns).
     * Does NOT insert TTS_PAUSE markers — sentence boundaries are already known.
     */
    private static String applyTtsReplacements(String text) {
        if (text == null || text.isEmpty()) return "";
        try {
            if (!com.foobnix.model.AppState.get().isEnalbeTTSReplacements) return text;
            // Apply system replacements from lineTTSReplacements3
            org.librera.LinkedJSONObject obj =
                    new org.librera.LinkedJSONObject(
                            com.foobnix.model.AppState.get().lineTTSReplacements3);
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = obj.optString(key);
                // Only apply non-TTS_PAUSE replacements
                if (!key.startsWith("<") || !key.endsWith(">")) {
                    try {
                        if (key.startsWith("*")) {
                            text = text.replaceAll(key.substring(1), value);
                        } else {
                            text = text.replace(key, value);
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return text.trim();
    }

    // -------------------------------------------------------------------------
    // Tap hit-test
    // -------------------------------------------------------------------------

    /**
     * Find the sentence index closest to the tap position.
     *
     * @param sentences   sentence list from getSentences()
     * @param tapYFraction  tapY / viewHeight (0=top, 1=bottom)
     * @return index into sentences, or 0 if list is empty
     */
    public static int findSentenceIndex(List<Sentence> sentences, float tapYFraction) {
        if (sentences == null || sentences.isEmpty()) return 0;

        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (Sentence s : sentences) {
            float dist = Math.abs(s.yCenter - tapYFraction);
            if (dist < bestDist) {
                bestDist = dist;
                best = s.index;
            }
        }
        LOG.d(TAG, "tap yFrac=", tapYFraction, "→ sentence", best,
              "yCenter=", sentences.get(Math.min(best, sentences.size()-1)).yCenter);
        return best;
    }

    // -------------------------------------------------------------------------
    // Best-match sentence lookup by tapped word
    // -------------------------------------------------------------------------

    /**
     * Find the sentence index containing a specific tapped word.
     *
     * Used by startTTSFromTap. The tapped word comes from AdvGuestureDetector
     * calling processLongTap(true, e, e, false) which uses the rendering engine's
     * exact TextWord bounding boxes — pixel-accurate and scroll-corrected.
     * This is coordinate-system independent (we compare strings, not positions).
     *
     * When the same word appears in multiple sentences (e.g. "the" appears everywhere),
     * yFractionHint picks the sentence whose yCenter is closest to tapY/viewHeight.
     * yCenter is in [0..1] page-normalized space; yFractionHint should also be in [0..1].
     *
     * Falls back to pure yFractionHint if no sentence contains the word.
     *
     * @param sentences       sentence list from getSentences()
     * @param tappedWord      the raw word string returned by processLongTap, lowercased
     *                        with punctuation stripped. May be null/empty.
     * @param yFractionHint   tapY / viewHeight as a rough position hint for tie-breaking
     */
    public static int findSentenceByWord(List<Sentence> sentences,
                                          String tappedWord,
                                          float yFractionHint) {
        if (sentences == null || sentences.isEmpty()) return 0;
        if (tappedWord == null || tappedWord.isEmpty()) {
            return findSentenceIndex(sentences, yFractionHint);
        }

        final String needle = tappedWord.toLowerCase().replaceAll("[^\\p{L}\\p{N}]", "");
        if (needle.isEmpty()) return findSentenceIndex(sentences, yFractionHint);

        // Find all candidate sentences that contain the tapped word
        List<Sentence> candidates = new java.util.ArrayList<>();
        for (Sentence s : sentences) {
            for (TextWord word : s.words) {
                String w = word.w.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase();
                if (w.equals(needle)) {
                    candidates.add(s);
                    break;
                }
            }
        }

        if (candidates.isEmpty()) {
            // Word not found (possibly from a hyphenated word or TTS artefact)
            // Fall back to Y-fraction
            return findSentenceIndex(sentences, yFractionHint);
        }

        if (candidates.size() == 1) {
            return candidates.get(0).index;
        }

        // Multiple sentences contain the word — pick the one closest to the tap Y
        Sentence best = candidates.get(0);
        float bestDist = Math.abs(best.yCenter - yFractionHint);
        for (int i = 1; i < candidates.size(); i++) {
            float dist = Math.abs(candidates.get(i).yCenter - yFractionHint);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidates.get(i);
            }
        }
        LOG.d(TAG, "findSentenceByWord", "word=", needle,
              "candidates=", candidates.size(), "→", best.index);
        return best.index;
    }



    /**
     * Find the sentence whose word content best matches the spoken text.
     *
     * WHY NOT USE paragIndex DIRECTLY
     * --------------------------------
     * event.paragIndex is an index into ttsPageParts[] — the array produced by
     * splitting the TTS-processed HTML at TTS_PAUSE markers (em-dashes, triple-spaces,
     * italic transitions, user replacements, etc.). pageSentences is built by
     * BreakIterator which splits on grammatical sentence boundaries. These two arrays
     * have different lengths and different content ordering, so paragIndex is NOT a
     * valid index into pageSentences.
     *
     * WORD OVERLAP MATCHING
     * ----------------------
     * Instead of index mapping, we compare the words of event.text (the actual text
     * being spoken) against the words of each sentence's TextWord list. The sentence
     * with the highest proportion of matching words wins.
     *
     * Handles TTS replacements ("wasn't" → "was t"): both "was" and "t" are in the
     * spoken text; "wasn't" stripped to "wasnt" is in the TextWord. We match "was"
     * (3 chars, significant) and the score is still high enough to identify the sentence.
     *
     * Handles duplicates: if the same phrase appears twice, use paragIndexFraction
     * (paragIndex / totalParags) as a Y-position hint to prefer the occurrence whose
     * yCenter is closer to that fraction.
     *
     * @param sentences        sentence list from getSentences()
     * @param spokenText       event.text — the TTS paragraph text being spoken
     * @param paragIndex       event.paragIndex — used only to break ties on duplicates
     * @param totalParags      total TTS paragraphs for this page (ttsPageParts.length)
     */
    public static Sentence findBestSentence(List<Sentence> sentences,
                                             String spokenText,
                                             int paragIndex,
                                             int totalParags) {
        if (sentences == null || sentences.isEmpty() || spokenText == null) return null;

        // Build word set from spoken text (words >= 3 chars to skip replacement artifacts)
        java.util.Set<String> spokenWords = new java.util.HashSet<>();
        for (String w : spokenText.split("\\s+")) {
            String s = w.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase();
            if (s.length() >= 3) spokenWords.add(s);
        }
        if (spokenWords.isEmpty()) {
            // Short sentence — fall back to Y-fraction estimate
            float yEst = (totalParags > 1) ? (float) paragIndex / (totalParags - 1) : 0.5f;
            return sentences.get(findSentenceIndex(sentences, yEst));
        }

        // Score each sentence by word overlap
        float bestScore = -1f;
        Sentence best = null;

        // Y estimate from paragIndex for tie-breaking
        float yEst = (totalParags > 1) ? (float) paragIndex / (totalParags - 1) : 0.5f;

        for (Sentence s : sentences) {
            if (s.words.isEmpty()) continue;

            int matches = 0;
            for (TextWord word : s.words) {
                String w = word.w.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase();
                if (w.length() >= 3 && spokenWords.contains(w)) matches++;
            }

            // Jaccard-style: matches / union
            int union = spokenWords.size() + s.words.size() - matches;
            float overlapScore = union > 0 ? (float) matches / union : 0f;

            // Add small bonus for Y-proximity to break ties on duplicate phrases
            float yBonus = 0.1f * (1f - Math.abs(s.yCenter - yEst));
            float totalScore = overlapScore + yBonus;

            if (totalScore > bestScore) {
                bestScore = totalScore;
                best = s;
            }
        }

        // Only use if meaningful match (>= 20% overlap); otherwise Y-fraction fallback
        if (best != null && bestScore >= 0.2f) {
            return best;
        }
        return sentences.get(findSentenceIndex(sentences, yEst));
    }



    /**
     * Highlight the sentence at the given index by directly assigning its TextWord
     * list to page.selectedText — no text search, no async, guaranteed correct.
     *
     * Must be called on the main thread (onStart fires on a background thread;
     * post to mainHandler before calling this).
     *
     * @param sentences  sentence list for the current page
     * @param index      sentence index from the TTS utterance ID
     * @param page       the ebookdroid Page object for the current rendered page
     */
    public static void highlightSentence(final List<Sentence> sentences,
                                         final int index,
                                         final org.ebookdroid.core.Page page) {
        if (sentences == null || page == null) return;
        if (index < 0 || index >= sentences.size()) return;

        Sentence s = sentences.get(index);

        // Clear old highlight and set new one atomically on the main thread
        page.selectedText.clear();
        page.selectedText.addAll(s.words);

        // Trigger redraw
        EventBus.getDefault().post(new InvalidateMessage());
    }

    /**
     * Clear any active highlight.
     */
    public static void clearHighlight(final org.ebookdroid.core.Page page) {
        if (page == null) return;
        page.selectedText.clear();
        EventBus.getDefault().post(new InvalidateMessage());
    }
}
