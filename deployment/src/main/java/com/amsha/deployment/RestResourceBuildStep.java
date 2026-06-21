package com.amsha.deployment;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;

import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;

/**
 * Build-time enforcement of the @RunOnVirtualThread requirement for REST resources.
 *
 * All JAX-RS resources must be annotated with @RunOnVirtualThread (class-level)
 * to avoid BlockingOperationNotAllowedException at runtime when running on the
 * event loop with virtual threads.
 *
 * Note: Third-party JAX-RS resources (e.g., langchain4j) are excluded from this
 * check since we cannot control their annotations.
 */
public class RestResourceBuildStep {

    private static final DotName JAX_RS_PATH = DotName.createSimple("jakarta.ws.rs.Path");
    private static final DotName RUN_ON_VIRTUAL_THREAD =
            DotName.createSimple("io.smallrye.common.annotation.RunOnVirtualThread");
    private static final String ARCLAW_PACKAGE = "com.amsha.";

    @BuildStep
    void enforceVirtualThreadOnRestResources(
            CombinedIndexBuildItem index, BuildProducer<ValidationErrorBuildItem> errors) {

        for (AnnotationInstance pathAnnotation : index.getIndex().getAnnotations(JAX_RS_PATH)) {

            if (pathAnnotation.target().kind() != AnnotationTarget.Kind.CLASS) continue;
            ClassInfo classInfo = pathAnnotation.target().asClass();

            // Only enforce for Amsha Agent's own REST resources (skip third-party libs)
            if (!classInfo.name().toString().startsWith(ARCLAW_PACKAGE)) continue;

            if (!classInfo.hasAnnotation(RUN_ON_VIRTUAL_THREAD)) {
                errors.produce(new ValidationErrorBuildItem(new RuntimeException("REST resource " + classInfo.name()
                        + " is missing @RunOnVirtualThread at class level. "
                        + "This will cause BlockingOperationNotAllowedException at runtime. "
                        + "Add @RunOnVirtualThread to the class declaration.")));
            }
        }
    }
}
