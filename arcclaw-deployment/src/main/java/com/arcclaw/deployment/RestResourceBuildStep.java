package com.arcclaw.deployment;

import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;

/**
 * Build-time enforcement of the @RunOnVirtualThread requirement for JPA-enabled
 * REST resources.
 *
 * Architecture Nuance #16: JAX-RS resources that inject EntityManager or use
 * PersistenceContext MUST be annotated with @RunOnVirtualThread to avoid
 * BlockingOperationNotAllowedException at runtime when running on the event
 * loop.
 */
public class RestResourceBuildStep {

    private static final DotName JAX_RS_PATH = DotName.createSimple("jakarta.ws.rs.Path");
    private static final DotName RUN_ON_VIRTUAL_THREAD = DotName
            .createSimple("io.smallrye.common.annotation.RunOnVirtualThread");
    private static final DotName PERSISTENCE_CONTEXT = DotName.createSimple("jakarta.persistence.PersistenceContext");
    private static final DotName INJECT = DotName.createSimple("jakarta.inject.Inject");
    private static final String ENTITY_MANAGER_CLASS = "jakarta.persistence.EntityManager";

    @BuildStep
    void enforceVirtualThreadOnJpaRestResources(
            CombinedIndexBuildItem index,
            BuildProducer<ValidationErrorBuildItem> errors) {

        for (AnnotationInstance pathAnnotation : index.getIndex().getAnnotations(JAX_RS_PATH)) {

            if (pathAnnotation.target().kind() != AnnotationTarget.Kind.CLASS)
                continue;
            ClassInfo classInfo = pathAnnotation.target().asClass();

            // Check: does this class inject EntityManager (directly or via
            // @PersistenceContext)?
            boolean hasJpaInjection = hasJpaInjection(classInfo);
            if (!hasJpaInjection)
                continue;

            // Check: does it have @RunOnVirtualThread?
            if (!classInfo.hasAnnotation(RUN_ON_VIRTUAL_THREAD)) {
                errors.produce(new ValidationErrorBuildItem(new RuntimeException(
                        "REST resource " + classInfo.name() + " injects JPA/EntityManager " +
                                "but is missing @RunOnVirtualThread at class level. " +
                                "This will cause BlockingOperationNotAllowedException at runtime. " +
                                "Add @RunOnVirtualThread to the class declaration. " +
                                "See Architecture Nuance #16.")));
            }
        }
    }

    private boolean hasJpaInjection(ClassInfo classInfo) {
        for (FieldInfo field : classInfo.fields()) {
            // Direct @PersistenceContext injection
            if (field.hasAnnotation(PERSISTENCE_CONTEXT))
                return true;
            // @Inject EntityManager
            if (field.hasAnnotation(INJECT) &&
                    field.type().name().toString().equals(ENTITY_MANAGER_CLASS))
                return true;
        }
        return false;
    }
}
