package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OnnxTokenizerFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsBundledNativeTokenizerInOfflineMode() throws Exception {
        Path tokenizerFile = tempDir.resolve("tokenizer.json");
        Files.writeString(tokenizerFile, wordPieceTokenizer());

        try (HuggingFaceTokenizer tokenizer = new OnnxTokenizerFactory().load(tokenizerFile)) {
            assertEquals("true", System.getProperty("ai.djl.offline"));
            assertEquals("true", System.getProperty("OPT_OUT_TRACKING"));
            assertTrue(tokenizer.getVersion().endsWith("-0.33.0"));
            assertArrayEquals(
                    new long[] {1, 2},
                    tokenizer.encode("Hello world", false, false).getIds());
        }
    }

    @Test
    void mapsMalformedLocalTokenizerToIOException() throws Exception {
        Path tokenizerFile = tempDir.resolve("malformed-tokenizer.json");
        Files.writeString(tokenizerFile, "{}");

        assertThrows(OnnxTokenizerLoadException.class, () -> new OnnxTokenizerFactory().load(tokenizerFile));
    }

    @Test
    void configuresNativeTruncationAtTheEmbeddingSpaceBoundary() throws Exception {
        Path tokenizerFile = tempDir.resolve("tokenizer.json");
        Files.writeString(tokenizerFile, wordPieceTokenizer());

        try (HuggingFaceTokenizer tokenizer = new OnnxTokenizerFactory().load(tokenizerFile, 1)) {
            assertArrayEquals(
                    new long[] {1},
                    tokenizer.encode("Hello world", false, false).getIds());
        }
    }

    private static String wordPieceTokenizer() {
        return """
                {
                  "version": "1.0",
                  "truncation": null,
                  "padding": null,
                  "added_tokens": [],
                  "normalizer": {
                    "type": "BertNormalizer",
                    "clean_text": true,
                    "handle_chinese_chars": true,
                    "strip_accents": null,
                    "lowercase": true
                  },
                  "pre_tokenizer": {"type": "Whitespace"},
                  "post_processor": null,
                  "decoder": {"type": "WordPiece", "prefix": "##", "cleanup": true},
                  "model": {
                    "type": "WordPiece",
                    "unk_token": "[UNK]",
                    "continuing_subword_prefix": "##",
                    "max_input_chars_per_word": 100,
                    "vocab": {"[UNK]": 0, "hello": 1, "world": 2}
                  }
                }
                """;
    }
}
