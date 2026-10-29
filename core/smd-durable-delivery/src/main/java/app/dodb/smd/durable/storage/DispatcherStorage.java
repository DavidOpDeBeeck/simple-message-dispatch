package app.dodb.smd.durable.storage;

import app.dodb.smd.serialization.SerializedEvent;

import java.time.Duration;
import java.util.List;

public interface DispatcherStorage {

    void store(String dispatcher, SerializedEvent event);

    int registerDispatcher(String dispatcher, int laneCount);

    void registerSubscriber(String dispatcher, String subscriber);

    List<PendingDelivery> claimBatch(String dispatcher, String subscriber, int batchSize);

    void markDelivered(String dispatcher, String subscriber, PendingDelivery delivery);

    void recordFailure(String dispatcher, String subscriber, PendingDelivery delivery, Duration retryDelay, Throwable failure);
}
