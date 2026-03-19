package com.perfplatform.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Random;

@RestController
@RequestMapping("/api")
public class DemoController {

    private final Random random = new Random();

    /**
     * Fast endpoint. 
     * Typical latency: 5-25ms.
     * Error rate: < 0.1%
     */
    @GetMapping("/users")
    public Map<String, String> getUsers() throws InterruptedException {
        // Simulate minor DB fetch latency
        Thread.sleep(5 + random.nextInt(20));
        
        // Rare random error
        if (random.nextInt(1000) == 0) {
            throw new RuntimeException("Rare transient database error fetching users");
        }

        return Map.of("status", "success", "message", "Fetched 10 users.");
    }

    /**
     * Slow, complex endpoint simulating a payment gateway call or heavy transaction.
     * Typical latency: 150-600ms.
     * Error rate: ~5% (simulate occasional downstream failures)
     */
    @PostMapping("/payments")
    public Map<String, String> processPayment() throws InterruptedException {
        // Simulate heavier processing
        Thread.sleep(150 + random.nextInt(450));

        // 5% error rate simulation
        if (random.nextInt(100) < 5) {
            throw new RuntimeException("Simulated downstream payment gateway timeout.");
        }

        return Map.of("status", "success", "transactionId", "txn_" + random.nextInt(999999));
    }
}
