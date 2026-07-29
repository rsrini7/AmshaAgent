package com.amsha.localml.onnx;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
        targets = {
            OnnxModelBundleManifest.class,
            OnnxModelBundleManifest.OnnxModelBundleSource.class,
            OnnxEmbeddingBundleSpec.class,
            OnnxEmbeddingBundleSpec.PoolingStrategy.class
        })
public class OnnxReflectionConfig {}
