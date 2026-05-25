package com.arcclaw.persistence.fs;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "arcclaw.fs")
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "fs")
public interface FsStoreProperties {

    @WithDefault("./data/fs")
    String dataDir();

    @WithDefault("30")
    int lockTimeoutSeconds();

    @WithDefault("lucene-index")
    String luceneIndexDir();

    @WithDefault("768")
    int vectorDimension();
}
