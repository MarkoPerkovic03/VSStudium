package network;

import org.zeromq.SocketType;
import org.zeromq.ZMQ;
import org.zeromq.ZContext;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple connection pool for ZeroMQ sockets
 * This is a basic implementation for demonstration purposes
 */
public class ZMQConnectionPool {
    
    private final ZContext context;
    private final Map<String, BlockingQueue<ZMQ.Socket>> pools;
    private final int maxPoolSize;
    
    public ZMQConnectionPool(int maxPoolSize) {
        this.context = new ZContext();
        this.pools = new ConcurrentHashMap<>();
        this.maxPoolSize = maxPoolSize;
    }
    
    /**
     * Get a socket from the pool or create a new one
     */
    public ZMQ.Socket getSocket(String address, SocketType socketType) {
        String poolKey = address + "_" + socketType.name();
        
        BlockingQueue<ZMQ.Socket> pool = pools.computeIfAbsent(poolKey, 
            k -> new LinkedBlockingQueue<>());
        
        ZMQ.Socket socket = pool.poll();
        if (socket == null) {
            socket = context.createSocket(socketType);
            socket.connect(address);
        }
        
        return socket;
    }
    
    /**
     * Return a socket to the pool
     */
    public void returnSocket(String address, SocketType socketType, ZMQ.Socket socket) {
        String poolKey = address + "_" + socketType.name();
        
        BlockingQueue<ZMQ.Socket> pool = pools.get(poolKey);
        if (pool != null && pool.size() < maxPoolSize) {
            pool.offer(socket);
        } else {
            // Pool is full, close the socket
            socket.close();
        }
    }
    
    /**
     * Close all connections and clean up
     */
    public void shutdown() {
        for (BlockingQueue<ZMQ.Socket> pool : pools.values()) {
            ZMQ.Socket socket;
            while ((socket = pool.poll()) != null) {
                socket.close();
            }
        }
        pools.clear();
        context.close();
    }
}