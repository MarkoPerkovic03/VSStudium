package common;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class OrderGenerator {
    private static final Random random = new Random();
    private static final String[] PRODUCT_IDS = {"P1", "P2", "P3", "P4", "P5"};
    private static final String[] CUSTOMER_IDS = {"C001", "C002", "C003", "C004", "C005"};

    public static Order generateOrder() {
        String orderId = "ORD-" + System.currentTimeMillis() + "-" + random.nextInt(1000);
        String customerId = CUSTOMER_IDS[random.nextInt(CUSTOMER_IDS.length)];
        
        List<OrderItem> items = new ArrayList<>();
        int numItems = 1 + random.nextInt(3); // 1-3 items
        
        for (int i = 0; i < numItems; i++) {
            String productId = PRODUCT_IDS[random.nextInt(PRODUCT_IDS.length)];
            int quantity = 1 + random.nextInt(5); // 1-5 quantity
            items.add(new OrderItem(productId, quantity));
        }
        
        return new Order(orderId, customerId, items);
    }
}