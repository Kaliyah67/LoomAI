<script setup>
/**
 * Flash 实时对话页（学生演示项目）
 *
 * 纯文字穿搭助手，SSE 流式输出（打字机效果）。
 * 要点：
 * - 不用原生 EventSource（它带不了 Authorization 头），改用 fetch + ReadableStream 手动解析 SSE。
 * - 页面挂载时生成一个 sessionId，用于短期记忆；「新对话」会重置 sessionId 并清屏。
 * - 顶部「偏好设置」弹窗设置风格/颜色/体型，写入长期画像（user_profile）。
 */
import { nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { flashChat, getFlashProfile, saveFlashProfile } from '../services/api'
import { useAuthStore } from '../store/auth'

/** 消息列表：{ role: 'user' | 'assistant', content: string } */
const messages = ref([])
/** 输入框内容 */
const input = ref('')
/** 是否正在流式输出（防止重复发送） */
const streaming = ref(false)
/** 当前会话 id（短期记忆 key） */
const sessionId = ref('')
/** 消息列表容器（用于自动滚动到底部） */
const messageListRef = ref(null)

/** 偏好设置弹窗 */
const profileDialogVisible = ref(false)
const profileForm = ref({ style: '', color: '', bodyType: '' })

const auth = useAuthStore()

/** 当前用户聊天记录的 localStorage key（按 userId 隔离，登录状态下保留聊天） */
function flashStorageKey() {
  const uid = auth.user?.userId
  return uid ? `flash_chat_${uid}` : 'flash_chat_guest'
}

/** 从 localStorage 恢复会话：登录后切换页面再回来，聊天不丢 */
function loadFlashChat() {
  try {
    const raw = localStorage.getItem(flashStorageKey())
    if (raw) {
      const saved = JSON.parse(raw)
      sessionId.value = saved.sessionId || generateSessionId()
      messages.value = Array.isArray(saved.messages) ? saved.messages : []
      return
    }
  } catch {
    // 解析失败则按全新会话处理
  }
  sessionId.value = generateSessionId()
}

/** 把当前会话写回 localStorage */
function persistFlashChat() {
  try {
    localStorage.setItem(flashStorageKey(), JSON.stringify({
      sessionId: sessionId.value,
      messages: messages.value,
    }))
  } catch {
    // 存储不可用时忽略
  }
}

watch([sessionId, messages], persistFlashChat, { deep: true })

/** 生成一个会话 id（优先用浏览器 crypto.randomUUID） */
function generateSessionId() {
  if (window.crypto && typeof window.crypto.randomUUID === 'function') {
    return window.crypto.randomUUID()
  }
  return 'session-' + Date.now() + '-' + Math.random().toString(36).slice(2)
}

/** 滚动到消息底部 */
async function scrollToBottom() {
  await nextTick()
  const el = messageListRef.value
  if (el) el.scrollTop = el.scrollHeight
}

/** 解析单个 SSE 事件（形如 "data:{...}"），把 delta 追加到对应消息 */
function handleSseEvent(rawEvent, assistantMsg) {
  for (const line of rawEvent.split('\n')) {
    if (!line.startsWith('data:')) continue
    const payload = line.slice('data:'.length).trim()
    if (!payload) continue
    let obj
    try {
      obj = JSON.parse(payload)
    } catch {
      continue
    }
    if (obj && typeof obj.delta === 'string') {
      assistantMsg.content += obj.delta
    } else if (obj && typeof obj.error === 'string') {
      assistantMsg.content += '\n[出错了：' + obj.error + ']'
    }
  }
}

/** 发送消息 */
async function handleSend() {
  const text = input.value.trim()
  if (!text || streaming.value) return

  messages.value.push({ role: 'user', content: text })
  input.value = ''
  messages.value.push({ role: 'assistant', content: '' })
  // 通过数组下标取到的是响应式代理，直接改 content 能触发视图更新
  const assistantMsg = messages.value[messages.value.length - 1]
  streaming.value = true

  try {
    const stream = await flashChat({ message: text, sessionId: sessionId.value })
    const reader = stream.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let idx
      while ((idx = buffer.indexOf('\n\n')) !== -1) {
        const rawEvent = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        handleSseEvent(rawEvent, assistantMsg)
      }
      scrollToBottom()
    }
    // 兜底：处理残留的、没有以 \n\n 结尾的事件
    if (buffer.trim()) {
      handleSseEvent(buffer, assistantMsg)
    }
    if (!assistantMsg.content) {
      assistantMsg.content = '（未收到回复）'
    }
  } catch (err) {
    assistantMsg.content += '\n[出错了：' + (err?.message || '未知错误') + ']'
    ElMessage.error(err?.message || '对话失败')
  } finally {
    streaming.value = false
    scrollToBottom()
  }
}

