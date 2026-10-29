package app.dodb.smd.ticket.drivenadapter.customerwebhooks;

public interface CustomerWebhookSender {

    void send(CustomerWebhook webhook);
}
