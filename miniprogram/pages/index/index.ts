import { HTTP_BASE, WS_URL } from "../../config"
import { authStore } from "../../stores/auth"
import { apiGet, apiPost } from "../../services/http"
import { connect, getWsState, onWsEvent, onWsState, send } from "../../services/ws"
import { genClientMsgId } from "../../utils/ids"
import { formatTime } from "../../utils/time"
import type {
  CreateFriendRequestByCodeResponse,
  CreateMomentCommentResponse,
  CreateMomentPostResponse,
  DecideFriendRequestResponse,
  FriendRequestEntity,
  FriendRelationEntity,
  GroupConversationDto,
  MeProfileDto,
  MessageEntity,
  MomentCommentDto,
  MomentPostDto,
  SingleChatConversationDto,
  ToggleMomentLikeResponse,
  UserBasicDto,
} from "../../types/api"
import type { WsEnvelope } from "../../types/ws"

type Tab = "chats" | "contacts" | "moments" | "call" | "me"

type FriendUi = {
  id: string
  otherUserId: string
}

type ChatMode = "single" | "group"

type ChatState = {
  open: boolean
  mode: ChatMode
  peerUserId?: string
  groupId?: string
  title: string
  draft: string
  messages: MessageEntity[]
  sinceIdByGroup: Record<string, string>
}

function showError(err: unknown, fallback = "操作失败") {
  const msg = typeof err === "string" ? err : err instanceof Error ? err.message : fallback
  wx.showToast({ title: msg.slice(0, 20), icon: "none" })
}

function uniq(arr: string[]) {
  const s = new Set<string>()
  for (const x of arr) s.add(String(x))
  return Array.from(s)
}

function pickOtherUserId(me: string, r: FriendRelationEntity) {
  if (r.user1Id === me) return String(r.user2Id)
  return String(r.user1Id)
}

