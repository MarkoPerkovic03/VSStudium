package marketplace;

import common.Order;
import common.OrderGenerator;
import network.Configuration;
import monitoring.SystemMonitor;
import org.zeromq.SocketType;
import org.zeromq.ZMQ;
import org.zeromq.ZContext;
import com.google.gson.Gson;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Marketplace {
    private final String id;
    private final ZContext context;
    private final ZMQ.Socket socket;
    private final Gson gson;
    private final SagaCoordinator sagaCoordinator;
    private final OrderProcessor orderProcessor;
    private final ScheduledExecutorService scheduler;
    private final Configuration config;

    public Marketplace(String id, String bindAddress, Configuration config) {
        this.id = id;
        this.context = new ZContext();
        this.socket = context.createSocket(SocketType.REP);
        this.socket.bind(bindAddress);
        this.gson = new Gson();
        this.sagaCoordinator = new SagaCoordinator();
        this.orderProcessor = new OrderProcessor(sagaCoordinator);
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.config = config;
        
        System.out.println("Marketplace " + id + " started on " + bindAddress);
    }

    public void start() {
        System.out.println("Marketplace " + id + " starting services...");
        
        // Start system monitoring
        SystemMonitor.getInstance().startPeriodicReporting(30); // Every 30 seconds
        
        // Start automatic order generation
        startOrderGeneration();
        
        // Start listening for external orders
        startOrderListener();
    }
    
    /**
     * Start automatic order generation based on configuration
     */
    private void startOrderGeneration() {
        int arrivalRateMs = config.getInt("order.arrival.rate.ms", 2000);
        
        scheduler.scheduleAtFixedRate(() -> {
            try {
                Order order = OrderGenerator.generateOrder();
                System.out.println("\n🆕 AUTO-GENERATED ORDER: " + order.getOrderId());
                System.out.println("Customer: " + order.getCustomerId());
                System.out.println("Items: " + order.getItems().size());
                order.getItems().forEach(item -> 
                    System.out.println("  - " + item.getProductId() + " x" + item.getQuantity()));
                
                // Process the order asynchronously
                orderProcessor.processOrder(order);
                
            } catch (Exception e) {
                System.err.println("Error generating order: " + e.getMessage());
            }
        }, 2000, arrivalRateMs, TimeUnit.MILLISECONDS);
        
        System.out.println("📦 Order generation started (every " + arrivalRateMs + "ms)");
    }
    
    /**
     * Listen for external orders via ZeroMQ
     */
    private void startOrderListener() {
        Thread listenerThread = new Thread(() -> {
            System.out.println("👂 Marketplace " + id + " listening for external orders...");
            
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String message = socket.recvStr();
                    if (message != null) {
                        System.out.println("\n📨 EXTERNAL ORDER RECEIVED: " + message);
                        
                        // Parse order
                        Order order = gson.fromJson(message, Order.class);
                        
                        // Process order asynchronously
                        orderProcessor.processOrder(order);
                        
                        // Send immediate acknowledgment
                        String response = "Order " + order.getOrderId() + " received by marketplace " + id + 
                                        " and processing started";
                        socket.send(response);
                        
                        System.out.println("✅ Acknowledgment sent for order " + order.getOrderId());
                    }
                } catch (Exception e) {
                    System.err.println("Error processing external order: " + e.getMessage());
                    // Send error response
                    try {
                        socket.send("Error processing order: " + e.getMessage());
                    } catch (Exception ex) {
                        System.err.println("Failed to send error response: " + ex.getMessage());
                    }
                }
            }
        });
        
        listenerThread.setDaemon(false);
        listenerThread.start();
    }

    public void stop() {
        System.out.println("Shutting down marketplace " + id);
        scheduler.shutdown();
        orderProcessor.shutdown();
        socket.close();
        context.close();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java -cp target/distributed-marketplace-1.0-SNAPSHOT.jar marketplace.Marketplace <marketplace_id>");
            return;
        }
        
        String marketplaceId = args[0];
        String bindAddress = "tcp://*:5555";
        
        // Load configuration
        Configuration config;
        try {
            config = new Configuration("config/marketplace.properties");
        } catch (Exception e) {
            System.out.println("Could not load config file, using defaults");
            config = new Configuration("") {
                @Override
                public String get(String key) {
                    return null;
                }
                
                @Override
                public int getInt(String key, int defaultValue) {
                    return defaultValue;
                }
            };
        }
        
        Marketplace marketplace = new Marketplace(marketplaceId, bindAddress, config);
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down marketplace " + marketplaceId);
            marketplace.stop();
        }));
        
        System.out.println("🚀 Starting Distributed Marketplace System");
        System.out.println("=========================================");
        marketplace.start();
        
        // Keep main thread alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            System.out.println("Marketplace interrupted");
        }
    }
}