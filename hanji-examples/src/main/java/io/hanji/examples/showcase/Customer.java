package io.hanji.examples.showcase;

import java.time.LocalDate;

/** A customer of the showcase shop. Order statistics are computed by {@link Store}. */
final class Customer {

    enum Grade {
        VIP("VIP", "vip"), GOLD("골드", "gold"), NEW("신규", "new");

        final String label;
        final String css;

        Grade(String label, String css) {
            this.label = label;
            this.css = css;
        }
    }

    final String name;
    final String email;
    final LocalDate since;
    /** Orders and spending before the demo period (keeps the numbers realistic). */
    final int pastOrders;
    final long pastSpent;

    Customer(String name, String email, LocalDate since, int pastOrders, long pastSpent) {
        this.name = name;
        this.email = email;
        this.since = since;
        this.pastOrders = pastOrders;
        this.pastSpent = pastSpent;
    }

    String initials() {
        return name.substring(0, 1);
    }
}
