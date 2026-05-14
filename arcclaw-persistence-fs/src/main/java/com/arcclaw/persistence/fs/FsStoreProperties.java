package com.arcclaw.persistence.fs;

import io.quarkus.arc.Unremovable;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "arcclaw.fs")
@Unremovable
public interface FsStoreProperties {

    @WithDefault("./arcclaw-data")
    String dataDir();

    @WithDefault("5")
    int lockTimeoutSeconds();
}
