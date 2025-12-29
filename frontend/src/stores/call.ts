import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { WsEnvelope } from '../types/ws'
import { useAuthStore } from './auth'
import { useWsStore } from './ws'

type CallPhase = 'idle' | 'incoming' | 'outgoing' | 'in_call' | 'ended'

type IcePayload = {
  candidate: string
  sdpMid?: string | null
  sdpMLineIndex?: number | null
}

function uuid() {
  return crypto.randomUUID()
}

function normalizeReason(ev: WsEnvelope) {
  const raw = (ev.callReason ?? ev.reason ?? '').trim()
  return raw || null
}

export const useCallStore = defineStore('call', () => {
  const phase = ref<CallPhase>('idle')
  const direction = ref<'IN' | 'OUT' | null>(null)
  const callId = ref<string | null>(null)
  const peerUserId = ref<string | null>(null)
  const callKind = ref<'video'>('video')
  const lastReason = ref<string | null>(null)

  const localStream = ref<MediaStream | null>(null)
  const remoteStream = ref<MediaStream | null>(null)
  const audioEnabled = ref(true)
  const videoEnabled = ref(true)
  const pcState = ref<RTCPeerConnectionState | 'none'>('none')
  const iceState = ref<RTCIceConnectionState | 'none'>('none')

  const incomingOfferSdp = ref<string | null>(null)
  const inviteClientMsgId = ref<string | null>(null)

  let pc: RTCPeerConnection | null = null
  let pendingLocalIce: IcePayload[] = []
  let pendingRemoteIce: IcePayload[] = []
  let remoteDescriptionSet = false

  const visible = computed(() => phase.value !== 'idle')
  const busy = computed(() => phase.value !== 'idle' && phase.value !== 'ended')

  function resetPeerConnection() {
    try {
      pc?.close()
    } catch {
      // ignore
    }
    pc = null
    pendingLocalIce = []
    pendingRemoteIce = []
    remoteDescriptionSet = false
  }

  function stopStreams() {
    try {
      localStream.value?.getTracks().forEach((t) => t.stop())
    } catch {
      // ignore
    }
    localStream.value = null
    remoteStream.value = null
  }

  function setPhaseEnded(reason: string | null) {
    lastReason.value = reason
    phase.value = 'ended'
  }

  function clear() {
    resetPeerConnection()
    stopStreams()
    phase.value = 'idle'
    direction.value = null
    callId.value = null
    peerUserId.value = null
    incomingOfferSdp.value = null
    inviteClientMsgId.value = null
    lastReason.value = null
    audioEnabled.value = true
    videoEnabled.value = true
    pcState.value = 'none'
    iceState.value = 'none'
  }

  function ensurePeerConnection() {
    if (pc) return pc
    const conn = new RTCPeerConnection({
      iceServers: [{ urls: 'stun:stun.l.google.com:19302' }],
    })
    pcState.value = conn.connectionState
    iceState.value = conn.iceConnectionState
    conn.ontrack = (e) => {
      if (!e.streams?.length) return
      const s = e.streams[0]
      if (s) remoteStream.value = s
    }
    conn.onicecandidate = (e) => {
      const c = e.candidate
      if (!c) return
      const payload: IcePayload = {
        candidate: c.candidate,
        sdpMid: c.sdpMid,
        sdpMLineIndex: c.sdpMLineIndex,
      }
      if (!callId.value || !peerUserId.value) {
        pendingLocalIce.push(payload)
        return
      }
      sendIce(payload)
    }
    conn.onconnectionstatechange = () => {
      pcState.value = conn.connectionState
      if (conn.connectionState === 'failed' || conn.connectionState === 'disconnected') {
        setPhaseEnded('连接断开')
        resetPeerConnection()
        stopStreams()
      }
    }
    conn.oniceconnectionstatechange = () => {
      iceState.value = conn.iceConnectionState
    }
    pc = conn
    return conn
  }

  async function ensureLocalMedia() {
    if (localStream.value) return localStream.value
    if (!window.isSecureContext) {
      throw new Error('getUserMedia 需要安全上下文：请用 https 或 localhost/127.0.0.1 打开前端页面')
    }
    const stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true })
    const hasVideo = stream.getVideoTracks().some((t) => t.readyState === 'live')
    const hasAudio = stream.getAudioTracks().some((t) => t.readyState === 'live')
    if (!hasVideo) {
      stream.getTracks().forEach((t) => t.stop())
      throw new Error('未检测到可用摄像头（video track 不存在或非 live）')
    }
    if (!hasAudio) {
      lastReason.value = '未检测到可用麦克风'
    }
    localStream.value = stream
    audioEnabled.value = true
    videoEnabled.value = true
    return stream
  }

  function attachLocalTracks(conn: RTCPeerConnection, stream: MediaStream) {
    for (const t of stream.getTracks()) {
      conn.addTrack(t, stream)
    }
  }

  function send(env: WsEnvelope) {
    const ws = useWsStore()
    ws.send(env)
  }

  function sendIce(payload: IcePayload) {
    if (!callId.value || !peerUserId.value) return
    send({
      type: 'CALL_ICE',
      callId: callId.value,
      to: peerUserId.value,
      iceCandidate: payload.candidate,
      iceSdpMid: payload.sdpMid ?? null,
      iceSdpMLineIndex: payload.sdpMLineIndex ?? null,
      clientMsgId: `ice-${uuid()}`,
    })
  }

  function flushPendingLocalIce() {
    if (!pendingLocalIce.length) return
    const batch = pendingLocalIce
    pendingLocalIce = []
    for (const c of batch) {
      sendIce(c)
    }
  }

  async function applyRemoteIce(payload: IcePayload) {
    if (!pc) return
    try {
      await pc.addIceCandidate({
        candidate: payload.candidate,
        sdpMid: payload.sdpMid ?? undefined,
        sdpMLineIndex: payload.sdpMLineIndex ?? undefined,
      })
    } catch {
      // ignore
    }
  }

  async function flushPendingRemoteIce() {
    if (!pendingRemoteIce.length) return
    const batch = pendingRemoteIce
    pendingRemoteIce = []
    for (const c of batch) {
      await applyRemoteIce(c)
    }
  }

  async function startVideoCall(peerId: string) {
    if (busy.value) throw new Error('call_busy')
    const auth = useAuthStore()
    if (!auth.userId) throw new Error('unauthorized')
    const ws = useWsStore()
    await ws.connect()

    direction.value = 'OUT'
    peerUserId.value = peerId
    phase.value = 'outgoing'
    lastReason.value = null
    callId.value = null
    incomingOfferSdp.value = null
    callKind.value = 'video'

    try {
      const stream = await ensureLocalMedia()
      const conn = ensurePeerConnection()
      attachLocalTracks(conn, stream)

      const offer = await conn.createOffer()
      await conn.setLocalDescription(offer)

      inviteClientMsgId.value = `invite-${uuid()}`
      send({
        type: 'CALL_INVITE',
        clientMsgId: inviteClientMsgId.value,
        to: peerId,
        callKind: 'video',
        sdp: offer.sdp ?? '',
      })
    } catch (e) {
      setPhaseEnded(`发起失败: ${String(e)}`)
      resetPeerConnection()
      stopStreams()
    }
  }

  async function acceptIncoming() {
    if (phase.value !== 'incoming') return
    if (!callId.value || !peerUserId.value || !incomingOfferSdp.value) return
    try {
      const ws = useWsStore()
      await ws.connect()

      const stream = await ensureLocalMedia()
      const conn = ensurePeerConnection()
      attachLocalTracks(conn, stream)

      await conn.setRemoteDescription({ type: 'offer', sdp: incomingOfferSdp.value })
      remoteDescriptionSet = true
      await flushPendingRemoteIce()

      const answer = await conn.createAnswer()
      await conn.setLocalDescription(answer)

      send({
        type: 'CALL_ACCEPT',
        clientMsgId: `accept-${uuid()}`,
        callId: callId.value,
        to: peerUserId.value,
        callKind: 'video',
        sdp: answer.sdp ?? '',
      })

      phase.value = 'in_call'
      flushPendingLocalIce()
    } catch (e) {
      setPhaseEnded(`接听失败: ${String(e)}`)
      resetPeerConnection()
      stopStreams()
    }
  }

  function rejectIncoming(reason: string | null = 'rejected') {
    if (phase.value !== 'incoming') return
    if (callId.value && peerUserId.value) {
      try {
        send({
          type: 'CALL_REJECT',
          clientMsgId: `reject-${uuid()}`,
          callId: callId.value,
          to: peerUserId.value,
          callReason: reason,
        })
      } catch {
        // ignore
      }
    }
    setPhaseEnded('已拒绝')
    resetPeerConnection()
    stopStreams()
  }

  function cancelOutgoing(reason: string | null = 'canceled') {
    if (phase.value !== 'outgoing') return
    if (callId.value && peerUserId.value) {
      try {
        send({
          type: 'CALL_CANCEL',
          clientMsgId: `cancel-${uuid()}`,
          callId: callId.value,
          to: peerUserId.value,
          callReason: reason,
        })
      } catch {
        // ignore
      }
    }
    setPhaseEnded('已取消')
    resetPeerConnection()
    stopStreams()
  }

  function hangup(reason: string | null = 'hangup') {
    if (phase.value !== 'in_call') return
    if (callId.value && peerUserId.value) {
      try {
        send({
          type: 'CALL_END',
          clientMsgId: `end-${uuid()}`,
          callId: callId.value,
          to: peerUserId.value,
          callReason: reason,
        })
      } catch {
        // ignore
      }
    }
    setPhaseEnded('已挂断')
    resetPeerConnection()
    stopStreams()
  }

  function toggleAudio() {
    audioEnabled.value = !audioEnabled.value
    try {
      localStream.value?.getAudioTracks().forEach((t) => (t.enabled = audioEnabled.value))
    } catch {
      // ignore
    }
  }

  function toggleVideo() {
    videoEnabled.value = !videoEnabled.value
    try {
      localStream.value?.getVideoTracks().forEach((t) => (t.enabled = videoEnabled.value))
    } catch {
      // ignore
    }
  }

  async function handleWsEvent(ev: WsEnvelope) {
    const auth = useAuthStore()
    if (!auth.userId) return
    if (!ev.type?.startsWith('CALL_')) return

    if (ev.type === 'CALL_INVITE_OK') {
      if (phase.value !== 'outgoing') return
      if (inviteClientMsgId.value && ev.clientMsgId && ev.clientMsgId !== inviteClientMsgId.value) return
      if (!ev.callId || !peerUserId.value) return
      callId.value = ev.callId
      flushPendingLocalIce()
      return
    }

    if (ev.type === 'CALL_INVITE') {
      if (busy.value) {
        if (ev.callId && ev.from) {
          try {
            send({
              type: 'CALL_REJECT',
              clientMsgId: `reject-${uuid()}`,
              callId: ev.callId,
              to: ev.from,
              callReason: 'busy',
            })
          } catch {
            // ignore
          }
        }
        return
      }
      if (!ev.callId || !ev.from || !ev.sdp) return
      direction.value = 'IN'
      callId.value = ev.callId
      peerUserId.value = ev.from
      callKind.value = 'video'
      incomingOfferSdp.value = ev.sdp
      lastReason.value = null
      phase.value = 'incoming'
      return
    }

    if (ev.type === 'CALL_ACCEPT') {
      if (phase.value !== 'outgoing') return
      if (!callId.value || ev.callId !== callId.value) return
      if (!ev.sdp) return
      try {
        const conn = ensurePeerConnection()
        await conn.setRemoteDescription({ type: 'answer', sdp: ev.sdp })
        remoteDescriptionSet = true
        await flushPendingRemoteIce()
        phase.value = 'in_call'
      } catch (e) {
        setPhaseEnded(`连接失败: ${String(e)}`)
        resetPeerConnection()
        stopStreams()
      }
      return
    }

    if (ev.type === 'CALL_ICE') {
      if (!callId.value || !ev.callId || ev.callId !== callId.value) return
      if (!ev.iceCandidate) return
      const payload: IcePayload = {
        candidate: ev.iceCandidate,
        sdpMid: ev.iceSdpMid ?? null,
        sdpMLineIndex: ev.iceSdpMLineIndex ?? null,
      }
      if (!remoteDescriptionSet) {
        pendingRemoteIce.push(payload)
        return
      }
      await applyRemoteIce(payload)
      return
    }

    if (ev.type === 'CALL_REJECT' || ev.type === 'CALL_CANCEL' || ev.type === 'CALL_END' || ev.type === 'CALL_TIMEOUT') {
      if (ev.callId && callId.value && ev.callId !== callId.value) return
      const reason = normalizeReason(ev)
      setPhaseEnded(reason ?? (ev.type === 'CALL_TIMEOUT' ? '未接听' : '通话结束'))
      resetPeerConnection()
      stopStreams()
      return
    }

    if (ev.type === 'CALL_ERROR') {
      const reason = normalizeReason(ev) ?? '通话失败'
      setPhaseEnded(reason)
      resetPeerConnection()
      stopStreams()
      return
    }
  }

  return {
    phase,
    visible,
    busy,
    direction,
    callId,
    peerUserId,
    callKind,
    lastReason,
    localStream,
    remoteStream,
    audioEnabled,
    videoEnabled,
    pcState,
    iceState,
    startVideoCall,
    acceptIncoming,
    rejectIncoming,
    cancelOutgoing,
    hangup,
    toggleAudio,
    toggleVideo,
    clear,
    handleWsEvent,
  }
})
