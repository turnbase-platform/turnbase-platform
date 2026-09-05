package io.turnbase.platform.common;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * observability.md §3's gRPC-out MDC boundary: copies whichever of {@link GrpcCorrelationKeys} are
 * present in the calling thread's MDC onto outbound gRPC metadata, so the receiving service's
 * {@code ServerInterceptor} can restore them (FR-OBS-003). An absent key is left off the metadata
 * entirely, matching the "omitted when absent" rule the log encoder follows.
 *
 * <p>Not yet attached to a live {@link Channel} — the orchestration client is IMP-M3-05 — proven
 * against a controlled call instead (IMP-GATE-18).
 */
@Component
public class GrpcCorrelationClientInterceptor implements ClientInterceptor {

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    return new SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        for (GrpcCorrelationKeys key : GrpcCorrelationKeys.values()) {
          String value = MDC.get(key.mdcKey());
          if (value != null) {
            headers.put(key.metadataKey(), value);
          }
        }
        super.start(responseListener, headers);
      }
    };
  }
}
