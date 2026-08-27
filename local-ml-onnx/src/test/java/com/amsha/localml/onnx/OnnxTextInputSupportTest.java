package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OnnxTextInputSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void preparePairsTokenizesAndEnforcesTruncationOnLongCandidates() throws Exception {
        Path tokenizerFile = tempDir.resolve("tokenizer.json");
        Files.writeString(tokenizerFile, wordPieceTokenizer());

        int maxSeqLength = 5;
        try (HuggingFaceTokenizer tokenizer = new OnnxTokenizerFactory().load(tokenizerFile, maxSeqLength)) {
            OnnxScoringBundleSpec spec = new OnnxScoringBundleSpec(
                    "text-pair",
                    "v1",
                    "input_ids",
                    "attention_mask",
                    "token_type_ids",
                    "logits",
                    maxSeqLength,
                    0L,
                    "relevance",
                    OnnxScoringBundleSpec.ScoreDirection.HIGHER_IS_BETTER,
                    OnnxScoringBundleSpec.OutputShape.BATCH_BY_ONE,
                    List.of("probe"));

            OnnxInferenceMetrics metrics = mock(OnnxInferenceMetrics.class);

            String query = "Hello";
            // Long candidate with many words
            String longCandidate = "world world world world world world world world world";

            OnnxTextInputSupport.Batch batch =
                    OnnxTextInputSupport.preparePairs(tokenizer, spec, query, List.of(longCandidate), metrics);

            assertNotNull(batch);
            assertEquals(1, batch.inputIds().length);
            assertTrue(batch.inputIds()[0].length <= maxSeqLength);
            assertEquals(maxSeqLength, batch.inputIds()[0].length);

            // Verify truncation metric recorded
            verify(metrics).recordTruncation("cross_encoder");
        }
    }

    private static String wordPieceTokenizer() {
        return """
                {
                  "version": "1.0",
                  "truncation": null,
                  "padding": null,
                  "added_tokens": [
                    {
                      "id": 0,
                      "content": "[PAD]",
                      "single_word": false,
                      "lstrip": false,
                      "rstrip": false,
                      "normalized": false,
                      "special": true
                    }
                  ],
                  "normalizer": {
                    "type": "BertNormalizer",
                    "clean_text": true,
                    "handle_chinese_chars": true,
                    "strip_accents": null,
                    "lowercase": true
                  },
                  "pre_tokenizer": {
                    "type": "BertPreTokenizer"
                  },
                  "post_processor": null,
                  "decoder": {
                    "type": "WordPiece",
                    "prefix": "##",
                    "cleanup": true
                  },
                  "model": {
                    "type": "WordPiece",
                    "unk_token": "[UNK]",
                    "continuing_subword_prefix": "##",
                    "max_input_chars_per_word": 100,
                    "vocab": {
                      "[PAD]": 0,
                      "[UNK]": 1,
                      "hello": 2,
                      "world": 3
                    }
                  }
                }
                """;
    }
}
