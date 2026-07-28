package com.amsha.localml.onnx;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
        targets = {
            OnnxModelBundleManifest.class,
            OnnxEmbeddingBundleSpec.class,
            OnnxEmbeddingBundleSpec.PoolingStrategy.class
        })
public class OnnxReflectionConfig {}
