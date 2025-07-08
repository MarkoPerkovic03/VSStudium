package seller;

import network.Configuration;
import network.FailureSimulator;
import network.Message;
import network.MessageType;
import org.zeromq.SocketType;
import org.zeromq.ZMQ;
import org.zeromq.ZContext;
import com.google.gson.Gson;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class Seller {
    private final String sellerId;
    private final ZContext context;
    private final ZMQ.Socket socket;
    private final ProductInventory inventory;
    private final FailureSimulator failureSimulator;
    private final Gson gson;
    private final Map<String, ReservationRecord> reservations;

    public Seller(String sellerId, Configuration config) {
        this.sellerId = sellerId;
        this.context = new ZContext();
        this.socket = context.createSocket(SocketType.REP);
        this.inventory = new ProductInventory();
        this.gson = new Gson();
        this.reservations = new ConcurrentHashMap<>();
        
        // Initialize failure simulator
        double crashProb = config.getDouble("crash.probability", 0.05);
        double dropProb = config.getDouble("drop.probability", 0.05);
        double timeoutProb = config.getDouble("timeout.probability", 0.05);
        int minDelay = config.getInt("min.delay.ms", 100);
        int maxDelay = config.getInt("max.delay.ms", 500);
        
        this.failureSimulator = new FailureSimulator(crashProb, dropProb, timeoutProb, minDelay, maxDelay);
        
        // Initialize inventory from config
        initializeInventory(config);
        
        // PORT-SCHEMA: S1=5570, S2=5571, S3=5572, etc.
        int port = 5569 + Integer.parseInt(sellerId.substring(1)); // S1 -> 5570, S2 -> 5571, etc.
        String bindAddress = "tcp://*:" + port;
        socket.bind(bindAddress);
        
        System.out.println("Seller " + sellerId + " started on " + bindAddress);
        printInventory();
    }
    
    private void initializeInventory(Configuration config) {
        // Load products from config (product.P1=10, product.P2=15, etc.)
        String[] productKeys = {"product.P1", "product.P2", "product.P3", "product.P4", "product.P5"};
        
        for (String key : productKeys) {
            String productId = key.substring(8); // Remove "product." prefix
            int quantity = config.getInt(key, 10); // Default 10 if not found
            inventory.addProduct(productId, quantity);
            System.out.println("[" + sellerId + "] Loaded " + productId + ": " + quantity + " units");
        }
    }
    
    public void start() {
        System.out.println("Seller " + sellerId + " listening for requests...");
        
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Receive message
                String messageJson = socket.recvStr();
                if (messageJson != null) {
                    System.out.println("[" + sellerId + "] Received: " + messageJson);
                    
                    // Simulate network delay
                    simulateDelay();
                    
                    // Check for failure simulation
                    if (failureSimulator.shouldDrop()) {
                        System.out.println("[" + sellerId + "] SIMULATED: Message dropped");
                        continue; // Don't send response
                    }
                    
                    if (failureSimulator.shouldCrash()) {
                        System.out.println("[" + sellerId + "] SIMULATED: Crash - Processing but not responding");
                        processMessage(messageJson);
                        continue; // Don't send response
                    }
                    
                    // Process message and send response
                    String response = processMessage(messageJson);
                    
                    if (failureSimulator.shouldTimeout()) {
                        System.out.println("[" + sellerId + "] SIMULATED: Timeout - Long delay before response");
                        Thread.sleep(5000); // 5 second delay
                    }
                    
                    socket.send(response);
                    System.out.println("[" + sellerId + "] Sent: " + response);
                }
            } catch (Exception e) {
                System.err.println("[" + sellerId + "] Error: " + e.getMessage());
            }
        }
    }
    
    private String processMessage(String messageJson) {
        try {
            Message message = gson.fromJson(messageJson, Message.class);
            
            switch (message.getType()) {
                case RESERVE_PRODUCT:
                    return handleReserveProduct(message);
                case COMMIT_ORDER:
                    return handleCommitOrder(message);
                case ROLLBACK_ORDER:
                    return handleRollbackOrder(message);
                default:
                    return createErrorResponse(message.getCorrelationId(), "Unknown message type");
            }
        } catch (Exception e) {
            System.err.println("[" + sellerId + "] Error processing message: " + e.getMessage());
            return createErrorResponse("unknown", "Invalid message format");
        }
    }
    
    private String handleReserveProduct(Message message) {
        try {
            // Parse reservation request: "productId:quantity"
            String[] parts = message.getPayload().split(":");
            String productId = parts[0];
            int quantity = Integer.parseInt(parts[1]);
            String orderId = message.getCorrelationId();
            
            System.out.println("[" + sellerId + "] Reserve request: " + productId + " x" + quantity + " for order " + orderId);
            
            // Check if we can reserve the product
            boolean success = inventory.reserveProduct(productId, quantity);
            
            if (success) {
                // Store reservation for potential rollback
                reservations.put(orderId + ":" + productId, new ReservationRecord(orderId, productId, quantity));
                
                Message response = new Message(MessageType.RESERVE_CONFIRM, 
                    productId + ":" + quantity, orderId);
                System.out.println("[" + sellerId + "] Reserved " + productId + " x" + quantity);
                printInventory();
                return gson.toJson(response);
            } else {
                Message response = new Message(MessageType.RESERVE_REJECT, 
                    productId + ":insufficient_stock", orderId);
                System.out.println("[" + sellerId + "] REJECTED " + productId + " x" + quantity + " - insufficient stock");
                return gson.toJson(response);
            }
        } catch (Exception e) {
            return createErrorResponse(message.getCorrelationId(), "Error processing reservation");
        }
    }
    
    private String handleCommitOrder(Message message) {
        String orderId = message.getCorrelationId();
        System.out.println("[" + sellerId + "] COMMIT order: " + orderId);
        
        // Remove reservations (products are now sold)
        reservations.entrySet().removeIf(entry -> entry.getKey().startsWith(orderId + ":"));
        
        Message response = new Message(MessageType.RESERVE_CONFIRM, "committed", orderId);
        System.out.println("[" + sellerId + "] Order " + orderId + " committed");
        printInventory();
        return gson.toJson(response);
    }
    
    private String handleRollbackOrder(Message message) {
        String orderId = message.getCorrelationId();
        System.out.println("[" + sellerId + "] ROLLBACK order: " + orderId);
        
        // Restore reserved products to inventory
        reservations.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(orderId + ":"))
            .forEach(entry -> {
                ReservationRecord record = entry.getValue();
                inventory.releaseProduct(record.getProductId(), record.getQuantity());
                System.out.println("[" + sellerId + "] Released " + record.getProductId() + " x" + record.getQuantity());
            });
        
        // Remove reservations
        reservations.entrySet().removeIf(entry -> entry.getKey().startsWith(orderId + ":"));
        
        Message response = new Message(MessageType.RESERVE_CONFIRM, "rolledback", orderId);
        System.out.println("[" + sellerId + "] Order " + orderId + " rolled back");
        printInventory();
        return gson.toJson(response);
    }
    
    private String createErrorResponse(String correlationId, String error) {
        Message response = new Message(MessageType.RESERVE_REJECT, error, correlationId);
        return gson.toJson(response);
    }
    
    private void simulateDelay() {
        try {
            int delay = failureSimulator.simulateDelay();
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private void printInventory() {
        System.out.println("[" + sellerId + "] Current inventory: " +
            "P1=" + inventory.getStock("P1") +
            ", P2=" + inventory.getStock("P2") +
            ", P3=" + inventory.getStock("P3") +
            ", P4=" + inventory.getStock("P4") +
            ", P5=" + inventory.getStock("P5"));
    }
    
    public void stop() {
        socket.close();
        context.close();
    }
    
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java -cp target/distributed-marketplace-1.0-SNAPSHOT.jar seller.Seller <seller_id>");
            return;
        }
        
        String sellerId = args[0];
        Configuration config = new Configuration("config/seller.properties");
        
        Seller seller = new Seller(sellerId, config);
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down seller " + sellerId);
            seller.stop();
        }));
        
        seller.start();
    }
}