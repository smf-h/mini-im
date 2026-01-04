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

export type FriendRelationEntity = {
  id: Id
  user1Id: Id
  user2Id: Id
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

export type GroupConversationDto = {
  groupId: Id
  name: string
  updatedAt: string
  unreadCount?: number
  mentionUnreadCount?: number
  lastMessage?: {
    serverMsgId: string
    fromUserId: Id
    content: string
    createdAt: string
  } | null
}

export type GroupBasicDto = {
  id: Id
  name: string
  avatarUrl?: string | null
}

export type CreateGroupResponse = {
  groupId: Id
}

export type CreateFriendRequestByCodeResponse = {
  requestId: Id
  toUserId: Id
}

export type DndListResponse = {
  dmPeerUserIds: Id[]
  groupIds: Id[]
}

export type UserProfileDto = {
  id: Id
  username: string
  nickname?: string | null
  avatarUrl?: string | null
  status?: number | null
  friendCode?: string | null
}

export type MeProfileDto = {
  id: Id
  username: string
  nickname?: string | null
  avatarUrl?: string | null
  status?: number | null
  friendCode?: string | null
  friendCodeUpdatedAt?: string | null
  friendCodeNextResetAt?: string | null
}

export type ResetFriendCodeResponse = {
  friendCode: string
  friendCodeUpdatedAt?: string | null
  friendCodeNextResetAt?: string | null
}

export type MemberRole = 'OWNER' | 'ADMIN' | 'MEMBER'

export type GroupProfileDto = {
  groupId: Id
  name: string
  avatarUrl?: string | null
  groupCode?: string | null
  createdBy?: Id | null
  createdAt?: string | null
  updatedAt?: string | null
  memberCount?: number | null
  myRole?: MemberRole | null
  isMember?: boolean | null
}

export type GroupMemberDto = {
  userId: Id
  username?: string | null
  nickname?: string | null
  avatarUrl?: string | null
  role: MemberRole
  joinAt?: string | null
  speakMuteUntil?: string | null
}

export type GroupJoinRequestStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELED'

export type GroupJoinRequestEntity = {
  id: Id
  groupId: Id
  fromUserId: Id
  message?: string | null
  status: GroupJoinRequestStatus
  handledBy?: Id | null
  handledAt?: string | null
  createdAt: string
  updatedAt: string
}

export type GroupJoinRequestResponse = {
  requestId: Id
}

export type ResetGroupCodeResponse = {
  groupCode: string
  groupCodeUpdatedAt?: string | null
  groupCodeNextResetAt?: string | null
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

export type CallStatus = 'RINGING' | 'ACCEPTED' | 'REJECTED' | 'CANCELED' | 'ENDED' | 'MISSED' | 'FAILED'

export type CallRecordDto = {
  id: Id
  callId: Id
  peerUserId: Id | null
  direction: 'IN' | 'OUT'
  status: CallStatus
  failReason?: string | null
  startedAt?: string | null
  acceptedAt?: string | null
  endedAt?: string | null
  durationSeconds?: number | null
}
