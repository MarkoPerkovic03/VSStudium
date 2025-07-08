package monitoring;

import common.Order;
import common.OrderGenerator;
import org.zeromq.SocketType;
import org.zeromq.ZMQ;
import org.zeromq.ZContext;
import com.google.gson.Gson;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadTestRunner {
    
    private static final Gson gson = new Gson();
    
    public static void runLoadTest(int numberOfOrders, int concurrentThreads, String marketplaceAddress) {
        System.out.println("=== LOAD TEST STARTING ===");
        System.out.println("Orders: " + numberOfOrders);
        System.out.println("Threads: " + concurrentThreads); 
        System.out.println("Target: " + marketplaceAddress);
        System.out.println("===========================");
        
        ExecutorService executor = Executors.newFixedThreadPool(concurrentThreads);
        AtomicInteger completedOrders = new AtomicInteger(0);
        AtomicInteger successfulOrders = new AtomicInteger(0);
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < numberOfOrders; i++) {
            final int orderNum = i + 1;
            executor.submit(() -> {
                try (ZContext context = new ZContext()) {
                    ZMQ.Socket socket = context.createSocket(SocketType.REQ);
                    socket.connect(marketplaceAddress);
                    socket.setReceiveTimeOut(5000); // 5 second timeout
                    
                    // Generate and send order
                    Order order = OrderGenerator.generateOrder();
                    String orderJson = gson.toJson(order);
                    
                    System.out.println("[Thread-" + Thread.currentThread().getId() + "] Sending order #" + orderNum + ": " + order.getOrderId());
                    
                    socket.send(orderJson);
                    String response = socket.recvStr();
                    
                    if (response != null) {
                        successfulOrders.incrementAndGet();
                        System.out.println("[Thread-" + Thread.currentThread().getId() + "] Order #" + orderNum + " SUCCESS: " + response);
                    } else {
                        System.err.println("[Thread-" + Thread.currentThread().getId() + "] Order #" + orderNum + " TIMEOUT");
                    }
                    
                } catch (Exception e) {
                    System.err.println("[Thread-" + Thread.currentThread().getId() + "] Order #" + orderNum + " ERROR: " + e.getMessage());
                } finally {
                    int completed = completedOrders.incrementAndGet();
                    if (completed % 10 == 0) {
                        System.out.println("Progress: " + completed + "/" + numberOfOrders + " orders completed");
                    }
                }
            });
        }
        
        executor.shutdown();
        try {
            boolean finished = executor.awaitTermination(60, TimeUnit.SECONDS);
            if (!finished) {
                System.err.println("Load test timed out after 60 seconds");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        System.out.println("\n=== LOAD TEST RESULTS ===");
        System.out.println("Total Orders: " + numberOfOrders);
        System.out.println("Successful Orders: " + successfulOrders.get());
        System.out.println("Failed Orders: " + (numberOfOrders - successfulOrders.get()));
        System.out.println("Success Rate: " + String.format("%.2f", (successfulOrders.get() * 100.0 / numberOfOrders)) + "%");
        System.out.println("Duration: " + duration + "ms");
        System.out.println("Orders/sec: " + String.format("%.2f", (numberOfOrders * 1000.0 / duration)));
        System.out.println("=========================");
    }
    
    public static void main(String[] args) {
        int orders = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        String address = args.length > 2 ? args[2] : "tcp://localhost:5555";
        
        runLoadTest(orders, threads, address);
    }
}