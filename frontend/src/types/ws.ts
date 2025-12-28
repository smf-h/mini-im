export type WsEnvelope = {
  type: string
  clientMsgId?: string | null
  serverMsgId?: string | null
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
  ts?: number | null
  reason?: string | null
}
