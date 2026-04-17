package com.foobnix.tts;

import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppState;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Maps each TTS paragraph index to its raw page text and approximate screen position.
 *
 * WHY THIS EXISTS
 * ---------------
 * TTS has two text representations of the same page:
 *   (A) event.text  — user TTS replacements applied. "wasn't" → "was t" if the user
 *       has an "n'→space" replacement rule. After stripping punctuation for doSearch,
 *       "was t" → "wast" (4 chars) vs page "wasn't" → "wasnt" (5 chars). No match.
 *   (B) page TextWords — raw as rendered. "wasn't" → stripped = "wasnt".
 *
 * This class builds (B) by re-running replaceHTMLforTTS with replacements disabled.
 * The resulting rawText matches page TextWords exactly, so doSearch finds the right words.
 *
 * DUPLICATE SENTENCE PROBLEM
 * --------------------------
 * If "She was alone" appears at paragraph 1 and paragraph 8, a 4-word search finds both.
 * This class tracks yFraction (paragraph index / total count) so the tap lookup can
 * score candidates by both word presence AND Y proximity, always picking the closest one.
 *
 * USAGE
 * -----
 * PageSentenceMap map = PageSentenceMap.build(dc.getPageHtml());
 *
 * On highlight:  Sentence s = map.get(event.paragIndex);
 *                String query = s.searchQuery(8);   → feed to dc.doSearch()
 *
 * On tap:        int idx = map.findTtsIndex(tapWord, yFraction);
 *                TTSService.pendingParagraph = idx;
 */
public class PageSentenceMap {

    private static final String TAG = "PageSentenceMap";

    // -------------------------------------------------------------------------
    // Data model
    // -------------------------------------------------------------------------

    public static class Sentence {
        /** Index into the TTS_PAUSE-split parts[] array. Equals pendingParagraph value. */
        public final int ttsIndex;
        /** Text with user TTS replacements OFF — matches what the page renderer shows. */
        public final String rawText;
        /** Text with user TTS replacements ON — what the TTS engine speaks. */
        public final String ttsText;
        /**
         * Estimated vertical position of this sentence in the page, 0.0 = top, 1.0 = bottom.
         * Derived from ttsIndex / (totalCount - 1). Approximate but directionally reliable.
         */
        public final float yFraction;

        Sentence(int ttsIndex, String rawText, String ttsText, float yFraction) {
            this.ttsIndex = ttsIndex;
            this.rawText = rawText;
            this.ttsText = ttsText;
            this.yFraction = yFraction;
        }

        /**
         * Build a doSearch query string from the first {@code wordCount} words of rawText.
         *
         * Strips punctuation (so "alone," matches TextWord "alone"), lowercases,
         * and joins with spaces. This is what PageSearcher accumulates in its sliding
         * window — the window text contains stripped word tokens joined by spaces.
         *
         * Using 8 words: long enough to uniquely identify a sentence and avoid matching
         * a duplicate, short enough that minor rendering differences don't break the match.
         */
        public String searchQuery(int wordCount) {
            if (rawText == null || rawText.isEmpty()) return "";
            String[] words = rawText.split("\\s+");
            int count = Math.min(wordCount, words.length);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) {
                String w = words[i].replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase();
                if (!w.isEmpty()) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(w);
                }
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final List<Sentence> sentences = new ArrayList<>();