/** 新对话：重置 session 并清屏 */
function startNewChat() {
  sessionId.value = generateSessionId()
  messages.value = []
}

/** 打开偏好设置：先拉取已保存的画像 */
async function openProfileDialog() {
  try {
    const { data } = await getFlashProfile()
    const p = data?.data || {}
    profileForm.value = {
      style: p.style || '',
      color: p.color || '',
      bodyType: p.bodyType || '',
    }
  } catch {
    // 拉取失败不阻塞，仍允许手动填写
  }
  profileDialogVisible.value = true
}

/** 保存偏好设置 */
async function handleSaveProfile() {
  try {
    await saveFlashProfile({
      style: profileForm.value.style || '',
      color: profileForm.value.color || '',
      bodyType: profileForm.value.bodyType || '',
    })
    ElMessage.success('偏好已保存')
    profileDialogVisible.value = false
  } catch (err) {
    ElMessage.error(err?.message || '保存失败')
  }
}

/** 回车发送（Shift+Enter 换行） */
function handleKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    handleSend()
  }
}

onMounted(() => {
  loadFlashChat()
})
</script>

<template>
  <div class="flash-page">
    <div class="flash-header">
      <div>
        <h1 class="page-title">Flash 助手</h1>
        <p class="page-hint">服装搭配顾问，结合知识库与你的偏好给出建议</p>
      </div>
      <div class="flash-actions">
        <button class="ui-btn ui-btn-soft" @click="startNewChat">新对话</button>
        <button class="ui-btn ui-btn-soft" @click="openProfileDialog">偏好设置</button>
      </div>
    </div>

    <div class="flash-chat ui-card">
      <!-- 消息列表 -->
      <div ref="messageListRef" class="flash-messages">
        <div v-if="!messages.length" class="flash-empty">
          <p>你好，我是 Flash，你的穿搭助手</p>
          <p class="flash-empty-sub">问我「通勤穿什么」「约会怎么搭」试试吧</p>
        </div>
        <div
          v-for="(msg, i) in messages"
          :key="i"
          class="flash-msg"
          :class="msg.role === 'user' ? 'flash-msg--user' : 'flash-msg--assistant'"
        >
          <div class="flash-bubble">{{ msg.content }}</div>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="flash-input-bar">
        <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="输入你的穿搭问题…（Enter 发送，Shift+Enter 换行）"
          :disabled="streaming"
          @keydown="handleKeydown"
        />
        <button
          class="ui-btn ui-btn-primary flash-send"
          :disabled="streaming || !input.trim()"
          @click="handleSend"
        >
          {{ streaming ? '回复中…' : '发送' }}
        </button>
      </div>
    </div>

    <!-- 偏好设置弹窗 -->
    <el-dialog v-model="profileDialogVisible" title="我的穿搭偏好" width="420px">
      <el-form label-width="64px">
        <el-form-item label="风格">
          <el-input v-model="profileForm.style" placeholder="如：简约 / 复古 / 街头" />
        </el-form-item>
        <el-form-item label="颜色">
          <el-input v-model="profileForm.color" placeholder="如：黑白灰 / 暖色系" />
        </el-form-item>
        <el-form-item label="体型">
          <el-input v-model="profileForm.bodyType" placeholder="如：偏瘦 / 微胖 / 梨形" />
        </el-form-item>
      </el-form>
      <template #footer>
        <button class="ui-btn ui-btn-soft" @click="profileDialogVisible = false">取消</button>
        <button class="ui-btn ui-btn-primary" @click="handleSaveProfile">保存</button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.flash-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 64px - 18px - 28px);
  min-height: 440px;
}
.flash-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}
.flash-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.flash-chat {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--border);
}
.flash-messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.flash-empty {
  margin: auto;
  text-align: center;
  color: var(--muted);
}
.flash-empty p {
  margin: 4px 0;
}
.flash-empty-sub {
  font-size: 13px;
}
.flash-msg {
  display: flex;
}
.flash-msg--user {
  justify-content: flex-end;
}
.flash-msg--assistant {
  justify-content: flex-start;
}
.flash-bubble {
  max-width: 72%;
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.flash-msg--user .flash-bubble {
  background: linear-gradient(180deg, var(--primary), var(--primary-600));
  color: #fff;
  border-bottom-right-radius: 4px;
}
.flash-msg--assistant .flash-bubble {
  background: #f2f4f9;
  color: var(--text);
  border-bottom-left-radius: 4px;
}
.flash-input-bar {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  padding: 14px 16px;
  border-top: 1px solid var(--border);
  background: #fff;
}
.flash-input-bar :deep(.el-textarea__inner) {
  border-radius: 10px;
  font-size: 14px;
}
.flash-send {
  flex-shrink: 0;
  height: 40px;
}
</style>
