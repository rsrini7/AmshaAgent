package com.amsha.tools.builtin;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.amsha.core.tools.ToolDiscoveryCompleted;
import com.amsha.core.tools.ToolDiscoveryService;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.tools.search.ToolSearchPort;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class ToolSearchIndex implements ToolSearchPort {

    private static final Logger LOG = Logger.getLogger(ToolSearchIndex.class);

    private static final String FIELD_NAME = "name";
    private static final String FIELD_DESCRIPTION = "description";

    @Inject
    ToolDiscoveryService discoveryService;

    private final Directory directory = new ByteBuffersDirectory();
    private final StandardAnalyzer analyzer = new StandardAnalyzer();
    private volatile boolean ready = false;

    void onDiscoveryCompleted(@Observes ToolDiscoveryCompleted event) {
        rebuild();
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    /**
     * Rebuilds the Lucene index from the current tool registry.
     * Called at startup and can be called on tool hot-reload.
     */
    public void rebuild() {
        try {
            var config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
            try (var writer = new IndexWriter(directory, config)) {
                for (var entry : discoveryService.getDescriptorCache().entrySet()) {
                    String name = entry.getKey();
                    ToolRegistry.ToolDescriptor desc = entry.getValue();
                    Document doc = new Document();
                    doc.add(new TextField(FIELD_NAME, name, Field.Store.YES));
                    doc.add(new TextField(FIELD_DESCRIPTION, desc.description(), Field.Store.YES));
                    writer.addDocument(doc);
                }
                this.ready = true;
                LOG.infof(
                        "ToolSearchIndex: indexed %d tools",
                        discoveryService.getDescriptorCache().size());
            }
        } catch (Throwable t) {
            this.ready = false;
            LOG.errorf("Failed to build tool search index: %s", t.getMessage());
        }
    }

    private record HitResult(String name, float score) {}

    /**
     * Searches the tool index for tools relevant to the given query.
     *
     * @param query      Natural-language query (e.g. "search the web for current news")
     * @param maxResults Maximum number of results to return (default 8)
     * @return Ordered list of matching tool names, most relevant first.
     */
    @Override
    public List<String> search(String query, int maxResults) {
        if (!ready || query == null || query.isBlank() || maxResults <= 0) return List.of();

        try {
            var reader = DirectoryReader.open(directory);
            var searcher = new IndexSearcher(reader);

            String[] fields = {FIELD_NAME, FIELD_DESCRIPTION};
            var parser = new MultiFieldQueryParser(fields, analyzer, Map.of());
            parser.setDefaultOperator(QueryParser.Operator.OR);

            String text = query;
            Query luceneQuery;
            try {
                luceneQuery = parser.parse(text);
            } catch (ParseException e) {
                text = QueryParser.escape(query);
                luceneQuery = parser.parse(text);
            }
            ScoreDoc[] hits = searcher.search(luceneQuery, maxResults).scoreDocs;

            List<HitResult> results = new ArrayList<>(hits.length);
            for (ScoreDoc hit : hits) {
                Document doc = searcher.storedFields().document(hit.doc);
                results.add(new HitResult(doc.get(FIELD_NAME), hit.score));
            }
            reader.close();

            results.sort(Comparator.<HitResult>comparingDouble(h -> -h.score()).thenComparing(HitResult::name));
            return results.stream().map(HitResult::name).toList();

        } catch (ParseException e) {
            LOG.debugf("Tool query parse failed (benign): %s", e.getMessage());
            return List.of();
        } catch (Throwable t) {
            // IllegalArgumentException (PhraseQuery on non-position field), IOException, LinkageError, etc.
            LOG.debugf("Tool search failed (benign): %s", t.getMessage());
            return List.of();
        }
    }

    /**
     * Searches with a default max of 8 results.
     */
    public List<String> search(String query) {
        return search(query, 8);
    }

    /**
     * Returns the number of indexed tools.
     */
    public int size() {
        try {
            var reader = DirectoryReader.open(directory);
            int count = reader.numDocs();
            reader.close();
            return count;
        } catch (IOException e) {
            return 0;
        }
    }
}
