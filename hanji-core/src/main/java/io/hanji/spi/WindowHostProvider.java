package io.hanji.spi;

import io.hanji.HanjiConfig;

/**
 * Service provider for a {@link WindowHost}, registered in
 * {@code META-INF/services/io.hanji.spi.WindowHostProvider}. With {@code hanji.host=auto} the available
 * provider with the highest priority is used.
 */
public interface WindowHostProvider {

    /** Name used for {@code -Dhanji.host=name}. */
    String name();

    /** Higher wins in auto mode. Built-ins: app 50, browser 10, external 0. */
    int priority();

    boolean isAvailable(HanjiConfig config);

    WindowHost create(HanjiConfig config) throws Exception;
}
