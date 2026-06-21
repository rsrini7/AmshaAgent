package com.arcclaw.persistence.fs.lucene;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import org.jboss.logging.Logger;

public class LuceneIndexManager implements Closeable {

    private static final Logger LOG = Logger.getLogger(LuceneIndexManager.class);

    public static final String FIELD_ID = "id";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_METADATA = "metadata_json";
    public static final String FIELD_TIMESTAMP = "timestamp";
    public static final String FIELD_EMBEDDING = "embedding";

    private static final double RRF_K = 60.0;
    private static final String[] SEARCH_FIELDS = {FIELD_CONTENT};

    private final Directory directory;
    private final StandardAnalyzer analyzer;
    private final IndexWriter writer;
    private final int vectorDimension;

    public LuceneIndexManager(Path indexDir, int vectorDimension) {
        try {
            Files.createDirectories(indexDir);
            this.directory = new NIOFSDirectory(indexDir);
            this.analyzer = new StandardAnalyzer();
            this.vectorDimension = vectorDimension;
            var config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            this.writer = new IndexWriter(directory, config);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize Lucene index at " + indexDir, e);
        }
    }

    public void index(String id, String content, String metadataJson, float[] embedding) {
        try {
            writer.deleteDocuments(new Term(FIELD_ID, id));

            Document doc = new Document();
            doc.add(new StringField(FIELD_ID, id, Field.Store.YES));
            doc.add(new TextField(FIELD_CONTENT, content != null ? content : "", Field.Store.YES));
            if (metadataJson != null && !metadataJson.isBlank()) {
                doc.add(new StoredField(FIELD_METADATA, metadataJson));
            }
            long now = System.currentTimeMillis();
            doc.add(new LongPoint(FIELD_TIMESTAMP, now));
            doc.add(new StoredField(FIELD_TIMESTAMP, now));

            if (embedding != null && embedding.length == vectorDimension) {
                doc.add(new KnnFloatVectorField(FIELD_EMBEDDING, embedding, VectorSimilarityFunction.COSINE));
            }

            writer.addDocument(doc);
            writer.commit();
            LOG.debugf(
                    "Indexed document %s (len=%d, hasVector=%s)",
                    id, content != null ? content.length() : 0, embedding != null);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to index document " + id, e);
        }
    }

    public void delete(String id) {
        try {
            writer.deleteDocuments(new Term(FIELD_ID, id));
            writer.commit();
            LOG.debugf("Deleted document %s", id);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete document " + id, e);
        }
    }

    public List<ScoredResult> searchBM25(String queryText, int topK) {
        if (queryText == null || queryText.isBlank()) return List.of();
        try (var reader = DirectoryReader.open(writer)) {
            var searcher = new IndexSearcher(reader);
            var parser = new MultiFieldQueryParser(SEARCH_FIELDS, analyzer, Map.of());
            parser.setDefaultOperator(QueryParser.Operator.OR);
            Query query;
            try {
                query = parser.parse(queryText);
            } catch (ParseException e) {
                query = parser.parse(QueryParser.escape(queryText));
            }
            var hits = searcher.search(query, topK);
            List<ScoredResult> results = convertHits(searcher, hits.scoreDocs);
            LOG.debugf("BM25 search for '%s' returned %d results", queryText, results.size());
            return results;
        } catch (ParseException e) {
            return List.of();
        } catch (IOException e) {
            throw new UncheckedIOException("BM25 search failed", e);
        }
    }

    public List<ScoredResult> searchVector(float[] queryVector, int topK) {
        if (queryVector == null || queryVector.length == 0) return List.of();
        try (var reader = DirectoryReader.open(writer)) {
            var searcher = new IndexSearcher(reader);
            var query = new KnnFloatVectorQuery(FIELD_EMBEDDING, queryVector, topK);
            var hits = searcher.search(query, topK);
            List<ScoredResult> results = convertHits(searcher, hits.scoreDocs);
            LOG.debugf("Vector search returned %d results (dim=%d)", results.size(), queryVector.length);
            return results;
        } catch (IOException e) {
            throw new UncheckedIOException("Vector search failed", e);
        }
    }

    public List<ScoredResult> searchHybrid(String queryText, float[] queryVector, int topK) {
        var bm25Results = searchBM25(queryText, topK * 2);
        var vectorResults = searchVector(queryVector, topK * 2);
        List<ScoredResult> merged = mergeRRF(topK, bm25Results, vectorResults);
        LOG.debugf("Hybrid search for '%s' merged into %d results", queryText, merged.size());
        return merged;
    }

    public int numDocs() {
        try (var reader = DirectoryReader.open(writer)) {
            int count = reader.numDocs();
            LOG.debugf("Index has %d documents", count);
            return count;
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public void close() throws IOException {
        LOG.debug("Closing Lucene index");
        try {
            writer.close();
        } finally {
            directory.close();
        }
    }

    private List<ScoredResult> convertHits(IndexSearcher searcher, ScoreDoc[] hits) throws IOException {
        var storedFields = searcher.storedFields();
        List<ScoredResult> results = new ArrayList<>(hits.length);
        for (ScoreDoc hit : hits) {
            Document doc = storedFields.document(hit.doc);
            String id = doc.get(FIELD_ID);
            String content = doc.get(FIELD_CONTENT);
            results.add(new ScoredResult(id, content != null ? content : "", hit.score));
        }
        return results;
    }

    @SafeVarargs
    private final List<ScoredResult> mergeRRF(int topN, List<ScoredResult>... rankedLists) {
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, ScoredResult> resultById = new LinkedHashMap<>();

        for (List<ScoredResult> list : rankedLists) {
            for (int i = 0; i < list.size(); i++) {
                ScoredResult r = list.get(i);
                int rank = i + 1;
                double contribution = 1.0 / (RRF_K + rank);
                rrfScores.merge(r.id(), contribution, Double::sum);
                resultById.merge(
                        r.id(),
                        r,
                        (existing, incoming) -> (existing.content() == null
                                        || existing.content().isBlank())
                                ? incoming
                                : existing);
            }
        }

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topN)
                .map(e -> {
                    ScoredResult orig = resultById.get(e.getKey());
                    return new ScoredResult(
                            orig.id(), orig.content(), e.getValue().floatValue());
                })
                .toList();
    }

    public record ScoredResult(String id, String content, float score) {}
}
