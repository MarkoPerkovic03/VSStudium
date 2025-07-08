package seller;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class ProductInventory {
    private final Map<String, Integer> inventory = new ConcurrentHashMap<>();

    public void addProduct(String productId, int quantity) {
        inventory.put(productId, quantity);
        System.out.println("Added product " + productId + " with quantity " + quantity);
    }

    public synchronized boolean reserveProduct(String productId, int quantity) {
        Integer currentStock = inventory.get(productId);
        if (currentStock == null) {
            System.out.println("Product " + productId + " not found in inventory");
            return false;
        }
        
        if (currentStock < quantity) {
            System.out.println("Insufficient stock for " + productId + ": available=" + currentStock + ", requested=" + quantity);
            return false;
        }
        
        int newStock = currentStock - quantity;
        inventory.put(productId, newStock);
        System.out.println("Reserved " + quantity + " units of " + productId + ". Remaining stock: " + newStock);
        return true;
    }

    public synchronized void releaseProduct(String productId, int quantity) {
        Integer currentStock = inventory.get(productId);
        if (currentStock != null) {
            int newStock = currentStock + quantity;
            inventory.put(productId, newStock);
            System.out.println("Released " + quantity + " units of " + productId + ". New stock: " + newStock);
        }
    }

    public int getStock(String productId) {
        return inventory.getOrDefault(productId, 0);
    }
    
    public void printInventory() {
        System.out.println("Current inventory: " + inventory);
    }
}