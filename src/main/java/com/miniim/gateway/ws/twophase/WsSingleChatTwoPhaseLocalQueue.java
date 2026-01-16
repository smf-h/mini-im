package com.miniim.gateway.ws.twophase;

import com.miniim.gateway.config.WsSingleChatTwoPhaseProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Component
@RequiredArgsConstructor
public class WsSingleChatTwoPhaseLocalQueue {

    private final WsSingleChatTwoPhaseProperties props;

    private volatile BlockingQueue<Item> accepted;
    private volatile BlockingQueue<Item> toSave;

    public BlockingQueue<Item> acceptedQueue() {
        BlockingQueue<Item> q = accepted;
        if (q != null) {
            return q;
        }
        synchronized (this) {
            if (accepted == null) {
                accepted = new LinkedBlockingQueue<>(props.localQueueCapacityEffective());
            }
            return accepted;
        }
    }

    public BlockingQueue<Item> toSaveQueue() {
        BlockingQueue<Item> q = toSave;
        if (q != null) {
            return q;
        }
        synchronized (this) {
            if (toSave == null) {
                toSave = new LinkedBlockingQueue<>(props.localQueueCapacityEffective());
            }
            return toSave;
        }
    }

    public record Item(
            long fromUserId,
            long toUserId,
            String clientMsgId,
            String serverMsgId,
            String msgType,
            String body,
            long sendTs,
            String producerServerId
    ) {
    }
}

