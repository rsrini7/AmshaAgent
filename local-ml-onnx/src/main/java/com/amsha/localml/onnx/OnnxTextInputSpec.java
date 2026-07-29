package com.amsha.localml.onnx;

/** Shared, manifest-owned tensor and tokenizer contract for transformer text models. */
interface OnnxTextInputSpec {

    String inputIdsName();

    String attentionMaskName();

    String tokenTypeIdsName();

    String outputName();

    int maxSequenceLength();

    Long paddingTokenId();
}
