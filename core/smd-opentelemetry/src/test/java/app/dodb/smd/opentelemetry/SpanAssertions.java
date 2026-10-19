package app.dodb.smd.opentelemetry;

import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

final class SpanAssertions {

    private SpanAssertions() {
    }

    static Consumer<List<? extends LinkData>> linksTo(SpanData target) {
        return links -> assertThat(links).singleElement().satisfies(link -> {
            assertThat(link.getSpanContext().getTraceId()).isEqualTo(target.getTraceId());
            assertThat(link.getSpanContext().getSpanId()).isEqualTo(target.getSpanId());
        });
    }

    static String traceparentOf(SpanData span) {
        return "00-" + span.getTraceId() + "-" + span.getSpanId() + "-01";
    }
}
