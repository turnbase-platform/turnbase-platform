package io.turnbase.platform.websocket;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.UserDestinationMessageHandler;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * {@code room-websocket.md} § Snapshot Delivery (IMP-M16-11): answers a {@code SUBSCRIBE} that
 * carries a {@code receipt} header with a {@code RECEIPT} frame once the subscription is
 * registered. Spring's simple broker sends none (only an external broker relay does), and a client
 * that acts the moment it has sent the {@code SUBSCRIBE} races the registration: the frames run on
 * an executor, so a broadcast made in between does not reach it.
 *
 * <p>The receipt goes out from {@link #afterMessageHandled}, which runs when the handler that
 * registers the subscription has returned: {@link SimpleBrokerMessageHandler} for a {@code /topic}
 * or {@code /queue} destination, {@link UserDestinationMessageHandler} for a {@code /user/…} one
 * (it resolves the destination and hands it to the broker on the same thread). {@code
 * afterSendCompletion} fires when the frame has only been handed to the executor, which is too
 * early. A subscription that was dropped or rejected by {@link WebSocketChannelInterceptor} never
 * reaches a handler and gets no receipt, as the STOMP specification allows for an {@code ERROR}ed
 * frame.
 */
@Component
class SubscribeReceiptInterceptor implements ExecutorChannelInterceptor {

  private static final byte[] EMPTY = new byte[0];

  private final MessageChannel clientOutboundChannel;

  SubscribeReceiptInterceptor(
      @Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel) {
    this.clientOutboundChannel = clientOutboundChannel;
  }

  @Override
  public void afterMessageHandled(
      Message<?> message, MessageChannel channel, MessageHandler handler, @Nullable Exception ex) {
    if (ex != null) {
      return;
    }
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
    String receiptId = accessor.getReceipt();
    String destination = accessor.getDestination();
    if (accessor.getCommand() != StompCommand.SUBSCRIBE
        || receiptId == null
        || destination == null
        || !registersSubscription(handler, destination)) {
      return;
    }
    StompHeaderAccessor receipt = StompHeaderAccessor.create(StompCommand.RECEIPT);
    receipt.setReceiptId(receiptId);
    receipt.setSessionId(accessor.getSessionId());
    receipt.setLeaveMutable(true);
    clientOutboundChannel.send(MessageBuilder.createMessage(EMPTY, receipt.getMessageHeaders()));
  }

  private static boolean registersSubscription(MessageHandler handler, String destination) {
    return destination.startsWith("/user/")
        ? handler instanceof UserDestinationMessageHandler
        : handler instanceof SimpleBrokerMessageHandler;
  }
}
