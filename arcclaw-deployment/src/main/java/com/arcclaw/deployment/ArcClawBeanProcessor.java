package com.arcclaw.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import org.jboss.jandex.DotName;
import java.util.stream.Collectors;
import java.util.List;

public class ArcClawBeanProcessor {

    @BuildStep
    public AdditionalBeanBuildItem registerCoreBeans(CombinedIndexBuildItem index) {
        // Register all ApplicationScoped beans from com.arcclaw.core packages
        // Since arcclaw-core is an extension module, its beans must be explicitly
        // registered or the package must be indexed for discovery.
        // We ensure all core service implementations are available.
        
        List<String> beans = index.getIndex()
                .getKnownClasses().stream()
                .filter(ci -> ci.name().toString().startsWith("com.arcclaw.core"))
                .filter(ci -> ci.hasAnnotation(DotName.createSimple("jakarta.enterprise.context.ApplicationScoped")))
                .map(ci -> ci.name().toString())
                .collect(Collectors.toList());

        AdditionalBeanBuildItem.Builder builder = AdditionalBeanBuildItem.builder();
        for (String bean : beans) {
            builder.addBeanClass(bean);
        }
        return builder.build();
    }
    
    @BuildStep
    public AdditionalBeanBuildItem registerAppBeans(CombinedIndexBuildItem index) {
         // Some beans in arcclaw-app might also need explicit registration if not picked up
         List<String> beans = index.getIndex()
                .getKnownClasses().stream()
                .filter(ci -> ci.name().toString().startsWith("com.arcclaw.app"))
                .filter(ci -> ci.hasAnnotation(DotName.createSimple("jakarta.enterprise.context.ApplicationScoped")))
                .map(ci -> ci.name().toString())
                .collect(Collectors.toList());

        AdditionalBeanBuildItem.Builder builder = AdditionalBeanBuildItem.builder();
        for (String bean : beans) {
            builder.addBeanClass(bean);
        }
        return builder.build();
    }

    @BuildStep
    public UnremovableBeanBuildItem markCoreBeansUnremovable() {
        // Mark all core beans as unremovable to ensure they're available for injection across modules.
        return UnremovableBeanBuildItem.beanClassNames(
            "com.arcclaw.core.routing.IntentClassifierService",
            "com.arcclaw.core.channel.ChannelServicesImpl",
            "com.arcclaw.core.hitl.ApprovalStateMachine",
            "com.arcclaw.core.routing.ModelProducer"
        );
    }
}
