package com.amsha.persistence.fs;

import com.amsha.persistence.api.PersistenceBackend;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "amsha.fs")
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.FILESYSTEM)
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
