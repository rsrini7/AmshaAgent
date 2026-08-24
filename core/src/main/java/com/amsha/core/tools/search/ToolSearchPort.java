package com.amsha.core.tools.search;

import java.util.List;

/**
 * Decoupled SPI port for dynamic tool search indexing and retrieval.
 */
public interface ToolSearchPort {

    /**
     * Searches the tool index for tools matching the natural language query.
     *
     * @param query the search query
     * @param maxResults maximum number of matching tool names to return
     * @return list of matching tool names
     */
    List<String> search(String query, int maxResults);

    /**
     * Checks if the search index is ready for queries.
     *
     * @return true if index is built and ready, false if degraded/empty
     */
    boolean isReady();
}
