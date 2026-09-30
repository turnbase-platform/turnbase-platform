package io.turnbase.platform.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

/**
 * {@code observability.md} §4: each inbound action frame is a span, parented on {@code
 * traceparent}.
 */
class WebSocketTraceInterceptorTest {

  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String PARENT_ID = "00f067aa0ba902b7";

  private final List<SpanData> finished = new ArrayList<>();
  private final SdkTracerProvider provider =
      SdkTracerProvider.builder()
          .addSpanProcessor(
              SimpleSpanProcessor.create(
                  new SpanExporter() {
                    @Override
                    public CompletableResultCode export(Collection<SpanData> spans) {
                      finished.addAll(spans);
                      return CompletableResultCode.ofSuccess();
                    }

                    @Override
                    public CompletableResultCode flush() {
                      return CompletableResultCode.ofSuccess();
                    }

                    @Override
                    public CompletableResultCode shutdown() {
                      return CompletableResultCode.ofSuccess();
                    }
                  }))
          .build();
  private final WebSocketTraceInterceptor interceptor =
      new WebSocketTraceInterceptor(() -> provider.get("test"));
  private final MessageChannel channel = mock(MessageChannel.class);
  private final MessageHandler annotationHandler = mock(SimpAnnotationMethodMessageHandler.class);

  private Message<byte[]> frame(String destination, @Nullable String traceparent) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
    accessor.setDestination(destination);
    if (traceparent != null) {
      accessor.addNativeHeader("traceparent", traceparent);
    }
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  @Test
  void anActionFrameBecomesAChildOfTheClientsTrace() {
    Message<?> message = frame("/app/game.s1.action", "00-" + TRACE_ID + "-" + PARENT_ID + "-01");

    interceptor.beforeHandle(message, channel, annotationHandler);
    assertThat(Span.current().getSpanContext().getTraceId()).isEqualTo(TRACE_ID);
    interceptor.afterMessageHandled(message, channel, annotationHandler, null);

    assertThat(finished).hasSize(1);
    assertThat(finished.get(0).getName()).isEqualTo("STOMP SEND /app/game.s1.action");
    assertThat(finished.get(0).getTraceId()).isEqualTo(TRACE_ID);
    assertThat(finished.get(0).getParentSpanId()).isEqualTo(PARENT_ID);
    assertThat(Span.current().getSpanContext().isValid()).isFalse();
  }

  @Test
  void aFrameWithoutTraceparentStartsItsOwnTrace() {
    Message<?> message = frame("/app/game.s1.sync", null);

    interceptor.beforeHandle(message, channel, annotationHandler);
    interceptor.afterMessageHandled(message, channel, annotationHandler, null);

    assertThat(finished).hasSize(1);
    assertThat(finished.get(0).getParentSpanContext().isValid()).isFalse();
  }

  @Test
  void otherHandlersAndNonApplicationDestinationsAreNotTraced() {
    Message<?> action = frame("/app/game.s1.action", null);
    interceptor.beforeHandle(action, channel, mock(MessageHandler.class));
    interceptor.afterMessageHandled(action, channel, mock(MessageHandler.class), null);
    Message<?> subscribe = frame("/topic/game.s1", null);
    interceptor.beforeHandle(subscribe, channel, annotationHandler);
    interceptor.afterMessageHandled(subscribe, channel, annotationHandler, null);

    assertThat(finished).isEmpty();
  }
}
