package com.tw.docsagent.eval;

import java.util.List;

/**
 * Ranks for the retrieval eval: where does the chunk with the answer come in the search results?
 * Pure code; the IT does the searching.
 *
 * <p>Ranks are 1-based; 0 means "not in the results at all" (beyond {@link #DEPTH}).
 */
public final class RetrievalScorer {

    /** How deep the eval searches. searchDocs returns 5 by default and at most 8; 20 shows how far off a miss is. */
    public static final int DEPTH = 20;

    private RetrievalScorer() {
    }

    /** 1-based position of the first result containing the phrase (whitespace-insensitive), or 0. */
    static int rank(String phrase, List<String> resultTexts) {
        String wanted = EvalScorer.normalize(phrase);
        for (int i = 0; i < resultTexts.size(); i++) {
            if (EvalScorer.normalize(resultTexts.get(i)).contains(wanted)) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * Rank of a question over one or more queries. Each phrase counts at its best rank over all
     * queries (Claude sees the results of every search it makes); the question counts at its
     * worst phrase, because the answer needs every expected chunk. 0 if any phrase was never found.
     */
    static int questionRank(List<String> phrases, List<List<String>> resultsPerQuery) {
        int worst = 0;
        for (String phrase : phrases) {
            int best = resultsPerQuery.stream()
                    .mapToInt(results -> rank(phrase, results))
                    .filter(rank -> rank > 0)
                    .min()
                    .orElse(0);
            if (best == 0) {
                return 0;
            }
            worst = Math.max(worst, best);
        }
        return worst;
    }

    static boolean hitAt(int rank, int k) {
        return rank > 0 && rank <= k;
    }

    /** Mean reciprocal rank: 1 for rank 1, 0.5 for rank 2, …, 0 for a miss. Rewards "found, and near the top". */
    static double mrr(List<Integer> ranks) {
        return ranks.stream().mapToDouble(rank -> rank > 0 ? 1.0 / rank : 0).average().orElse(0);
    }
}
