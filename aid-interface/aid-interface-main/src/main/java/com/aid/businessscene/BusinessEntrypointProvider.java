package com.aid.businessscene;

import java.util.List;

/** Versioned execution packages supply safe metadata; instructions and credentials never cross this boundary. */
public interface BusinessEntrypointProvider {
    record Reference(String kind,String code,String versionId) { }
    record Descriptor(Reference reference,String name,String packageDigest,String defaultModelCode,List<String> selectableModelCodes,List<Reference> dependencies) { }
    record Selection(Reference reference,boolean defaultEntry) { }
    String code();
    List<Descriptor> options(String sceneCode);
    Descriptor resolve(String sceneCode,Reference reference);
    /** Implementations join the caller's transaction; this must not submit generation or charge a user. */
    default void activate(String sceneCode,List<Selection> selections,String actor) { }
}
