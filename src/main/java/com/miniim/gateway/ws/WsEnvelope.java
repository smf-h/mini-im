package com.miniim.gateway.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsEnvelope {
    /**
     * 消息类型（路由字段）。
     *
     * <p>示例：AUTH / AUTH_OK / AUTH_FAIL / PING / PONG / CHAT / ACK / ERROR ...</p>
     */
    public String type;

    /** 业务消息 id（通常用于 ACK/幂等/重试）。当前阶段未接入业务链路。 */
    public String clientMsgId;

    /** 会话 id（你之前讨论过 convId；现在改为单聊/群聊分表后可逐步替换为 singleChatId/groupId）。 */
//    public Long convId;
    public String serverMsgId;

    /** 服务端确认的发送方 userId。注意：不能信任客户端自己填的 from，必须以鉴权绑定身份为准。 */
    public Long from;

    /** 单聊接收方 userId。 */
    public Long to;
//    public Long singleChatId;

    /** 群 id（群聊场景）。 */
    public Long groupId;

    /** accessToken：用于 WS 首包 AUTH（旧客户端兼容）；新方案建议握手阶段带 Authorization。 */
    public String token;

    /** ACK 类型（例如 DELIVERED/READ）。当前阶段未接入业务链路。 */
    public String ackType;

    /** 消息内容类型（例如 TEXT/IMAGE/FILE）。当前阶段未接入业务链路。 */
    public String msgType;

    /** 消息正文（例如文本内容）。 */
    public String body;

    /** 时间戳（毫秒）。一般用于客户端展示/排序/粗略诊断。 */
    public Long ts;

    /** 错误原因（例如 AUTH_FAIL/ERROR 时返回）。 */
    public String reason;
}
