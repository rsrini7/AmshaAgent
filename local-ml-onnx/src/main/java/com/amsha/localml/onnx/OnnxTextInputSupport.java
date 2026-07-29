package com.amsha.localml.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Payload-free shared tokenizer, graph-validation, and tensor lifecycle helpers. */
final class OnnxTextInputSupport {

    private OnnxTextInputSupport() {}

    static void validateGraph(OrtSession session, OnnxTextInputSpec spec) throws OnnxTextInputException {
        List<String> declaredInputs = new ArrayList<>();
        declaredInputs.add(spec.inputIdsName());
        declaredInputs.add(spec.attentionMaskName());
        if (spec.tokenTypeIdsName() != null) declaredInputs.add(spec.tokenTypeIdsName());
        if (!session.getInputNames().equals(Set.copyOf(declaredInputs))) {
            throw new OnnxTextInputException("ONNX graph inputs do not match the verified manifest");
        }
        if (!session.getOutputNames().contains(spec.outputName())) {
            throw new OnnxTextInputException("Declared ONNX output is absent from the graph");
        }
    }

    static Batch prepare(HuggingFaceTokenizer tokenizer, OnnxTextInputSpec spec, List<String> texts)
            throws OnnxTextInputException {
        Encoding[] encodings = tokenizer.batchEncode(texts, true, true);
        if (encodings.length != texts.size()) {
            throw new OnnxTextInputException("Tokenizer output batch size does not match input batch");
        }
        int sequenceLength = 0;
        for (Encoding encoding : encodings) {
            Objects.requireNonNull(encoding, "tokenizer encoding");
            sequenceLength = Math.max(sequenceLength, Math.min(encoding.getIds().length, spec.maxSequenceLength()));
        }
        if (sequenceLength == 0) throw new OnnxTextInputException("Tokenizer produced an empty sequence");

        long[][] inputIds = new long[texts.size()][sequenceLength];
        long[][] attentionMask = new long[texts.size()][sequenceLength];
        long[][] tokenTypeIds = new long[texts.size()][sequenceLength];
        for (int row = 0; row < encodings.length; row++) {
            Arrays.fill(inputIds[row], spec.paddingTokenId());
            long[] ids = encodings[row].getIds();
            long[] mask = encodings[row].getAttentionMask();
            long[] types = encodings[row].getTypeIds();
            int copied = Math.min(ids.length, sequenceLength);
            System.arraycopy(ids, 0, inputIds[row], 0, copied);
            System.arraycopy(mask, 0, attentionMask[row], 0, Math.min(mask.length, copied));
            if (types != null) {
                System.arraycopy(types, 0, tokenTypeIds[row], 0, Math.min(types.length, copied));
            }
            if (countActive(attentionMask[row]) == 0) {
                throw new OnnxTextInputException("Tokenizer produced an all-zero attention mask");
            }
        }
        return new Batch(inputIds, attentionMask, tokenTypeIds);
    }

    static TensorBatch createTensors(OrtEnvironment environment, OnnxTextInputSpec spec, Batch inputs)
            throws OrtException {
        OnnxTensor inputIds = OnnxTensor.createTensor(environment, inputs.inputIds());
        try {
            OnnxTensor attentionMask = OnnxTensor.createTensor(environment, inputs.attentionMask());
            try {
                OnnxTensor tokenTypeIds = spec.tokenTypeIdsName() == null
                        ? null
                        : OnnxTensor.createTensor(environment, inputs.tokenTypeIds());
                Map<String, OnnxTensor> tensors = new LinkedHashMap<>();
                tensors.put(spec.inputIdsName(), inputIds);
                tensors.put(spec.attentionMaskName(), attentionMask);
                if (tokenTypeIds != null) tensors.put(spec.tokenTypeIdsName(), tokenTypeIds);
                return new TensorBatch(inputIds, attentionMask, tokenTypeIds, Map.copyOf(tensors));
            } catch (OrtException | RuntimeException e) {
                attentionMask.close();
                throw e;
            }
        } catch (OrtException | RuntimeException e) {
            inputIds.close();
            throw e;
        }
    }

    private static int countActive(long[] mask) {
        int count = 0;
        for (long value : mask) if (value != 0) count++;
        return count;
    }

    static final class Batch {
        private final long[][] inputIds;
        private final long[][] attentionMask;
        private final long[][] tokenTypeIds;

        private Batch(long[][] inputIds, long[][] attentionMask, long[][] tokenTypeIds) {
            this.inputIds = inputIds;
            this.attentionMask = attentionMask;
            this.tokenTypeIds = tokenTypeIds;
        }

        long[][] inputIds() {
            return inputIds;
        }

        long[][] attentionMask() {
            return attentionMask;
        }

        long[][] tokenTypeIds() {
            return tokenTypeIds;
        }
    }

    record TensorBatch(
            OnnxTensor inputIds, OnnxTensor attentionMask, OnnxTensor tokenTypeIds, Map<String, OnnxTensor> values)
            implements AutoCloseable {

        @Override
        public void close() {
            try {
                if (tokenTypeIds != null) tokenTypeIds.close();
            } finally {
                try {
                    attentionMask.close();
                } finally {
                    inputIds.close();
                }
            }
        }
    }

    static final class OnnxTextInputException extends Exception {
        private OnnxTextInputException(String message) {
            super(message);
        }
    }
}
