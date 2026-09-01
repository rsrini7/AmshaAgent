package com.amsha.localml.onnx;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
        targets = {
            OnnxModelBundleManifest.class,
            OnnxModelBundleManifest.OnnxModelBundleSource.class,
            OnnxEmbeddingBundleSpec.class,
            OnnxEmbeddingBundleSpec.PoolingStrategy.class,
            OnnxClassifierBundleSpec.class,
            OnnxClassifierBundleSpec.ScoreTransform.class,
            OnnxClassifierRuntimeControl.Classifier.class,
            OnnxClassifierRuntimeControl.Snapshot.class,
            VerifiedOnnxModelBundle.class,
            OnnxPostPolicyToolRiskInterceptor.class,
            OnnxAnswerQualityInterceptor.class,
            OnnxScoringBundleSpec.class,
            OnnxScoringBundleSpec.ScoreDirection.class,
            OnnxScoringBundleSpec.OutputShape.class,
            LocalOnnxScoringAdapter.class,
            OnnxScoringRunner.class
        },
        classNames = {
            "com.amsha.localml.onnx.OnnxTextInputSpec",
            "com.amsha.localml.onnx.OnnxClassifierRuntimeControl$Registration",
            "com.amsha.localml.onnx.OnnxClassifierRuntimeControl$Lease",
            "com.amsha.localml.onnx.OnnxClassifierRuntimeControl$ToggleResult"
        })
public class OnnxReflectionConfig {}
