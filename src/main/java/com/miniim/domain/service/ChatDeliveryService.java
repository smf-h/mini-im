package com.miniim.domain.service;

public interface ChatDeliveryService {
    /**
     * 处理接收方的 ACK：DELIVERED/READ。
     * @param fromUserId ack 发送方（接收消息的人）
     * @param toUserId   原消息发送方
     * @param clientMsgId 客户端消息ID
     * @param serverMsgId 服务端消息ID
     * @param ackType    大小写不敏感：DELIVERED/READ/RECEIVED(等价DELIVERED)
     * @return true 表示已更新
     */
    boolean handleReceiverAck(Long fromUserId, Long toUserId, String clientMsgId, String serverMsgId, String ackType);

    /**
     * 登录后为该用户补拉未送达消息（例如状态= DROPPED 或 SAVED 未投递）。
     */
    void deliverPendingForUser(Long userId);
}
