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

export type UserBasicDto = {
  id: Id
  username: string
  nickname?: string | null
}

export type SingleChatConversationDto = {
  singleChatId: Id
  peerUserId: Id
  updatedAt: string
  unreadCount?: number
  myLastReadMsgId?: Id | null
  peerLastReadMsgId?: Id | null
  lastMessage?: {
    serverMsgId: Id
    fromUserId: Id
    toUserId: Id
    content: string
    createdAt: string
  } | null
}

export type GroupConversationDto = {
  groupId: Id
  name: string
  updatedAt: string
  unreadCount?: number
  mentionUnreadCount?: number
  lastMessage?: {
    serverMsgId: Id
    fromUserId: Id
    content: string
    createdAt: string
  } | null
}

export type FriendRelationEntity = {
  id: Id
  user1Id: Id
  user2Id: Id
  createdAt: string
  updatedAt: string
}

export type FriendRequestStatus = "PENDING" | "ACCEPTED" | "REJECTED"

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

export type CreateFriendRequestByCodeResponse = {
  requestId: Id
  toUserId: Id
}

export type DecideFriendRequestResponse = {
  singleChatId: Id | null
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

export type MessageEntity = {
  id: Id
  serverMsgId: Id
  chatType?: number | null
  singleChatId?: Id | null
  groupId?: Id | null
  fromUserId: Id
  toUserId?: Id | null
  content: string
  msgType?: string | null
  status?: string | number | null
  createdAt: string
}

export type MomentPostDto = {
  id: Id
  authorId: Id
  content: string
  likeCount: number
  commentCount: number
  likedByMe: boolean
  createdAt: string
}

export type MomentCommentDto = {
  id: Id
  postId: Id
  userId: Id
  content: string
  createdAt: string
}

export type CreateMomentPostResponse = {
  postId: Id
}

export type ToggleMomentLikeResponse = {
  liked: boolean
  likeCount: number
}

export type CreateMomentCommentResponse = {
  commentId: Id
}