    private PageSentenceMap() {}

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Build a sentence map from a page's HTML.
     *
     * Runs replaceHTMLforTTS twice: once with user replacements disabled (rawText)
     * and once with them enabled (ttsText). Both are split by TTS_PAUSE to get
     * one Sentence per TTS paragraph. Indices are 1-to-1 with TTSEngine's parts[].
     */
    public static PageSentenceMap build(String pageHtml) {
        PageSentenceMap map = new PageSentenceMap();
        if (pageHtml == null || pageHtml.isEmpty()) return map;

        try {
            // --- Raw split: replacements OFF ---
            boolean savedReplacements = AppState.get().isEnalbeTTSReplacements;
            AppState.get().isEnalbeTTSReplacements = false;
            String rawProcessed = TxtUtils.replaceHTMLforTTS(pageHtml);
            AppState.get().isEnalbeTTSReplacements = savedReplacements;

            // --- TTS split: replacements ON (normal) ---
            String ttsProcessed = TxtUtils.replaceHTMLforTTS(pageHtml);

            String[] rawParts = rawProcessed.split(TxtUtils.TTS_PAUSE);
            String[] ttsParts = ttsProcessed.split(TxtUtils.TTS_PAUSE);

            int count = Math.min(rawParts.length, ttsParts.length);
            for (int i = 0; i < count; i++) {
                String raw = rawParts[i].trim().replaceAll("\\s+", " ");
                String tts = ttsParts[i].trim().replaceAll("\\s+", " ");
                // Y fraction: distribute sentences evenly top-to-bottom.
                // count==1 → 0.0; count>1 → 0.0 … 1.0 linearly.
                float yFrac = (count > 1) ? (float) i / (count - 1) : 0f;
                map.sentences.add(new Sentence(i, raw, tts, yFrac));
            }

            LOG.d(TAG, "built", count, "sentences for page");
        } catch (Exception e) {
            LOG.e(e);
        }

        return map;
    }

    // -------------------------------------------------------------------------
    // Lookups
    // -------------------------------------------------------------------------

    /**
     * Get the sentence at a specific TTS paragraph index.
     * Returns null if the index is out of range.
     */
    public Sentence get(int ttsIndex) {
        if (ttsIndex < 0 || ttsIndex >= sentences.size()) return null;
        return sentences.get(ttsIndex);
    }

    /**
     * Find the TTS paragraph index best matching a tap.
     *
     * Each sentence is scored by:
     *   - Whether it contains the tapped word (word-level precision from the render engine)
     *   - Y-fraction distance from the tap (breaks ties, finds nearest occurrence)
     *
     * A sentence containing the word scores proportional to its Y distance.
     * A sentence NOT containing the word gets a 10× Y-distance penalty,
     * so it only wins if no sentence has the word at all (image pages, etc.).
     *
     * This solves both problems at once:
     *   - "She was alone" at paragraph 1 and paragraph 8: if you tapped near para 8,
     *     both score the word match but para 8 wins on Y distance.
     *   - Common words like "the": Y distance governs and picks the nearest one.
     *
     * @param tapWord  lowercase stripped word at tap position from AdvGuestureDetector.
     *                 Null/empty falls back to pure Y fraction.
     * @param yFraction tap Y as fraction of view height (0.0=top, 1.0=bottom).
     */
    public int findTtsIndex(String tapWord, float yFraction) {
        if (sentences.isEmpty()) return 0;
        if (tapWord == null) tapWord = "";

        final String word = tapWord.toLowerCase().replaceAll("[^\\p{L}\\p{N}]", "");

        int bestIndex = 0;
        float bestScore = Float.MAX_VALUE;

        // Pre-compile pattern for whole-word matching (avoids "alone" matching "alongside")
        Pattern wordPattern = null;
        if (!word.isEmpty()) {
            try {
                wordPattern = Pattern.compile(
                        "(?:^|\\s)" + Pattern.quote(word) + "(?:\\s|$)");
            } catch (Exception e) {
                // ignore — fall back to contains
            }
        }

        for (Sentence s : sentences) {
            boolean hasWord = false;
            if (!word.isEmpty() && !s.rawText.isEmpty()) {
                String rawClean = s.rawText.toLowerCase()
                        .replaceAll("[^\\p{L}\\p{N}\\s]", " ");
                if (wordPattern != null) {
                    hasWord = wordPattern.matcher(rawClean).find();
                }
                if (!hasWord) {
                    hasWord = rawClean.contains(word); // substring fallback
                }
            }

            float yDist = Math.abs(s.yFraction - yFraction);
            // hasWord sentences score by yDist alone.
            // Non-matching sentences score 10× worse, so they only win if nothing matches.
            float score = hasWord ? yDist : (yDist * 10f + 1f);

            if (score < bestScore) {
                bestScore = score;
                bestIndex = s.ttsIndex;
            }
        }

        LOG.d(TAG, "findTtsIndex", "word=", word, "y=", yFraction, "→", bestIndex);
        return bestIndex;
    }

    public boolean isEmpty() { return sentences.isEmpty(); }
    public int size() { return sentences.size(); }
}
