package marketplace;

import java.util.*;

public class SagaTransaction {
    private final String orderId;  // final hinzugefügt
    private Map<String, Boolean> productStatus = new HashMap<>();
    private boolean completed = false;

    public SagaTransaction(String orderId, List<String> productIds) {
        this.orderId = orderId;
        for (String pid : productIds) {
            productStatus.put(pid, false);
        }
    }

    // Getter hinzugefügt um "unused field" Warnung zu beheben
    public String getOrderId() {
        return orderId;
    }

    public void confirmProduct(String productId) {
        productStatus.put(productId, true);
    }

    public void rejectProduct(String productId) {
        productStatus.put(productId, false);
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public boolean allConfirmed() {
        return productStatus.values().stream().allMatch(v -> v);
    }

    public boolean anyRejected() {
        return productStatus.values().stream().anyMatch(v -> !v);
    }
    
    @Override
    public String toString() {
        return "SagaTransaction{orderId='" + orderId + "', completed=" + completed + 
               ", productStatus=" + productStatus + "}";
    }
}