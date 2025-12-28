export type ResultEnvelope<T> = {
  ok: boolean
  code: number
  message: string
  data: T
  ts?: number
}

export type Id = string

export type LoginResponse = {
  userId: Id
  accessToken: string
  refreshToken: string
  accessTokenExpiresInSeconds: number
  refreshTokenExpiresInSeconds: number
}

export type RefreshResponse = {
  userId: Id
  accessToken: string
  accessTokenExpiresInSeconds: number
}

export type FriendRequestStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED'

export type FriendRequestEntity = {
  id: Id
  fromUserId: Id
  toUserId: Id
  content: string | null
  status: FriendRequestStatus
  handledAt: string | null
  createdAt: string
  updatedAt: string
}

export type SingleChatConversationDto = {
  singleChatId: Id
  peerUserId: Id
  updatedAt: string
  unreadCount?: number
  myLastReadMsgId?: Id | null
  peerLastReadMsgId?: Id | null
  lastMessage?: {
    serverMsgId: string
    fromUserId: Id
    toUserId: Id
    content: string
    createdAt: string
  } | null
}

export type SingleChatMemberStateDto = {
  singleChatId?: Id | null
  peerUserId: Id
  myLastReadMsgId?: Id | null
  peerLastReadMsgId?: Id | null
}

export type MessageEntity = {
  id: Id
  chatType: number
  singleChatId: Id | null
  groupId: Id | null
  fromUserId: Id
  toUserId: Id | null
  msgType: number
  content: string
  status: number
  clientMsgId: string | null
  serverMsgId: string
  createdAt: string
  updatedAt: string
}
