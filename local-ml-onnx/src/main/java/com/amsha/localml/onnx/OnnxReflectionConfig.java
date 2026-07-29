package com.amsha.localml.onnx;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
        targets = {
            OnnxModelBundleManifest.class,
            OnnxModelBundleManifest.OnnxModelBundleSource.class,
            OnnxEmbeddingBundleSpec.class,
            OnnxEmbeddingBundleSpec.PoolingStrategy.class,
            OnnxClassifierBundleSpec.class,
            OnnxClassifierBundleSpec.ScoreTransform.class
        })
public class OnnxReflectionConfig {}
