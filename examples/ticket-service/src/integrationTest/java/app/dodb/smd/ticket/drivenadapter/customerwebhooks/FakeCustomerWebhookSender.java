package app.dodb.smd.ticket.drivenadapter.customerwebhooks;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

class FakeCustomerWebhookSender implements CustomerWebhookSender {

    private final List<CustomerWebhook> attempts = new CopyOnWriteArrayList<>();
    private final Map<UUID, AtomicInteger> remainingFailures = new ConcurrentHashMap<>();

    @Override
    public void send(CustomerWebhook webhook) {
        attempts.add(webhook);
        var failures = remainingFailures.get(webhook.event().ticketId());
        if (failures != null && failures.getAndDecrement() > 0) {
            throw new IllegalStateException("Customer endpoint unavailable for ticket " + webhook.event().ticketId());
        }
    }

    void failNextSends(UUID ticketId, int times) {
        remainingFailures.put(ticketId, new AtomicInteger(times));
    }

    List<CustomerWebhook> attemptsFor(UUID ticketId) {
        return attempts.stream()
            .filter(webhook -> webhook.event().ticketId().equals(ticketId))
            .toList();
    }
}
