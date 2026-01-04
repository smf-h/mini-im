# 任务清单: 违禁词处理（服务端替换）

目录: `helloagents/plan/202512302234_forbidden_words/`

---

## 1. 违禁词词库
- [√] 1.1 新增 `src/main/resources/forbidden-words.txt`（UTF-8，一行一个词，支持 `#` 注释与空行）

## 2. 服务端过滤组件
- [√] 2.1 新增 `com.miniim.common.content.ForbiddenWordFilter`：按“包含子串”匹配，命中替换为 `***`

## 3. 接入点（默认覆盖范围）
- [√] 3.1 WS 单聊发送：`WsFrameHandler#handleSingleChat` 落库与下发前替换
- [√] 3.2 WS 群聊发送：`WsFrameHandler#handleGroupChat` 落库与下发前替换
- [√] 3.3 好友申请附言：WS `FRIEND_REQUEST` 与 HTTP `/friend/request/by-code` 保存与推送前替换

## 4. 文档与验证
- [√] 4.1 更新 `helloagents/wiki/api.md` 说明“服务端会对消息/附言做违禁词替换”
- [√] 4.2 更新 `helloagents/CHANGELOG.md`
- [√] 4.3 `mvn test`
- [√] 4.4 `npm -C frontend run build`
