package at.haha007.edenmcp.hooks.spark;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SparkCommandRunnerTest {
    @Test
    void settleWaitsForOutputArrivingAfterTheFirstLine() throws Exception {
        AtomicInteger received = new AtomicInteger(1); // acknowledgement already captured
        Thread producer = new Thread(() -> {
            try {
                Thread.sleep(60);
                received.incrementAndGet(); // detailed output arrives later
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        producer.start();

        long started = System.nanoTime();
        SparkCommandRunner.awaitQuiet(received::get, 100);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        producer.join();

        assertTrue(elapsedMillis >= 45,
                "expected to wait for follow-up output, but returned after " + elapsedMillis + "ms");
        assertTrue(elapsedMillis < 2_000,
                "settle must stay bounded, but waited " + elapsedMillis + "ms");
    }

    @Test
    void settleReturnsAfterOneQuietWindowWithoutOutput() throws Exception {
        long started = System.nanoTime();
        SparkCommandRunner.awaitQuiet(() -> 0, 10);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis >= 5, "returned before the quiet window elapsed: " + elapsedMillis + "ms");
        assertTrue(elapsedMillis < 1_000, "unexpectedly long settle: " + elapsedMillis + "ms");
    }
}
