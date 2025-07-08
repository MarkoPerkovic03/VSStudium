package marketplace;

import org.zeromq.SocketType;
import org.zeromq.ZMQ;
import org.zeromq.ZContext;
import com.google.gson.Gson;
import common.Order;
import common.OrderItem;
import common.OrderGenerator;

import java.util.Arrays;
import java.util.Scanner;

public class TestClient {
    private static final Gson gson = new Gson();
    
    public static void main(String[] args) {
        System.out.println("=== Distributed Marketplace Test Client ===");
        System.out.println();
        
        // MARKETPLACE AUSWAHL
        String marketplacePort = "5555"; // Default MP1
        String marketplaceId = "MP1";
        
        if (args.length > 0) {
            marketplaceId = args[0];
            int portNumber = 5554 + Integer.parseInt(marketplaceId.substring(2));
            marketplacePort = String.valueOf(portNumber);
            System.out.println("Connecting to " + marketplaceId + " on port " + marketplacePort);
        } else {
            System.out.println("Connecting to MP1 on port 5555 (default)");
            System.out.println("To connect to MP2: java -cp target/distributed-marketplace-1.0-SNAPSHOT.jar marketplace.TestClient MP2");
        }
        
        try (ZContext context = new ZContext();
             Scanner scanner = new Scanner(System.in)) {
            
            ZMQ.Socket socket = context.createSocket(SocketType.REQ);
            socket.connect("tcp://localhost:" + marketplacePort);
            socket.setReceiveTimeOut(5000); // 5 second timeout
            
            System.out.println("Connected to Marketplace " + marketplaceId + " on tcp://localhost:" + marketplacePort);
            System.out.println();
            
            while (true) {
                System.out.println("Choose an option:");
                System.out.println("1. Send predefined test order");
                System.out.println("2. Send random generated order");
                System.out.println("3. Send multiple orders (stress test)");
                System.out.println("4. Send order that will likely fail (high quantities)");
                System.out.println("5. Exit");
                System.out.print("Enter choice (1-5): ");
                
                String choice = scanner.nextLine();
                
                switch (choice) {
                    case "1":
                        sendPredefinedOrder(socket);
                        break;
                    case "2":
                        sendRandomOrder(socket);
                        break;
                    case "3":
                        sendMultipleOrders(socket, scanner);
                        break;
                    case "4":
                        sendFailingOrder(socket);
                        break;
                    case "5":
                        System.out.println("Goodbye!");
                        return;
                    default:
                        System.out.println("Invalid choice, please try again.");
                        continue;
                }
                
                System.out.println();
                System.out.println("Press Enter to continue...");
                scanner.nextLine();
                System.out.println();
            }
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void sendPredefinedOrder(ZMQ.Socket socket) {
        System.out.println("📦 Sending predefined test order...");
        
        Order order = new Order(
            "TEST-" + System.currentTimeMillis(),
            "C1234",
            Arrays.asList(
                new OrderItem("P1", 2),
                new OrderItem("P3", 1),
                new OrderItem("P5", 1)
            )
        );
        
        sendOrderAndReceiveResponse(socket, order);
    }
    
    private static void sendRandomOrder(ZMQ.Socket socket) {
        System.out.println("🎲 Sending random generated order...");
        
        Order order = OrderGenerator.generateOrder();
        sendOrderAndReceiveResponse(socket, order);
    }
    
    private static void sendMultipleOrders(ZMQ.Socket socket, Scanner scanner) {
        System.out.print("How many orders to send? ");
        try {
            int count = Integer.parseInt(scanner.nextLine());
            
            System.out.println("📈 Sending " + count + " orders...");
            
            for (int i = 0; i < count; i++) {
                Order order = OrderGenerator.generateOrder();
                System.out.println("Sending order " + (i + 1) + "/" + count + ": " + order.getOrderId());
                sendOrderAndReceiveResponse(socket, order);
                
                // Small delay between orders
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
            System.out.println("✅ Completed sending " + count + " orders");
            
        } catch (NumberFormatException e) {
            System.out.println("Invalid number format");
        }
    }
    
    private static void sendFailingOrder(ZMQ.Socket socket) {
        System.out.println("💥 Sending order with high quantities (likely to fail)...");
        
        Order order = new Order(
            "FAIL-TEST-" + System.currentTimeMillis(),
            "C9999",
            Arrays.asList(
                new OrderItem("P1", 50),  // Very high quantity
                new OrderItem("P2", 100), // Very high quantity
                new OrderItem("P3", 75)   // Very high quantity
            )
        );
        
        sendOrderAndReceiveResponse(socket, order);
    }
    
    private static void sendOrderAndReceiveResponse(ZMQ.Socket socket, Order order) {
        try {
            String orderJson = gson.toJson(order);
            
            System.out.println("📋 Order Details:");
            System.out.println("  Order ID: " + order.getOrderId());
            System.out.println("  Customer: " + order.getCustomerId());
            System.out.println("  Items:");
            for (OrderItem item : order.getItems()) {
                System.out.println("    - " + item.getProductId() + " x" + item.getQuantity());
            }
            
            System.out.println("📤 Sending order...");
            
            // Send order
            socket.send(orderJson);
            
            // Receive response
            String response = socket.recvStr();
            if (response != null) {
                System.out.println("📥 Response: " + response);
            } else {
                System.out.println("⏰ Timeout - no response received within 5 seconds");
            }
            
        } catch (Exception e) {
            System.err.println("❌ Error sending order: " + e.getMessage());
        }
    }
}