export type WsEnvelope = {
  type: string
  clientMsgId?: string | null
  serverMsgId?: string | null
  msgSeq?: string | number | null
  from?: string | null
  to?: string | null
  groupId?: string | null
  token?: string | null
  ackType?: string | null
  msgType?: string | null
  body?: string | null
  mentions?: string[] | null
  replyToServerMsgId?: string | null
  important?: boolean | null
  callId?: string | null
  callKind?: string | null
  sdp?: string | null
  iceCandidate?: string | null
  iceSdpMid?: string | null
  iceSdpMLineIndex?: number | null
  callReason?: string | null
  ts?: number | null
  reason?: string | null
}
