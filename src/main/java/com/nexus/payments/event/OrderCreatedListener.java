package com.nexus.payments.event;

import com.nexus.payments.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class OrderCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCreatedListener.class);

    @RabbitListener(queues = RabbitConfig.ORDERS_CREATED_QUEUE)
    public void onOrderCreated(OrderCreated event) {
        log.info("Received OrderCreated event, eventId={}, orderId={}, userId={}, amount={} {}",
                event.eventId(), event.orderId(), event.userId(), event.totalAmount(), event.currency());
    }
}
