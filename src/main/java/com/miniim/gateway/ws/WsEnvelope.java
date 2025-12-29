package com.miniim.gateway.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

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

    /**
     * 群聊：@ 提及的 userId 列表（仅用于服务端计算重要消息与落库稀疏索引）。
     *
     * <p>约定：使用字符串传输，避免 JS number 精度问题；服务端会做数字解析与群成员校验，非法值将被忽略。</p>
     */
    public List<String> mentions;

    /**
     * 群聊：回复/引用的目标消息 id（服务端消息 id，通常等于 msgId 的字符串形式）。
     */
    public String replyToServerMsgId;

    /**
     * 服务端下发给接收方的标记：该消息对当前接收方是否“重要”（@我/回复我）。
     *
     * <p>用于前端 toast/高亮提示；不参与落库消息本体。</p>
     */
    public Boolean important;

    /** 单聊通话：通话 id（服务端生成，long 语义按字符串传输）。 */
    public Long callId;

    /** 单聊通话：通话类型（Phase1: video）。 */
    public String callKind;

    /** WebRTC SDP（offer/answer）。 */
    public String sdp;

    /** WebRTC ICE candidate（candidate 字符串）。 */
    public String iceCandidate;

    public String iceSdpMid;

    public Integer iceSdpMLineIndex;

    /** 通话失败/拒绝原因（用于 UI 展示与记录）。 */
    public String callReason;

    /** 时间戳（毫秒）。一般用于客户端展示/排序/粗略诊断。 */
    public Long ts;

    /** 错误原因（例如 AUTH_FAIL/ERROR 时返回）。 */
    public String reason;
}
