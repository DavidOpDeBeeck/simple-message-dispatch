package app.dodb.smd.test;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.deadline.DeadlineId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.time.SystemTimeProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static java.time.Duration.ofHours;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeadlineSchedulerStubTest {

    private static final Instant NOW = Instant.parse("2026-10-22T09:00:00Z");

    private final TimeProviderStub time = new TimeProviderStub(new SystemTimeProvider());
    private final DeadlineSchedulerStub deadlines = new DeadlineSchedulerStub(time);

    DeadlineSchedulerStubTest() {
        time.stubTime(NOW);
    }

    @Test
    void schedule_withDelayAndInstant_capturesDeadlinesOrderedByDueTime() {
        var later = deadlines.schedule(new CommandForTest("later"), ofHours(4));
        var sooner = deadlines.schedule(new CommandForTest("sooner"), NOW.plusSeconds(1));

        assertThat(deadlines.getDeadlines()).containsExactly(
            new ScheduledDeadline(sooner, NOW.plusSeconds(1), new CommandForTest("sooner")),
            new ScheduledDeadline(later, NOW.plus(ofHours(4)), new CommandForTest("later"))
        );
    }

    @Test
    void schedule_withNegativeDelay_throws() {
        assertThatThrownBy(() -> deadlines.schedule(new CommandForTest("late"), ofHours(-1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Deadline delay must not be negative: command=" + CommandForTest.class.getName() + ", delay=PT-1H");
    }

    @Test
    void cancel_pendingDeadline_removesItOnce() {
        var deadlineId = deadlines.schedule(new CommandForTest("reminder"), ofHours(4));

        assertThat(deadlines.cancel(deadlineId)).isTrue();
        assertThat(deadlines.cancel(deadlineId)).isFalse();
        assertThat(deadlines.getDeadlines()).isEmpty();
    }

    @Test
    void fireDue_sendsOnlyDueDeadlinesInDueOrderAndRemovesThem() {
        deadlines.schedule(new CommandForTest("second"), NOW.plusSeconds(2));
        deadlines.schedule(new CommandForTest("first"), NOW.plusSeconds(1));
        var later = deadlines.schedule(new CommandForTest("later"), NOW.plusSeconds(10));
        var gateway = new RecordingCommandGateway(_ -> {
        });
        time.stubTime(NOW.plusSeconds(2));

        var fired = deadlines.fireDue(gateway);

        assertThat(gateway.commands).containsExactly(new CommandForTest("first"), new CommandForTest("second"));
        assertThat(fired).extracting(ScheduledDeadline::command).containsExactlyElementsOf(gateway.commands);
        assertThat(deadlines.getDeadlines()).extracting(ScheduledDeadline::deadlineId).containsExactly(later);
    }

    @Test
    void fireDue_whenFiredCommandCancelsAnotherDueDeadline_skipsIt() {
        var second = new ArrayList<DeadlineId>();
        deadlines.schedule(new CommandForTest("first"), NOW);
        second.add(deadlines.schedule(new CommandForTest("second"), NOW));
        var gateway = new RecordingCommandGateway(_ -> deadlines.cancel(second.getFirst()));

        deadlines.fireDue(gateway);

        assertThat(gateway.commands).containsExactly(new CommandForTest("first"));
    }

    @Test
    void fireDue_whenFiredCommandSchedulesDueDeadline_leavesItForNextRound() {
        deadlines.schedule(new CommandForTest("first"), NOW);
        var gateway = new RecordingCommandGateway(command -> {
            if (command.equals(new CommandForTest("first"))) {
                deadlines.schedule(new CommandForTest("follow-up"), NOW);
            }
        });

        deadlines.fireDue(gateway);
        assertThat(gateway.commands).containsExactly(new CommandForTest("first"));

        deadlines.fireDue(gateway);
        assertThat(gateway.commands).containsExactly(new CommandForTest("first"), new CommandForTest("follow-up"));
    }

    @Test
    void reset_withPendingDeadlines_clearsThem() {
        deadlines.schedule(new CommandForTest("reminder"), ofHours(4));

        deadlines.reset();

        assertThat(deadlines.getDeadlines()).isEmpty();
    }

    private record CommandForTest(String value) implements Command<Void> {
    }

    private static class RecordingCommandGateway implements CommandGateway {

        private final List<Command<?>> commands = new ArrayList<>();
        private final Consumer<Command<?>> handler;

        private RecordingCommandGateway(Consumer<Command<?>> handler) {
            this.handler = handler;
        }

        @Override
        public <R, C extends Command<R>> R send(C command) {
            commands.add(command);
            handler.accept(command);
            return null;
        }

        @Override
        public <R, C extends Command<R>> R send(C command, Metadata metadata) {
            return send(command);
        }

        @Override
        public <R, C extends Command<R>> R send(CommandMessage<R, C> commandMessage) {
            return send(commandMessage.payload());
        }
    }
}
