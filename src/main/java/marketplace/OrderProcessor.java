package marketplace;

import common.Order;
import common.OrderItem;
import network.Message;
import network.MessageType;
import monitoring.SystemMonitor;
import org.zeromq.SocketType;
import org.zeromq.ZMQ;
import org.zeromq.ZContext;
import com.google.gson.Gson;

import java.util.*;
import java.util.concurrent.*;

public class OrderProcessor {
    private final ZContext context;
    private final Gson gson;
    private final SagaCoordinator sagaCoordinator;
    private final ExecutorService executorService;
    private final Map<String, String> sellerAddresses;
    
    public OrderProcessor(SagaCoordinator sagaCoordinator) {
        this.context = new ZContext();
        this.gson = new Gson();
        this.sagaCoordinator = sagaCoordinator;
        this.executorService = Executors.newCachedThreadPool();
        this.sellerAddresses = new HashMap<>();
        
        initializeSellerAddresses();
    }
    
    private void initializeSellerAddresses() {
        sellerAddresses.put("S1", "tcp://localhost:5557");
        sellerAddresses.put("S2", "tcp://localhost:5558");
        sellerAddresses.put("S3", "tcp://localhost:5559");
        sellerAddresses.put("S4", "tcp://localhost:5560");
        sellerAddresses.put("S5", "tcp://localhost:5561");
    }
    
    public void processOrder(Order order) {
        long startTime = System.currentTimeMillis();
        SystemMonitor.getInstance().orderReceived();
        
        System.out.println("Starting SAGA for order: " + order.getOrderId());
        
        List<String> productIds = order.getItems().stream()
            .map(OrderItem::getProductId)
            .toList();
        
        SagaTransaction saga = new SagaTransaction(order.getOrderId(), productIds);
        sagaCoordinator.startTransaction(order.getOrderId(), saga);
        
        CompletableFuture<Boolean> reservationFuture = reserveProducts(order);
        
        reservationFuture.thenAccept(success -> {
            long processingTime = System.currentTimeMillis() - startTime;
            
            if (success) {
                commitOrder(order);
                SystemMonitor.getInstance().orderSucceeded(order.getOrderId(), processingTime);
            } else {
                rollbackOrder(order);
                SystemMonitor.getInstance().orderFailed(order.getOrderId(), "Reservation failed");
            }
            sagaCoordinator.completeTransaction(order.getOrderId());
        }).exceptionally(throwable -> {
            long processingTime = System.currentTimeMillis() - startTime;
            System.err.println("Error in SAGA for order " + order.getOrderId() + " (processing time: " + processingTime + "ms): " + throwable.getMessage());
            rollbackOrder(order);
            SystemMonitor.getInstance().orderFailed(order.getOrderId(), "Exception: " + throwable.getMessage());
            sagaCoordinator.completeTransaction(order.getOrderId());
            return null;
        }); // <- DIESE KLAMMER UND SEMIKOLON FEHLTEN!
    }
    
    private CompletableFuture<Boolean> reserveProducts(Order order) {
        List<CompletableFuture<Boolean>> reservationFutures = new ArrayList<>();
        
        Map<String, List<OrderItem>> itemsBySeller = groupItemsBySeller(order.getItems());
        
        for (Map.Entry<String, List<OrderItem>> entry : itemsBySeller.entrySet()) {
            String sellerId = entry.getKey();
            List<OrderItem> items = entry.getValue();
            
            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> 
                reserveFromSeller(sellerId, items, order.getOrderId()), executorService);
            
            reservationFutures.add(future);
        }
        
