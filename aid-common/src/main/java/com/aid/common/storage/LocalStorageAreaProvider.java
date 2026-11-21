package com.aid.common.storage;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.springframework.core.env.Environment;

/** A module registers its persistent areas; administrators choose the sole physical root. */
public interface LocalStorageAreaProvider {
    record Area(String property, String relativePath, Path legacyPath) { }
    List<Area> areas(Environment environment, Path legacyPublic);
    Set<String> dependentBeans();
    /** Derives module process configuration after its registered files have migrated. */
    default java.util.Map<String,Object> managedProperties(Environment environment,Path privateRoot) throws java.io.IOException {
        return java.util.Map.of();
    }
}
