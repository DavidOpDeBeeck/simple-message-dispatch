package app.dodb.smd.spring.eventstore.aggregate;

public interface AuditLog {

    void record(String entry);
}
