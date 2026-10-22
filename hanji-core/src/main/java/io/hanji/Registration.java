package io.hanji;

/**
 * Handle returned by listener and binding methods. Call {@link #remove()} to undo the registration.
 */
@FunctionalInterface
public interface Registration {

    void remove();

    static Registration combine(Registration... registrations) {
        return () -> {
            for (Registration r : registrations) {
                if (r != null) {
                    r.remove();
                }
            }
        };
    }
}
