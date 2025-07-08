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
    private final ZMQ.Socket serverSocket;
    private final Gson gson;
    private final SagaCoordinator sagaCoordinator;
    private final OrderProcessor orderProcessor;
    private final ScheduledExecutorService scheduler;
    private final Configuration config;
    private volatile boolean running = true;

    public Marketplace(String id, String bindAddress, Configuration config) {
        this.id = id;
        this.context = new ZContext();
        this.serverSocket = context.createSocket(SocketType.REP);
        this.serverSocket.bind(bindAddress);
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
        SystemMonitor.getInstance().startPeriodicReporting(30);
        
        // Start automatic order generation
        startOrderGeneration();
        
        // Start listening for external orders in separate thread
        Thread listenerThread = new Thread(this::startOrderListener);
        listenerThread.setDaemon(false);
        listenerThread.start();
    }
    
    private void startOrderGeneration() {
        int arrivalRateMs = config.getInt("order.arrival.rate.ms", 2000);
        
        scheduler.scheduleAtFixedRate(() -> {
            if (running) {
                try {
                    Order order = OrderGenerator.generateOrder();
                    System.out.println("\n🆕 AUTO-GENERATED ORDER: " + order.getOrderId());
                    System.out.println("Customer: " + order.getCustomerId());
                    System.out.println("Items: " + order.getItems().size());
                    order.getItems().forEach(item -> 
                        System.out.println("  - " + item.getProductId() + " x" + item.getQuantity()));
                    
                    orderProcessor.processOrder(order);
                    
                } catch (Exception e) {
                    System.err.println("Error generating order: " + e.getMessage());
                }
            }
        }, 2000, arrivalRateMs, TimeUnit.MILLISECONDS);
        
        System.out.println("📦 Order generation started (every " + arrivalRateMs + "ms)");
    }
    
    private void startOrderListener() {
        System.out.println("👂 Marketplace " + id + " listening for external orders...");
        
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                String message = serverSocket.recvStr();
                if (message != null && running) {
                    System.out.println("\n📨 EXTERNAL ORDER RECEIVED: " + message);
                    
                    Order order = gson.fromJson(message, Order.class);
                    orderProcessor.processOrder(order);
                    
                    String response = "Order " + order.getOrderId() + " received by marketplace " + id + 
                                    " and processing started";
                    serverSocket.send(response);
                    
                    System.out.println("✅ Acknowledgment sent for order " + order.getOrderId());
                }
            } catch (Exception e) {
                if (running) {
                    System.err.println("Error processing external order: " + e.getMessage());
                    try {
                        serverSocket.send("Error processing order: " + e.getMessage());
                    } catch (Exception ex) {
                        System.err.println("Failed to send error response: " + ex.getMessage());
                    }
                }
            }
        }
    }

    public void stop() {
        System.out.println("Shutting down marketplace " + id);
        running = false;
        scheduler.shutdown();
        orderProcessor.shutdown();
        serverSocket.close();
        context.close();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java -cp target/distributed-marketplace-1.0-SNAPSHOT.jar marketplace.Marketplace <marketplace_id>");
            return;
        }
        
        String marketplaceId = args[0];
        
        // PORT-SCHEMA: MP1=5555, MP2=5556, MP3=5557, etc.
        int portNumber = 5554 + Integer.parseInt(marketplaceId.substring(2)); // MP1->5555, MP2->5556
        String bindAddress = "tcp://*:" + portNumber;
        
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
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down marketplace " + marketplaceId);
            marketplace.stop();
        }));
        
        System.out.println("🚀 Starting Distributed Marketplace System");
        System.out.println("=========================================");
        System.out.println("Marketplace ID: " + marketplaceId);
        System.out.println("Bind Address: " + bindAddress);
        System.out.println("=========================================");
        marketplace.start();
        
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            System.out.println("Marketplace interrupted");
        }
    }
}