        return CompletableFuture.allOf(reservationFutures.toArray(new CompletableFuture[0]))
            .thenApply(v -> reservationFutures.stream().allMatch(CompletableFuture::join));
    }
    
    private boolean reserveFromSeller(String sellerId, List<OrderItem> items, String orderId) {
        String sellerAddress = sellerAddresses.get(sellerId);
        if (sellerAddress == null) {
            System.err.println("Unknown seller: " + sellerId);
            return false;
        }
        
        try (ZMQ.Socket socket = context.createSocket(SocketType.REQ)) {
            socket.connect(sellerAddress);
            socket.setReceiveTimeOut(3000);
            
            boolean allReserved = true;
            
            for (OrderItem item : items) {
                String payload = item.getProductId() + ":" + item.getQuantity();
                Message request = new Message(MessageType.RESERVE_PRODUCT, payload, orderId);
                
                System.out.println("Sending to " + sellerId + ": " + gson.toJson(request));
                socket.send(gson.toJson(request));
                
                String responseJson = socket.recvStr();
                if (responseJson == null) {
                    System.err.println("Timeout waiting for response from " + sellerId);
                    allReserved = false;
                    break;
                }
                
                Message response = gson.fromJson(responseJson, Message.class);
                System.out.println("Response from " + sellerId + ": " + responseJson);
                
                if (response.getType() != MessageType.RESERVE_CONFIRM) {
                    System.out.println("Reservation REJECTED by " + sellerId + " for " + item.getProductId());
                    allReserved = false;
                    break;
                }
                
                System.out.println("Reservation CONFIRMED by " + sellerId + " for " + item.getProductId());
            }
            
            return allReserved;
            
        } catch (Exception e) {
            System.err.println("Error communicating with seller " + sellerId + ": " + e.getMessage());
            return false;
        }
    }
    
    private void commitOrder(Order order) {
        System.out.println("COMMITTING order: " + order.getOrderId());
        
        Map<String, List<OrderItem>> itemsBySeller = groupItemsBySeller(order.getItems());
        
        for (String sellerId : itemsBySeller.keySet()) {
            CompletableFuture.runAsync(() -> sendCommitToSeller(sellerId, order.getOrderId()), executorService);
        }
        
        System.out.println("✅ Order " + order.getOrderId() + " SUCCESSFULLY COMPLETED!");
    }
    
    private void rollbackOrder(Order order) {
        System.out.println("ROLLING BACK order: " + order.getOrderId());
        
        Map<String, List<OrderItem>> itemsBySeller = groupItemsBySeller(order.getItems());
        
        for (String sellerId : itemsBySeller.keySet()) {
            CompletableFuture.runAsync(() -> sendRollbackToSeller(sellerId, order.getOrderId()), executorService);
        }
        
        System.out.println("❌ Order " + order.getOrderId() + " ROLLED BACK!");
    }
    
    private void sendCommitToSeller(String sellerId, String orderId) {
        sendTransactionMessage(sellerId, orderId, MessageType.COMMIT_ORDER, "commit");
    }
    
    private void sendRollbackToSeller(String sellerId, String orderId) {
        sendTransactionMessage(sellerId, orderId, MessageType.ROLLBACK_ORDER, "rollback");
    }
    
    private void sendTransactionMessage(String sellerId, String orderId, MessageType messageType, String action) {
        String sellerAddress = sellerAddresses.get(sellerId);
        if (sellerAddress == null) {
            System.err.println("Unknown seller: " + sellerId);
            return;
        }
        
        try (ZMQ.Socket socket = context.createSocket(SocketType.REQ)) {
            socket.connect(sellerAddress);
            socket.setReceiveTimeOut(3000);
            
            Message message = new Message(messageType, action, orderId);
            socket.send(gson.toJson(message));
            
            String response = socket.recvStr();
            if (response != null) {
                System.out.println("Seller " + sellerId + " " + action + " response: " + response);
            } else {
                System.err.println("Timeout waiting for " + action + " response from " + sellerId);
            }
            
        } catch (Exception e) {
            System.err.println("Error sending " + action + " to seller " + sellerId + ": " + e.getMessage());
        }
    }
    
    private Map<String, List<OrderItem>> groupItemsBySeller(List<OrderItem> items) {
        Map<String, List<OrderItem>> result = new HashMap<>();
        
        for (OrderItem item : items) {
            String sellerId = getSellerForProduct(item.getProductId());
            result.computeIfAbsent(sellerId, k -> new ArrayList<>()).add(item);
        }
        
        return result;
    }
    
    private String getSellerForProduct(String productId) {
        return switch (productId) {
            case "P1", "P2" -> "S1";
            case "P3", "P4" -> "S2";
            case "P5" -> "S3";
            default -> "S1";
        };
    }
    
    public void shutdown() {
        executorService.shutdown();
        context.close();
    }
}