package io.hanji.spi;

/** Notifications from a host to Hanji. May be called from any thread. */
@FunctionalInterface
public interface HostCallbacks {

    /** The user closed the native window. */
    void closed();
}
