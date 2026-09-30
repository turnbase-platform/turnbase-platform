package io.turnbase.platform.websocket;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;

/**
 * {@code observability.md} §4 *The STOMP frame* (FR-TRACE-002): a WebSocket is one long-lived HTTP
 * upgrade, so the agent sees a single span for the handshake and nothing for the frames inside it.
 * This starts a server span per inbound {@code /app/**} frame, parented on the frame's {@code
 * traceparent} STOMP header when the client sent one, and makes it current while the
 * {@code @MessageMapping} handler runs — which is what lets the agent's gRPC client span to the
 * Game Host become its child, giving one trace from the client to the game code.
 *
 * <p>Runs on the handler thread ({@link ExecutorChannelInterceptor}), not the sender's, because
 * that is the thread whose context the gRPC call reads. Uses the global OpenTelemetry instance the
 * agent installs; without an agent it is a no-op tracer.
 */
@Component
class WebSocketTraceInterceptor implements ExecutorChannelInterceptor {

  static final String TRACEPARENT = "traceparent";

  private static final TextMapGetter<StompHeaderAccessor> GETTER =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(StompHeaderAccessor carrier) {
          return List.of(TRACEPARENT, "tracestate");
        }

        @Override
        public @Nullable String get(@Nullable StompHeaderAccessor carrier, String key) {
          return carrier == null ? null : carrier.getFirstNativeHeader(key);
        }
      };

  private record Active(Span span, Scope scope) {}

  private final Supplier<Tracer> tracer;
  private final ThreadLocal<@Nullable Active> active = new ThreadLocal<>();

  WebSocketTraceInterceptor() {
    this(() -> GlobalOpenTelemetry.getTracer("io.turnbase.platform.websocket"));
  }

  WebSocketTraceInterceptor(Supplier<Tracer> tracer) {
    this.tracer = tracer;
  }

  @Override
  public Message<?> beforeHandle(
      Message<?> message, MessageChannel channel, MessageHandler handler) {
    if (!(handler instanceof SimpAnnotationMethodMessageHandler)) {
      return message;
    }
    StompHeaderAccessor headers = StompHeaderAccessor.wrap(message);
    String destination = headers.getDestination();
    if (headers.getMessageType() != SimpMessageType.MESSAGE
        || destination == null
        || !destination.startsWith("/app/")) {
      return message;
    }
    Context parent =
        W3CTraceContextPropagator.getInstance().extract(Context.root(), headers, GETTER);
    Span span =
        tracer
            .get()
            .spanBuilder("STOMP SEND " + destination)
            .setParent(parent)
            .setSpanKind(SpanKind.SERVER)
            .startSpan();
    // Closed in afterMessageHandled, which Spring pairs with this call on the same thread.
    @SuppressWarnings("MustBeClosedChecker")
    Scope scope = span.makeCurrent();
    active.set(new Active(span, scope));
    return message;
  }

  @Override
  public void afterMessageHandled(
      Message<?> message, MessageChannel channel, MessageHandler handler, @Nullable Exception ex) {
    Active current = active.get();
    if (current == null) {
      return;
    }
    active.remove();
    if (ex != null) {
      current.span().setStatus(StatusCode.ERROR);
      current.span().recordException(ex);
    }
    current.scope().close();
    current.span().end();
  }
}
