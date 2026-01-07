export type WsEnvelope = {
  type: string
  clientMsgId?: string
  serverMsgId?: string
  from?: string
  to?: string
  groupId?: string
  token?: string
  ackType?: string
  msgType?: string
  body?: string
  mentions?: string[]
  replyToServerMsgId?: string
  important?: boolean
  callId?: string
  callKind?: string
  sdp?: string
  iceCandidate?: string
  iceSdpMid?: string
  iceSdpMLineIndex?: number
  callReason?: string
  ts?: number
  reason?: string
}

