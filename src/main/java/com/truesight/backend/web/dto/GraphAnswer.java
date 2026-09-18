package com.truesight.backend.web.dto;

import java.util.List;

/**
 * AC 9.4: an answer that cites companies and excerpts present in the user's own
 * graph, or a decline. unverifiedCitations lists any names the model cited that are
 * NOT in the graph — surfaced rather than silently dropped, so the reader can see the
 * model tried to reach outside its data.
 */
public record GraphAnswer(boolean answered, String answer, List<String> citedCompanies,
                          List<String> citedExcerpts, List<String> unverifiedCitations) {
}