Page({
  data: {
    httpBase: HTTP_BASE,
    wsUrl: WS_URL,
    myUserId: "" as string,
    loggedIn: false,
    tab: "chats" as Tab,
    wsState: getWsState(),
    userNameMap: {} as Record<string, string>,
    login: { username: "", password: "", loading: false },
    loading: { chats: false, contacts: false, moments: false, createPost: false, me: false },
    singleChats: [] as SingleChatConversationDto[],
    groupChats: [] as GroupConversationDto[],
    friends: [] as FriendUi[],
    friendRequests: [] as FriendRequestEntity[],
    addFriend: { code: "", message: "", loading: false },
    groupCreate: { name: "", loading: false },
    groupJoin: { code: "", message: "", loading: false },
    moments: [] as MomentPostDto[],
    momentDraft: "",
    momentComments: {} as Record<string, MomentCommentDto[]>,
    momentCommentDraft: {} as Record<string, string>,
    meProfile: null as MeProfileDto | null,
    call: {
      toUserId: "",
      sdp: "dummy_sdp",
      answerSdp: "dummy_answer",
      iceCandidate: "",
      current: { callId: "", peerUserId: "" },
      incoming: { callId: "", fromUserId: "" },
      events: [] as string[],
    },
    chat: {
      open: false,
      mode: "single" as ChatMode,
      peerUserId: "",
      groupId: "",
      title: "",
      draft: "",
      messages: [] as MessageEntity[],
      sinceIdByGroup: {} as Record<string, string>,
    } as ChatState,
  },

  onLoad() {
    authStore.hydrate()
    const loggedIn = authStore.isLoggedIn
    this.setData({ loggedIn, myUserId: authStore.userId ?? "" })
    if (loggedIn) {
      void this.ensureConnected()
      void this.refreshAll()
    }

    onWsState((s) => {
      this.setData({ wsState: s })
    })

    onWsEvent((ev) => {
      void this.onWsEvent(ev)
    })
  },

  async ensureConnected() {
    try {
      await connect()
    } catch (e) {
      showError(e, "WS连接失败")
    }
  },

  async refreshAll() {
    await Promise.all([this.refreshChats(), this.refreshContacts(), this.refreshMoments(), this.refreshMe()])
  },

  switchTab(e: any) {
    const tab = String(e?.currentTarget?.dataset?.tab || "chats") as Tab
    this.setData({ tab })
    if (tab === "chats") void this.refreshChats()
    if (tab === "contacts") void this.refreshContacts()
    if (tab === "moments") void this.refreshMoments()
    if (tab === "me") void this.refreshMe()
  },

  onLoginUsername(e: any) {
    this.setData({ "login.username": e.detail.value })
  },
  onLoginPassword(e: any) {
    this.setData({ "login.password": e.detail.value })
  },

  async doLogin() {
    if (this.data.login.loading) return
    const username = this.data.login.username.trim()
    const password = this.data.login.password
    if (!username || !password) {
      wx.showToast({ title: "请输入账号密码", icon: "none" })
      return
    }
    this.setData({ "login.loading": true })
    try {
      await authStore.login(username, password)
      this.setData({ loggedIn: true, myUserId: authStore.userId ?? "" })
      await this.ensureConnected()
      await this.refreshAll()
      wx.showToast({ title: "登录成功", icon: "success" })
    } catch (e) {
      showError(e, "登录失败")
    } finally {
      this.setData({ "login.loading": false })
    }
  },

  logout() {
    authStore.clear()
    this.setData({
      loggedIn: false,
      myUserId: "",
      tab: "chats",
      singleChats: [],
      groupChats: [],
      friends: [],
      friendRequests: [],
      moments: [],
      meProfile: null,
    })
    wx.showToast({ title: "已退出", icon: "none" })
  },

  async refreshChats() {
    if (this.data.loading.chats) return
    this.setData({ "loading.chats": true })
    try {
      const [single, group] = await Promise.all([
        apiGet<SingleChatConversationDto[]>("/single-chat/conversation/cursor?limit=50"),
        apiGet<GroupConversationDto[]>("/group/conversation/cursor?limit=50"),
      ])
      this.setData({ singleChats: single, groupChats: group })

      const peerIds = uniq(single.map((x) => String(x.peerUserId)))
      await this.ensureUserBasics(peerIds)
    } catch (e) {
      showError(e, "刷新会话失败")
    } finally {
      this.setData({ "loading.chats": false })
    }
  },

  async refreshContacts() {
    if (this.data.loading.contacts) return
    this.setData({ "loading.contacts": true })
    try {
      const [rels, reqs] = await Promise.all([
        apiGet<FriendRelationEntity[]>("/friend/relation/list"),
        apiGet<FriendRequestEntity[]>("/friend/request/cursor?box=all&limit=50"),
      ])
      const me = this.data.myUserId
      const friends: FriendUi[] = rels.map((r) => ({
        id: String(r.id),
        otherUserId: pickOtherUserId(me, r),
      }))
      this.setData({ friends, friendRequests: reqs })

      const ids = uniq(
        friends.map((x) => x.otherUserId).concat(reqs.flatMap((r) => [String(r.fromUserId), String(r.toUserId)])),
      )
      await this.ensureUserBasics(ids)
    } catch (e) {
      showError(e, "刷新联系人失败")
    } finally {
      this.setData({ "loading.contacts": false })
    }
  },

  onAddFriendCode(e: any) {
    this.setData({ "addFriend.code": e.detail.value })
  },
  onAddFriendMessage(e: any) {
    this.setData({ "addFriend.message": e.detail.value })
  },

  async createFriendRequest() {
    if (this.data.addFriend.loading) return
    const code = this.data.addFriend.code.trim().toUpperCase()
    const message = this.data.addFriend.message
    if (!code) {
      wx.showToast({ title: "请输入好友码", icon: "none" })
      return
    }
    this.setData({ "addFriend.loading": true })
    try {
      const resp = await apiPost<CreateFriendRequestByCodeResponse>("/friend/request/by-code", {
        toFriendCode: code,
        message,
      })
      wx.showToast({ title: "已发送", icon: "success" })
      this.setData({ "addFriend.code": "", "addFriend.message": "" })
      await this.ensureUserBasics([String(resp.toUserId)])
      await this.refreshContacts()
    } catch (e) {
      showError(e, "发送失败")
    } finally {
      this.setData({ "addFriend.loading": false })
    }
  },

  onGroupCreateName(e: any) {
    this.setData({ "groupCreate.name": e.detail.value })
  },

  async createGroup() {
    if (this.data.groupCreate.loading) return
    const name = String(this.data.groupCreate.name || "").trim()
    if (!name) {
      wx.showToast({ title: "请输入群名", icon: "none" })
      return
    }
    this.setData({ "groupCreate.loading": true })
    try {
      await apiPost<{ groupId: string }>("/group/create", { name, memberUserIds: [] })
      wx.showToast({ title: "已创建", icon: "success" })
      this.setData({ "groupCreate.name": "" })
      await this.refreshChats()
    } catch (e) {
      showError(e, "创建失败")
    } finally {
      this.setData({ "groupCreate.loading": false })
    }
  },

  onGroupJoinCode(e: any) {
    this.setData({ "groupJoin.code": e.detail.value })
  },
  onGroupJoinMessage(e: any) {
    this.setData({ "groupJoin.message": e.detail.value })
  },

  async requestJoinGroup() {
    if (this.data.groupJoin.loading) return
    const code = String(this.data.groupJoin.code || "").trim().toUpperCase()
    const message = String(this.data.groupJoin.message || "")
    if (!code) {
      wx.showToast({ title: "请输入群码", icon: "none" })
      return
    }
    this.setData({ "groupJoin.loading": true })
    try {
      await apiPost<{ requestId: string }>("/group/join/request", { groupCode: code, message })
      wx.showToast({ title: "已申请", icon: "success" })
      this.setData({ "groupJoin.code": "", "groupJoin.message": "" })
    } catch (e) {
      showError(e, "申请失败")
    } finally {
      this.setData({ "groupJoin.loading": false })
    }
  },

  async decideFriendRequest(e: any) {
    const id = String(e?.currentTarget?.dataset?.id || "")
    const action = String(e?.currentTarget?.dataset?.action || "")
    if (!id || !action) return
    try {
      await apiPost<DecideFriendRequestResponse>("/friend/request/decide", { requestId: id, action })
      wx.showToast({ title: "已处理", icon: "success" })
      await this.refreshContacts()
      await this.refreshChats()
    } catch (err) {
      showError(err, "处理失败")
    }
  },

  async refreshMoments() {
    if (this.data.loading.moments) return
    this.setData({ "loading.moments": true })
    try {
      const posts = await apiGet<MomentPostDto[]>("/moment/feed/cursor?limit=20")
      const viewPosts = posts.map((p) => ({ ...p, createdAt: formatTime(p.createdAt) }))
      this.setData({ moments: viewPosts })
      await this.ensureUserBasics(uniq(viewPosts.map((p) => String(p.authorId))))

      const comments: Record<string, MomentCommentDto[]> = {}
      for (const p of viewPosts) {
        try {
          const list = await apiGet<MomentCommentDto[]>(
            `/moment/comment/cursor?postId=${encodeURIComponent(String(p.id))}&limit=5`,
          )
          comments[String(p.id)] = list.map((c) => ({ ...c, createdAt: formatTime(c.createdAt) }))
          await this.ensureUserBasics(uniq(list.map((c) => String(c.userId))))
        } catch {
          // ignore
        }
      }
      this.setData({ momentComments: comments })
    } catch (e) {
      showError(e, "刷新朋友圈失败")
    } finally {
      this.setData({ "loading.moments": false })
    }
  },

  onMomentDraft(e: any) {
    this.setData({ momentDraft: e.detail.value })
  },

  async createMomentPost() {
    if (this.data.loading.createPost) return
    const content = this.data.momentDraft.trim()
    if (!content) return
    this.setData({ "loading.createPost": true })
    try {
      await apiPost<CreateMomentPostResponse>("/moment/post/create", { content })
      this.setData({ momentDraft: "" })
      wx.showToast({ title: "已发布", icon: "success" })
      await this.refreshMoments()
    } catch (e) {
      showError(e, "发布失败")
    } finally {
      this.setData({ "loading.createPost": false })
    }
  },

  async deleteMomentPost(e: any) {
    const postId = String(e?.currentTarget?.dataset?.id || "")
    if (!postId) return
    try {
      await apiPost<void>("/moment/post/delete", { postId })
      wx.showToast({ title: "已删除", icon: "success" })
      await this.refreshMoments()
    } catch (err) {
      showError(err, "删除失败")
    }
  },

  async toggleMomentLike(e: any) {
    const postId = String(e?.currentTarget?.dataset?.id || "")
    if (!postId) return
    try {
      await apiPost<ToggleMomentLikeResponse>("/moment/like/toggle", { postId })
      await this.refreshMoments()
    } catch (err) {
      showError(err, "点赞失败")
    }
  },

  onMomentCommentDraft(e: any) {
    const postId = String(e?.currentTarget?.dataset?.id || "")
    const v = e.detail.value
    const map = { ...(this.data.momentCommentDraft as Record<string, string>) }
    map[postId] = v
    this.setData({ momentCommentDraft: map })
  },

  async createMomentComment(e: any) {
    const postId = String(e?.currentTarget?.dataset?.id || "")
    if (!postId) return
    const draft = (this.data.momentCommentDraft as Record<string, string>)[postId] || ""
    const content = draft.trim()
    if (!content) return
    try {
      await apiPost<CreateMomentCommentResponse>("/moment/comment/create", { postId, content })
      const map = { ...(this.data.momentCommentDraft as Record<string, string>) }
      map[postId] = ""
      this.setData({ momentCommentDraft: map })
      await this.refreshMoments()
    } catch (err) {
      showError(err, "评论失败")
    }
  },

  async deleteMomentComment(e: any) {
    const commentId = String(e?.currentTarget?.dataset?.id || "")
    if (!commentId) return
    try {
      await apiPost<void>("/moment/comment/delete", { commentId })
      await this.refreshMoments()
    } catch (err) {
      showError(err, "删除评论失败")
    }
  },

  async refreshMe() {
    if (this.data.loading.me) return
    this.setData({ "loading.me": true })
    try {
      const me = await apiGet<MeProfileDto>("/me/profile")
      this.setData({ meProfile: me })
    } catch (e) {
      showError(e, "刷新失败")
    } finally {
      this.setData({ "loading.me": false })
    }
  },

  async resetFriendCode() {
    try {
      await apiPost<void>("/me/friend-code/reset", {})
      await this.refreshMe()
      wx.showToast({ title: "已重置", icon: "success" })
    } catch (e) {
      showError(e, "重置失败")
    }
  },

  // chat overlay
  async openSingleChat(e: any) {
    const peer = String(e?.currentTarget?.dataset?.peer || "")
    if (!peer) return
    const title = this.data.userNameMap[peer] || `用户 ${peer}`
    this.setData({
      "chat.open": true,
      "chat.mode": "single",
      "chat.peerUserId": peer,
      "chat.groupId": "",
      "chat.title": title,
      "chat.draft": "",
      "chat.messages": [],
    })
    await this.ensureUserBasics([peer])
    await this.loadChatHistory()
  },

  async openGroupChat(e: any) {
    const gid = String(e?.currentTarget?.dataset?.group || "")
    if (!gid) return
    const g = this.data.groupChats.find((x) => String(x.groupId) === gid)
    const title = g?.name || `群 ${gid}`
    this.setData({
      "chat.open": true,
      "chat.mode": "group",
      "chat.peerUserId": "",
      "chat.groupId": gid,
      "chat.title": title,
      "chat.draft": "",
      "chat.messages": [],
    })
    await this.loadChatHistory()
  },

  closeChat() {
    this.setData({ "chat.open": false })
  },

  onChatDraft(e: any) {
    this.setData({ "chat.draft": e.detail.value })
  },

  async loadChatHistory() {
    const chat = this.data.chat as ChatState
    try {
      let list: MessageEntity[] = []
      if (chat.mode === "single" && chat.peerUserId) {
        list = await apiGet<MessageEntity[]>(
          `/single-chat/message/cursor?peerUserId=${encodeURIComponent(chat.peerUserId)}&limit=30`,
        )
      }
      if (chat.mode === "group" && chat.groupId) {
        list = await apiGet<MessageEntity[]>(
          `/group/message/cursor?groupId=${encodeURIComponent(chat.groupId)}&limit=30`,
        )
        const max = list.reduce((acc, m) => {
          const id = String(m.id || "0")
          return id > acc ? id : acc
        }, "0")
        const since = { ...(chat.sinceIdByGroup || {}) }
        since[chat.groupId] = max
        this.setData({ "chat.sinceIdByGroup": since })
      }

      const users = uniq(list.map((m) => String(m.fromUserId)))
      await this.ensureUserBasics(users)

      this.setData({
        "chat.messages": list
          .slice()
          .reverse()
          .map((m) => ({ ...m, createdAt: formatTime(m.createdAt) })),
      })
    } catch (e) {
      showError(e, "加载消息失败")
    }
  },

  async sendChatMessage() {
    const chat = this.data.chat as ChatState
    const body = chat.draft.trim()
    if (!body) return
    if (this.data.wsState.state !== "open" || !this.data.wsState.authed) {
      wx.showToast({ title: "WS未就绪", icon: "none" })
      return
    }

    const clientMsgId = genClientMsgId("m")
    try {
      if (chat.mode === "single" && chat.peerUserId) {
        send({ type: "SINGLE_CHAT", clientMsgId, to: chat.peerUserId, body })
      } else if (chat.mode === "group" && chat.groupId) {
        send({ type: "GROUP_CHAT", clientMsgId, groupId: chat.groupId, body, msgType: "TEXT" })
      } else {
        return
      }

      const mine: MessageEntity = {
        id: clientMsgId,
        serverMsgId: clientMsgId,
        fromUserId: this.data.myUserId,
        toUserId: chat.peerUserId,
        groupId: chat.groupId,
        content: body,
        createdAt: formatTime(Date.now()),
      }
      const next = (chat.messages || []).concat(mine)
      this.setData({ "chat.messages": next, "chat.draft": "" })
    } catch (e) {
      showError(e, "发送失败")
    }
  },

  // 通话（信令）
  onCallToUserId(e: any) {
    this.setData({ "call.toUserId": e.detail.value })
  },
  onCallSdp(e: any) {
    this.setData({ "call.sdp": e.detail.value })
  },
  onCallAnswerSdp(e: any) {
    this.setData({ "call.answerSdp": e.detail.value })
  },
  onCallIceCandidate(e: any) {
    this.setData({ "call.iceCandidate": e.detail.value })
  },
  clearCallLog() {
    this.setData({ "call.events": [] })
  },
  callInvite() {
    const to = this.data.call.toUserId.trim()
    const sdp = this.data.call.sdp.trim()
    if (!to || !sdp) {
      wx.showToast({ title: "to/sdp 必填", icon: "none" })
      return
    }
    if (this.data.wsState.state !== "open" || !this.data.wsState.authed) {
      wx.showToast({ title: "WS未就绪", icon: "none" })
      return
    }
    try {
      send({ type: "CALL_INVITE", clientMsgId: genClientMsgId("call"), to, callKind: "video", sdp })
      wx.showToast({ title: "已发送邀请", icon: "none" })
    } catch (e) {
      showError(e, "发送失败")
    }
  },

  callAccept() {
    const callId = String(this.data.call.incoming.callId || "")
    if (!callId) return
    const sdp = String(this.data.call.answerSdp || "").trim()
    if (!sdp) {
      wx.showToast({ title: "SDP必填", icon: "none" })
      return
    }
    if (this.data.wsState.state !== "open" || !this.data.wsState.authed) {
      wx.showToast({ title: "WS未就绪", icon: "none" })
      return
    }
    try {
      send({ type: "CALL_ACCEPT", clientMsgId: genClientMsgId("call"), callId, callKind: "video", sdp })
      this.setData({
        "call.current.callId": callId,
        "call.current.peerUserId": String(this.data.call.incoming.fromUserId || ""),
        "call.incoming.callId": "",
        "call.incoming.fromUserId": "",
      })
    } catch (e) {
      showError(e, "接听失败")
    }
  },

  callReject() {
    const callId = String(this.data.call.incoming.callId || "")
    if (!callId) return
    if (this.data.wsState.state !== "open" || !this.data.wsState.authed) {
      wx.showToast({ title: "WS未就绪", icon: "none" })
      return
    }
    try {
      send({ type: "CALL_REJECT", clientMsgId: genClientMsgId("call"), callId, callReason: "reject" })
      this.setData({ "call.incoming.callId": "", "call.incoming.fromUserId": "" })
    } catch (e) {
      showError(e, "拒绝失败")
    }
  },

  callCancel() {
    const callId = String(this.data.call.current.callId || "")
    if (!callId) {
      wx.showToast({ title: "无通话", icon: "none" })
      return
    }
    if (this.data.wsState.state !== "open" || !this.data.wsState.authed) {
      wx.showToast({ title: "WS未就绪", icon: "none" })
      return
    }
    try {
      send({ type: "CALL_CANCEL", clientMsgId: genClientMsgId("call"), callId, callReason: "cancel" })
      this.setData({ "call.current.callId": "", "call.current.peerUserId": "" })
    } catch (e) {
      showError(e, "取消失败")
    }
  },

  callEnd() {
    const callId = String(this.data.call.current.callId || "")
    if (!callId) {
      wx.showToast({ title: "无通话", icon: "none" })
      return
    }
    if (this.data.wsState.state !== "open" || !this.data.wsState.authed) {
      wx.showToast({ title: "WS未就绪", icon: "none" })
      return
    }
    try {
      send({ type: "CALL_END", clientMsgId: genClientMsgId("call"), callId, callReason: "end" })
      this.setData({ "call.current.callId": "", "call.current.peerUserId": "" })
    } catch (e) {
      showError(e, "挂断失败")
    }
  },

  callIce() {
    const callId = String(this.data.call.current.callId || "")
    const iceCandidate = String(this.data.call.iceCandidate || "").trim()
    if (!callId) {
      wx.showToast({ title: "无通话", icon: "none" })
      return
    }
    if (!iceCandidate) {
      wx.showToast({ title: "ICE必填", icon: "none" })
      return
    }
    if (this.data.wsState.state !== "open" || !this.data.wsState.authed) {
      wx.showToast({ title: "WS未就绪", icon: "none" })
      return
    }
    try {
      send({ type: "CALL_ICE", clientMsgId: genClientMsgId("call"), callId, iceCandidate })
      this.setData({ "call.iceCandidate": "" })
    } catch (e) {
      showError(e, "发送失败")
    }
  },

  async onWsEvent(ev: WsEnvelope) {
    if (!ev || !ev.type) return

    if (ev.type === "ACK" && ev.ackType === "saved" && ev.clientMsgId && ev.serverMsgId) {
      const chat = this.data.chat as ChatState
      if (!chat.open) return
      const clientMsgId = String(ev.clientMsgId)
      const serverMsgId = String(ev.serverMsgId)
      const updated = (chat.messages || []).map((m) => {
        if (String(m.id) !== clientMsgId) return m
        return { ...m, id: serverMsgId, serverMsgId }
      })
      this.setData({ "chat.messages": updated })
      return
    }

    if (ev.type === "GROUP_NOTIFY" && ev.groupId) {
      await this.pullGroupSince(String(ev.groupId))
      return
    }

    if (ev.type === "SINGLE_CHAT" || ev.type === "GROUP_CHAT") {
      const msg: MessageEntity = {
        id: String(ev.serverMsgId || ev.clientMsgId || genClientMsgId("rx")),
        serverMsgId: String(ev.serverMsgId || ""),
        fromUserId: String(ev.from || ""),
        toUserId: ev.to ? String(ev.to) : undefined,
        groupId: ev.groupId ? String(ev.groupId) : undefined,
        content: String(ev.body || ""),
        createdAt: formatTime(ev.ts || Date.now()),
      }
      await this.ensureUserBasics(uniq([msg.fromUserId].filter(Boolean)))
      const chat = this.data.chat as ChatState
      if (!chat.open) return

      if (chat.mode === "single" && chat.peerUserId) {
        const mine = msg.fromUserId === this.data.myUserId
        const okPeer = mine ? msg.toUserId === chat.peerUserId : msg.fromUserId === chat.peerUserId
        if (!okPeer) return
      }
      if (chat.mode === "group" && chat.groupId) {
        if (String(msg.groupId) !== String(chat.groupId)) return
      }
      this.setData({ "chat.messages": (chat.messages || []).concat(msg) })
      return
    }

    if (ev.type.startsWith("CALL_") || ev.type === "CALL_ERROR") {
      const line = `${formatTime(ev.ts || Date.now())} ${ev.type} from=${ev.from || ""} to=${ev.to || ""} callId=${ev.callId || ""} reason=${ev.reason || ""}`
      const next = [line].concat(this.data.call.events || []).slice(0, 30)
      this.setData({ "call.events": next })
      if (ev.type === "CALL_INVITE_OK" && ev.callId && ev.to) {
        this.setData({
          "call.current.callId": String(ev.callId),
          "call.current.peerUserId": String(ev.to),
        })
      }
      if (ev.type === "CALL_INVITE" && ev.callId && ev.from) {
        this.setData({
          "call.incoming.callId": String(ev.callId),
          "call.incoming.fromUserId": String(ev.from),
        })
      }
      if (ev.type === "CALL_CANCEL" || ev.type === "CALL_END" || ev.type === "CALL_TIMEOUT" || ev.type === "CALL_REJECT") {
        const cid = String(ev.callId || "")
        if (cid && cid === String(this.data.call.current.callId || "")) {
          this.setData({ "call.current.callId": "", "call.current.peerUserId": "" })
        }
        if (cid && cid === String(this.data.call.incoming.callId || "")) {
          this.setData({ "call.incoming.callId": "", "call.incoming.fromUserId": "" })
        }
      }
      return
    }
  },

  async pullGroupSince(groupId: string) {
    const chat = this.data.chat as ChatState
    const sinceMap = (chat.sinceIdByGroup || {}) as Record<string, string>
    const sinceId = sinceMap[groupId] || "0"
    try {
      const list = await apiGet<MessageEntity[]>(
        `/group/message/since?groupId=${encodeURIComponent(groupId)}&limit=50&sinceId=${encodeURIComponent(String(sinceId))}`,
      )
      if (!list.length) return
      const users = uniq(list.map((m) => String(m.fromUserId)))
      await this.ensureUserBasics(users)

      const max = list.reduce((acc, m) => {
        const id = String(m.id || "0")
        return id > acc ? id : acc
      }, sinceId)
      const nextSince = { ...sinceMap, [groupId]: max }
      this.setData({ "chat.sinceIdByGroup": nextSince })

      if (chat.open && chat.mode === "group" && chat.groupId === groupId) {
        const merged = (chat.messages || []).concat(list.map((m) => ({ ...m, createdAt: formatTime(m.createdAt) })))
        this.setData({ "chat.messages": merged })
      }
    } catch {
      // ignore
    }
  },

  async ensureUserBasics(ids: string[]) {
    const miss: string[] = []
    const map = this.data.userNameMap as Record<string, string>
    for (const id of ids) {
      if (!id) continue
      if (!map[id]) {
        miss.push(id)
      }
    }
    if (!miss.length) return
    try {
      const qs = miss.join(",")
      const list = await apiGet<UserBasicDto[]>(`/user/basic?ids=${encodeURIComponent(qs)}`)
      const next = { ...map }
      for (const u of list) {
        next[String(u.id)] = u.nickname || u.username || String(u.id)
      }
      this.setData({ userNameMap: next })
    } catch {
      // ignore
    }
  },
})
