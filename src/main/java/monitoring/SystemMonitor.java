package monitoring;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class SystemMonitor {
    private static final SystemMonitor INSTANCE = new SystemMonitor();
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    
    // Metrics
    private final AtomicInteger totalOrdersReceived = new AtomicInteger(0);
    private final AtomicInteger successfulOrders = new AtomicInteger(0);
    private final AtomicInteger failedOrders = new AtomicInteger(0);
    private final AtomicInteger reservationAttempts = new AtomicInteger(0);
    private final AtomicInteger reservationSuccesses = new AtomicInteger(0);
    private final AtomicInteger reservationFailures = new AtomicInteger(0);
    private final AtomicInteger rollbacks = new AtomicInteger(0);
    private final AtomicLong totalProcessingTime = new AtomicLong(0);
    
    private final long startTime = System.currentTimeMillis();
    
    private SystemMonitor() {}
    
    public static SystemMonitor getInstance() {
        return INSTANCE;
    }
    
    // Order tracking methods
    public void orderReceived() {
        totalOrdersReceived.incrementAndGet();
        logEvent("ORDER_RECEIVED", "Total orders: " + totalOrdersReceived.get());
    }
    
    public void orderSucceeded(String orderId, long processingTimeMs) {
        successfulOrders.incrementAndGet();
        totalProcessingTime.addAndGet(processingTimeMs);
        logEvent("ORDER_SUCCESS", "OrderID: " + orderId + ", ProcessingTime: " + processingTimeMs + "ms");
    }
    
    public void orderFailed(String orderId, String reason) {
        failedOrders.incrementAndGet();
        logEvent("ORDER_FAILED", "OrderID: " + orderId + ", Reason: " + reason);
    }
    
    // Reservation tracking methods
    public void reservationAttempted(String sellerId, String productId, int quantity) {
        reservationAttempts.incrementAndGet();
        logEvent("RESERVATION_ATTEMPT", "Seller: " + sellerId + ", Product: " + productId + ", Qty: " + quantity);
    }
    
    public void reservationSucceeded(String sellerId, String productId, int quantity) {
        reservationSuccesses.incrementAndGet();
        logEvent("RESERVATION_SUCCESS", "Seller: " + sellerId + ", Product: " + productId + ", Qty: " + quantity);
    }
    
    public void reservationFailed(String sellerId, String productId, int quantity, String reason) {
        reservationFailures.incrementAndGet();
        logEvent("RESERVATION_FAILED", "Seller: " + sellerId + ", Product: " + productId + ", Qty: " + quantity + ", Reason: " + reason);
    }
    
    public void rollbackExecuted(String orderId, String sellerId) {
        rollbacks.incrementAndGet();
        logEvent("ROLLBACK", "OrderID: " + orderId + ", Seller: " + sellerId);
    }
    
    // Error tracking methods
    public void networkError(String component, String error) {
        logEvent("NETWORK_ERROR", "Component: " + component + ", Error: " + error);
    }
    
    public void sellerTimeout(String sellerId, String operation) {
        logEvent("SELLER_TIMEOUT", "Seller: " + sellerId + ", Operation: " + operation);
    }
    
    public void sellerCrash(String sellerId) {
        logEvent("SELLER_CRASH", "Seller: " + sellerId);
    }
    
    // Utility methods
    private void logEvent(String eventType, String details) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        System.out.println("[MONITOR] " + timestamp + " [" + eventType + "] " + details);
    }
    
    public void printStatistics() {
        long uptimeMs = System.currentTimeMillis() - startTime;
        double uptimeMinutes = uptimeMs / 60000.0;
        
        System.out.println("\n=== SYSTEM STATISTICS ===");
        System.out.println("Uptime: " + String.format("%.2f", uptimeMinutes) + " minutes");
        System.out.println();
        
        // Order statistics
        System.out.println("📦 ORDER STATISTICS:");
        System.out.println("  Total Orders Received: " + totalOrdersReceived.get());
        System.out.println("  Successful Orders: " + successfulOrders.get());
        System.out.println("  Failed Orders: " + failedOrders.get());
        
        if (totalOrdersReceived.get() > 0) {
            double successRate = (successfulOrders.get() * 100.0) / totalOrdersReceived.get();
            System.out.println("  Success Rate: " + String.format("%.1f", successRate) + "%");
        }
        
        if (successfulOrders.get() > 0) {
            double avgProcessingTime = totalProcessingTime.get() / (double) successfulOrders.get();
            System.out.println("  Avg Processing Time: " + String.format("%.2f", avgProcessingTime) + "ms");
        }
        
        // Reservation statistics
        System.out.println();
        System.out.println("🔒 RESERVATION STATISTICS:");
        System.out.println("  Total Reservation Attempts: " + reservationAttempts.get());
        System.out.println("  Successful Reservations: " + reservationSuccesses.get());
        System.out.println("  Failed Reservations: " + reservationFailures.get());
        System.out.println("  Rollbacks Executed: " + rollbacks.get());
        
        if (reservationAttempts.get() > 0) {
            double reservationSuccessRate = (reservationSuccesses.get() * 100.0) / reservationAttempts.get();
            System.out.println("  Reservation Success Rate: " + String.format("%.1f", reservationSuccessRate) + "%");
        }
        
        // Performance metrics
        System.out.println();
        System.out.println("⚡ PERFORMANCE METRICS:");
        if (uptimeMinutes > 0) {
            double ordersPerMinute = totalOrdersReceived.get() / uptimeMinutes;
            System.out.println("  Orders per Minute: " + String.format("%.2f", ordersPerMinute));
        }
        
        System.out.println("========================\n");
    }
    
    public void startPeriodicReporting(int intervalSeconds) {
        Thread reportingThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(intervalSeconds * 1000L);
                    printStatistics();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        
        reportingThread.setDaemon(true);
        reportingThread.start();
        System.out.println("📊 Started periodic monitoring (every " + intervalSeconds + " seconds)");
    }
    
    // Getters for external monitoring
    public int getTotalOrdersReceived() { return totalOrdersReceived.get(); }
    public int getSuccessfulOrders() { return successfulOrders.get(); }
    public int getFailedOrders() { return failedOrders.get(); }
    public int getReservationAttempts() { return reservationAttempts.get(); }
    public int getReservationSuccesses() { return reservationSuccesses.get(); }
    public int getReservationFailures() { return reservationFailures.get(); }
    public int getRollbacks() { return rollbacks.get(); }
    public long getUptimeMs() { return System.currentTimeMillis() - startTime; }
}