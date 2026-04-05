package com.arcclaw.deployment;

import java.util.List;
import java.util.stream.Collectors;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import org.jboss.jandex.DotName;
import org.jboss.logging.Logger;

/**
 * Registers runtime beans from arcclaw-core and arcclaw-app as CDI beans.
 * <p>
 * Because arcclaw-core declares itself as a Quarkus extension
 * (META-INF/quarkus-extension.properties), its classes are NOT
 * auto-discovered by Arc for CDI bean management. They must be
 * explicitly registered here via {@link AdditionalBeanBuildItem}.
 */
public class ArcClawBeanProcessor {

    private static final Logger log = Logger.getLogger(ArcClawBeanProcessor.class);

    private static final DotName APP_SCOPED =
            DotName.createSimple("jakarta.enterprise.context.ApplicationScoped");
    private static final DotName DEPENDENT =
            DotName.createSimple("jakarta.enterprise.context.Dependent");
    private static final DotName SINGLETON =
            DotName.createSimple("jakarta.inject.Singleton");

    /**
     * Scans the combined Jandex index for all CDI-annotated beans inside
     * {@code com.arcclaw.core} and registers them with Arc.
     */
    @BuildStep
    AdditionalBeanBuildItem registerCoreBeans(CombinedIndexBuildItem index) {
        List<String> beans = index.getIndex()
                .getKnownClasses().stream()
                .filter(ci -> ci.name().toString().startsWith("com.arcclaw.core"))
                .filter(ci -> ci.hasAnnotation(APP_SCOPED)
                           || ci.hasAnnotation(DEPENDENT)
                           || ci.hasAnnotation(SINGLETON))
                .map(ci -> ci.name().toString())
                .collect(Collectors.toList());

        log.infof("[ArcClawBeanProcessor] Found %d core beans in combined index", beans.size());
        if (beans.isEmpty()) {
            log.warn("[ArcClawBeanProcessor] No core beans found — " +
                     "falling back to explicit registration");
            return explicitCoreBeans();
        }

        AdditionalBeanBuildItem.Builder builder = AdditionalBeanBuildItem.builder()
                .setUnremovable();
        beans.forEach(builder::addBeanClass);
        return builder.build();
    }

    private AdditionalBeanBuildItem explicitCoreBeans() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClass(com.arcclaw.core.channel.ChannelServicesImpl.class)
                .addBeanClass(com.arcclaw.core.hitl.ApprovalStateMachine.class)
                .addBeanClass(com.arcclaw.core.routing.IntentClassifierService.class)
                .addBeanClass(com.arcclaw.core.routing.ModelProducer.class)
                .addBeanClass(com.arcclaw.core.routing.LlmModelFactory.class)
                .addBeanClass(com.arcclaw.core.channel.ChannelPluginRegistry.class)
                .addBeanClass(com.arcclaw.core.pipeline.MessagePipeline.class)
                .addBeanClass(com.arcclaw.core.lifecycle.WorkspaceManager.class)
                .addBeanClass(com.arcclaw.core.security.DestructiveVocabularyFilter.class)
                .addBeanClass(com.arcclaw.core.persistence.AsyncPersistenceEngine.class)
                .addBeanClass(com.arcclaw.core.persistence.FtsConnectionInterceptor.class)
                .addBeanClass(com.arcclaw.core.audit.AuditSchemaEnforcer.class)
                .addBeanClass(com.arcclaw.core.audit.TelemetryAuditService.class)
                .addBeanClass(com.arcclaw.core.tools.ToolRegistry.class)
                .addBeanClass(com.arcclaw.core.reflex.ReflexRegistry.class)
                .setUnremovable()
                .build();
    }
}
