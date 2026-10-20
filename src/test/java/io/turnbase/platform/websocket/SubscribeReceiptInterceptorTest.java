package io.turnbase.platform.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.UserDestinationMessageHandler;
import org.springframework.messaging.support.MessageBuilder;

/** {@code IMP-M16-11}: when, and only when, the {@code RECEIPT} of a {@code SUBSCRIBE} goes out. */
class SubscribeReceiptInterceptorTest {

  private final MessageChannel outbound = mock(MessageChannel.class);
  private final MessageChannel inbound = mock(MessageChannel.class);
  private final SubscribeReceiptInterceptor interceptor = new SubscribeReceiptInterceptor(outbound);

  @Test
  void theBrokerHandlingATopicSubscribeSendsTheReceipt() {
    interceptor.afterMessageHandled(
        subscribe("/topic/room.1", "r-7"), inbound, mock(SimpleBrokerMessageHandler.class), null);

    ArgumentCaptor<Message<?>> sent = ArgumentCaptor.captor();
    verify(outbound).send(sent.capture());
    StompHeaderAccessor receipt = StompHeaderAccessor.wrap(sent.getValue());
    assertThat(receipt.getCommand()).isEqualTo(StompCommand.RECEIPT);
    assertThat(receipt.getReceiptId()).isEqualTo("r-7");
    assertThat(receipt.getSessionId()).isEqualTo("s-1");
  }

  @Test
  void aUserQueueSubscribeIsAnsweredWhenTheUserDestinationHandlerHasResolvedIt() {
    interceptor.afterMessageHandled(
        subscribe("/user/queue/room-state", "r-1"),
        inbound,
        mock(UserDestinationMessageHandler.class),
        null);

    verify(outbound).send(any());
  }

  @Test
  void aHandlerThatDoesNotRegisterTheSubscriptionSendsNothing() {
    interceptor.afterMessageHandled(
        subscribe("/topic/room.1", "r-7"), inbound, mock(MessageHandler.class), null);
    interceptor.afterMessageHandled(
        subscribe("/topic/room.1", "r-7"),
        inbound,
        mock(UserDestinationMessageHandler.class),
        null);
    interceptor.afterMessageHandled(
        subscribe("/user/queue/room-state", "r-7"),
        inbound,
        mock(SimpleBrokerMessageHandler.class),
        null);

    verify(outbound, never()).send(any());
  }

  @Test
  void aSubscribeWithoutAReceiptHeaderOrThatFailedSendsNothing() {
    SimpleBrokerMessageHandler broker = mock(SimpleBrokerMessageHandler.class);
    interceptor.afterMessageHandled(subscribe("/topic/room.1", null), inbound, broker, null);
    interceptor.afterMessageHandled(
        subscribe("/topic/room.1", "r-7"), inbound, broker, new IllegalStateException("boom"));

    verify(outbound, never()).send(any());
  }

  @Test
  void aFrameThatIsNotASubscribeSendsNothing() {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
    accessor.setDestination("/app/room.1.member");
    accessor.setNativeHeader("receipt", "r-7");
    accessor.setSessionId("s-1");
    Message<?> send = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

    interceptor.afterMessageHandled(send, inbound, mock(SimpleBrokerMessageHandler.class), null);

    verify(outbound, never()).send(any());
  }

  private static Message<?> subscribe(String destination, @Nullable String receipt) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
    accessor.setDestination(destination);
    accessor.setSubscriptionId("0");
    accessor.setSessionId("s-1");
    if (receipt != null) {
      accessor.setNativeHeader("receipt", receipt);
    }
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }
}
