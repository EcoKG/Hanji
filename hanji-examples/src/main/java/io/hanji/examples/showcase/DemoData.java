package io.hanji.examples.showcase;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Deterministic demo data (fixed seeds) so screenshots are reproducible. */
final class DemoData {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    static final List<Order.Product> PRODUCTS = List.of(
            new Order.Product("한지 노트 A5", 12_000, "#6366f1"),
            new Order.Product("닥나무 엽서 세트", 8_500, "#0ea5e9"),
            new Order.Product("전통 문양 포장지", 6_000, "#10b981"),
            new Order.Product("한지 조명 스탠드", 89_000, "#f59e0b"),
            new Order.Product("서예 붓 3종", 32_000, "#ec4899"),
            new Order.Product("먹물 120ml", 9_800, "#64748b"),
            new Order.Product("한지 액자 (중)", 45_000, "#8b5cf6"),
            new Order.Product("창호지 롤 10m", 27_000, "#14b8a6"),
            new Order.Product("부채 만들기 키트", 18_000, "#ef4444"),
            new Order.Product("한지 공예 클래스", 120_000, "#4f46e5"));

    static final List<String> CHANNELS = List.of("자사몰", "스마트스토어", "오프라인", "B2B");

    private static final String[][] PEOPLE = {
            {"김서연", "seoyeon.kim"}, {"이도윤", "doyun.lee"}, {"박지호", "jiho.park"}, {"최하은", "haeun.choi"},
            {"정민준", "minjun.jung"}, {"강수아", "sua.kang"}, {"조예준", "yejun.jo"}, {"윤지우", "jiwoo.yoon"},
            {"장서준", "seojun.jang"}, {"임하린", "harin.lim"}, {"문가은", "gaeun.moon"}, {"배준서", "junseo.bae"},
            {"송채원", "chaewon.song"}, {"신동현", "donghyun.shin"}, {"권나은", "naeun.kwon"}};

    private DemoData() {
    }

    static List<Customer> customers() {
        Random r = new Random(1022);
        List<Customer> out = new ArrayList<>();
        for (int i = 0; i < PEOPLE.length; i++) {
            boolean recent = i % 4 == 3;
            LocalDate since = recent ? TODAY.minusDays(5 + r.nextInt(50)) : TODAY.minusDays(120 + r.nextInt(900));
            int past = recent ? r.nextInt(2) : 2 + r.nextInt(14);
            long spent = past * (38_000L + r.nextInt(70) * 1_000L);
            out.add(new Customer(PEOPLE[i][0], PEOPLE[i][1] + "@example.com", since, past, spent));
        }
        return out;
    }

    static List<Order> orders(List<Customer> customers) {
        Random r = new Random(20261022);
        Order.Status[] statuses = {Order.Status.SHIPPING, Order.Status.DELIVERED, Order.Status.DELIVERED,
                Order.Status.PAID, Order.Status.ON_HOLD, Order.Status.SHIPPING, Order.Status.REFUNDED};
        List<Order> out = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            List<Order.Item> items = new ArrayList<>();
            int count = 1 + r.nextInt(3);
            for (int j = 0; j < count; j++) {
                items.add(new Order.Item(PRODUCTS.get(r.nextInt(PRODUCTS.size())), 1 + r.nextInt(3)));
            }
            Order.Status status = i < 3 ? Order.Status.PAID : statuses[r.nextInt(statuses.length)];
            Order order = new Order(String.format("HJ-%05d", 24871 - i), customers.get(r.nextInt(customers.size())),
                    CHANNELS.get(r.nextInt(CHANNELS.size())),
                    TODAY.atTime(18, 40).minusMinutes(i * 53L + r.nextInt(30)), items, status);
            if (status == Order.Status.ON_HOLD) {
                order.notes.add(new Order.Note(order.createdAt.plusMinutes(12), "주소 확인 요청 — 고객 회신 대기"));
            }
            out.add(order);
        }
        return out;
    }

    /** Daily revenue (in 10,000 KRW) for the last {@code days} days, current and previous period. */
    static Map<String, Object> revenue(int days) {
        Random r = new Random(days * 31L);
        List<String> labels = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        List<Integer> previous = new ArrayList<>();
        int buckets = days <= 7 ? 7 : 12;
        int span = Math.max(1, days / buckets);
        double base = 260;
        for (int i = buckets - 1; i >= 0; i--) {
            LocalDate d = TODAY.minusDays((long) i * span);
            labels.add(d.getMonthValue() + "/" + d.getDayOfMonth());
            base = base * (0.96 + r.nextDouble() * 0.12);
            current.add((int) (base * span * (0.9 + r.nextDouble() * 0.25)));
            previous.add((int) (base * span * (0.75 + r.nextDouble() * 0.2)));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("labels", labels);
        m.put("current", current);
        m.put("previous", previous);
        return m;
    }

    static List<Map<String, Object>> channels(int days) {
        Random r = new Random(days * 7L);
        String[] colors = {"#4f46e5", "#0ea5e9", "#10b981", "#f59e0b"};
        int[] weights = {38 + r.nextInt(6), 29 + r.nextInt(6), 18 + r.nextInt(5), 9 + r.nextInt(4)};
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < CHANNELS.size(); i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", CHANNELS.get(i));
            m.put("value", weights[i]);
            m.put("color", colors[i]);
            out.add(m);
        }
        return out;
    }
}
