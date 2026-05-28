package com.arcclaw.deployment.scope;

import com.arcclaw.core.scope.WorkspaceScopeContext;
import com.arcclaw.core.scope.WorkspaceScoped;

import org.jboss.jandex.DotName;

import io.quarkus.arc.deployment.ContextRegistrationPhaseBuildItem;
import io.quarkus.arc.deployment.ContextRegistrationPhaseBuildItem.ContextConfiguratorBuildItem;
import io.quarkus.arc.deployment.CustomScopeBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;

/**
 * Quarkus build-time processor that registers the {@link WorkspaceScoped}
 * custom CDI scope with Arc.
 * <p>
 * Three build steps:
 * <ol>
 * <li>{@link #customScope} - declares the annotation as a custom scope so
 * Arc generates client proxies for beans annotated with it.</li>
 * <li>{@link #registerContext} - binds the scope annotation to its
 * {@link WorkspaceScopeContext} implementation via
 * {@code ContextConfigurator.contextClass()}.</li>
 * <li>{@link #unremovableBeans} - prevents Arc from removing the context
 * class during dead-code elimination (it's only referenced reflectively
 * by the Arc runtime).</li>
 * </ol>
 */
public class WorkspaceScopeProcessor {

    /**
     * Declare {@link WorkspaceScoped} as a custom CDI scope.
     * <p>
     * This tells Arc to treat the annotation as a scope annotation and
     * generate client proxies for all beans annotated with it.
     */
    @BuildStep
    CustomScopeBuildItem customScope() {
        return new CustomScopeBuildItem(DotName.createSimple(WorkspaceScoped.class.getName()));
    }

    /**
     * Register the {@link WorkspaceScopeContext} as the context implementation
     * for the {@link WorkspaceScoped} scope.
     * <p>
     * Uses {@code contextClass()} which tells Arc to instantiate the context
     * class directly at runtime - no recorder indirection needed.
     */
    @BuildStep
    ContextConfiguratorBuildItem registerContext(ContextRegistrationPhaseBuildItem phase) {
        return new ContextConfiguratorBuildItem(
                phase.getContext().configure(WorkspaceScoped.class).normal().contextClass(WorkspaceScopeContext.class));
    }

    /**
     * Mark the {@link WorkspaceScopeContext} bean as unremovable.
     * <p>
     * Arc's dead-code elimination would otherwise remove the context class
     * because no injection points reference it directly - it's only
     * instantiated reflectively by the Arc runtime.
     */
    @BuildStep
    void unremovableBeans(BuildProducer<UnremovableBeanBuildItem> producer) {
        producer.produce(new UnremovableBeanBuildItem(
                new UnremovableBeanBuildItem.BeanClassNameExclusion(WorkspaceScopeContext.class.getName())));
    }
}
