<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { message as toast } from 'ant-design-vue'
import {
  DeleteOutlined,
  MenuOutlined,
  PictureOutlined,
  PlusOutlined
} from '@ant-design/icons-vue'
import { api, streamChat, uploadImage } from './api'
import MessageItem from './components/MessageItem.vue'

const conversations = ref([])
const currentId = ref(null)
const title = ref('新对话')
const mode = ref(localStorage.getItem('xxs.ai.mode') || 'assistant')
const reasoning = ref(localStorage.getItem('xxs.ai.reasoning') === '1')
const messages = ref([])
const streaming = ref(false)
const input = ref('')
const status = ref(null)
const sidebarOpen = ref(false)
const scrollBox = ref(null)
const pendingImages = ref([])
const uploading = ref(false)
const fileInput = ref(null)

const tagText = computed(() => (mode.value === 'service' ? '智能客服' : '通用助手'))
const displayModel = computed(() =>
  reasoning.value ? 'deepseek-reasoner' : status.value?.model || 'deepseek-chat'
)
const mockMode = computed(() => !!status.value?.mockMode)

const suggestions = [
  { title: '发布笔记', text: '怎么发布一篇图文笔记？' },
  { title: '账号隐私', text: '如何保护我的账号隐私？' },
  { title: '当前时间', text: '现在几点了？' },
  { title: 'MCP 查 QQ', text: '帮我查一下 QQ 10001 的资料' },
  { title: '数学计算', text: '帮我算一下 (128 + 72) * 3' }
]

onMounted(async () => {
  await loadStatus()
  await loadConversations()
})

async function loadStatus() {
  try {
    status.value = await api('/api/status')
  } catch (e) {
    status.value = null
  }
}

async function loadConversations() {
  try {
    conversations.value = await api('/api/conversations')
    if (currentId.value) {
      const current = conversations.value.find((c) => c.id === currentId.value)
      if (current) title.value = current.title
      return
    }
    if (conversations.value.length) {
      await openConversation(conversations.value[0].id, false)
    }
  } catch (e) {
    // 后端没起来时保持欢迎页
  }
}

async function openConversation(id, closeDrawer = true) {
  if (streaming.value) return
  try {
    const list = await api(`/api/conversations/${id}/messages`)
    const conv = conversations.value.find((c) => c.id === id)
    currentId.value = id
    mode.value = conv ? conv.mode : mode.value
    title.value = conv ? conv.title : '对话'
    messages.value = list.map((m) => ({
      role: m.role,
      content: m.content,
      citations: parseCitations(m.citations),
      images: parseImages(m.images),
      createdAt: m.createdAt
    }))
    if (closeDrawer) sidebarOpen.value = false
    scrollBottom()
  } catch (e) {
    toast.error('加载会话失败')
  }
}

function parseCitations(raw) {
  if (!raw) return []
  try {
    const list = JSON.parse(raw)
    return Array.isArray(list) ? list : []
  } catch (e) {
    return []
  }
}

function parseImages(raw) {
  if (!raw) return []
  try {
    const list = JSON.parse(raw)
    return Array.isArray(list) ? list : []
  } catch (e) {
    return []
  }
}

function newChat() {
  if (streaming.value) return
  currentId.value = null
  title.value = '新对话'
  messages.value = []
  input.value = ''
  pendingImages.value = []
  sidebarOpen.value = false
}

function setMode(value) {
  if (streaming.value) {
    toast.info('请等当前回答结束')
    return
  }
  if (mode.value === value) return
  mode.value = value
  localStorage.setItem('xxs.ai.mode', value)
  newChat()
}

function toggleReasoning(value) {
  reasoning.value = value
  localStorage.setItem('xxs.ai.reasoning', value ? '1' : '0')
}

async function send() {
  const text = input.value.trim()
  const images = pendingImages.value.slice()
  if ((!text && !images.length) || streaming.value || uploading.value) return

  streaming.value = true
  input.value = ''
  pendingImages.value = []
  messages.value.push({ role: 'user', content: text, images })
  const aiIndex = messages.value.length
  messages.value.push({
    role: 'assistant',
    content: '',
    streaming: true,
    citations: [],
    reasoning: reasoning.value,
    reasoningText: ''
  })
  scrollBottom()

  try {
    await streamChat(
      {
        conversationId: currentId.value,
        message: text,
        mode: mode.value,
        reasoning: reasoning.value,
        images
      },
      (event, data) => handleEvent(event, data, aiIndex)
    )
  } catch (e) {
    const ai = messages.value[aiIndex]
    if (!ai.content) {
      ai.content = '请求失败：' + e.message
      ai.error = true
    }
    ai.streaming = false
    toast.error('对话请求失败')
  } finally {
    streaming.value = false
    const ai = messages.value[aiIndex]
    if (ai) ai.streaming = false
    await loadConversations()
    scrollBottom()
  }
}

function pickImages() {
  if (streaming.value) {
    toast.info('请等当前回答结束')
    return
  }
  fileInput.value?.click()
}

async function onFileChange(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  if (!files.length) return

  const slots = 4 - pendingImages.value.length
  if (slots <= 0) {
    toast.info('一次最多发送 4 张图片')
    return
  }

  uploading.value = true
  for (const file of files.slice(0, slots)) {
    if (file.size > 10 * 1024 * 1024) {
      toast.error(`${file.name} 超过 10MB`)
      continue
    }
    try {
      const image = await uploadImage(file)
      pendingImages.value.push(image)
    } catch (e) {
      toast.error('图片上传失败：' + e.message)
    }
  }
  uploading.value = false
}

function removePendingImage(index) {
  pendingImages.value.splice(index, 1)
}

function handleEvent(event, data, aiIndex) {
  const ai = messages.value[aiIndex]
  if (!ai) return
  if (event === 'meta') {
    currentId.value = data.conversationId
    title.value = data.title || title.value
    ai.citations = data.citations || []
    ai.reasoning = !!data.reasoning
    if (!conversations.value.some((c) => c.id === data.conversationId)) {
      conversations.value.unshift({
        id: data.conversationId,
        title: title.value,
        mode: data.mode,
        updatedAt: new Date().toISOString(),
        messageCount: 0
      })
    }
  } else if (event === 'reasoning') {
    ai.reasoning = true
    ai.reasoningText = (ai.reasoningText || '') + (data.content || '')
  } else if (event === 'delta') {
    ai.content += data.content || ''
  } else if (event === 'error') {
    ai.content = data.message || '对话失败'
    ai.error = true
    ai.streaming = false
  } else if (event === 'done') {
    ai.streaming = false
    if (ai.reasoningText) ai.thinkingCollapsed = true
    if (data.title) title.value = data.title
  }
  scrollBottom()
}

function toggleThinking(index) {
  const target = messages.value[index]
  if (target) target.thinkingCollapsed = !target.thinkingCollapsed
}

function quickAsk(text) {
  if (streaming.value) {
    toast.info('请等当前回答结束')
    return
  }
  input.value = text
  send()
}

async function removeConversation(conv) {
  if (!window.confirm('删除这个对话及全部消息？')) return
  try {
    await api(`/api/conversations/${conv.id}`, { method: 'DELETE' })
    conversations.value = conversations.value.filter((c) => c.id !== conv.id)
    if (currentId.value === conv.id) newChat()
    toast.success('对话已删除')
  } catch (e) {
    toast.error('删除失败')
  }
}

function scrollBottom() {
  nextTick(() => {
    const box = scrollBox.value
    if (box) box.scrollTop = box.scrollHeight
  })
}

function autoGrow(event) {
  const el = event.target
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 150) + 'px'
}

function onSubmitKey(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    send()
  }
}

function formatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  const now = new Date()
  if (date.toDateString() === now.toDateString()) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return `${date.getMonth() + 1}/${date.getDate()}`
}
</script>

<template>
  <div class="flex h-screen overflow-hidden">
    <aside
      class="fixed inset-y-0 left-0 z-30 flex w-[276px] shrink-0 flex-col border-r border-[#efe6e2] bg-white transition-transform md:static md:translate-x-0"
      :class="sidebarOpen ? 'translate-x-0' : '-translate-x-full'"
    >
      <div class="flex items-center gap-2.5 px-5 pb-4 pt-5">
        <div class="grid h-9 w-9 place-items-center rounded-xl bg-brand text-sm font-bold text-white shadow-sm">小</div>
        <div>
          <div class="text-[15px] font-bold tracking-wide">小小书 AI</div>
          <div class="text-[10.5px] text-[#a99d97]">Spring AI × DeepSeek</div>
        </div>
      </div>

      <div class="px-4">
        <a-button type="primary" block class="!h-9 !rounded-xl !font-semibold" @click="newChat">
          <template #icon><PlusOutlined /></template>
          新建对话
        </a-button>
      </div>

      <div class="mx-4 mt-4 grid grid-cols-2 gap-1 rounded-xl bg-[#f2eeeb] p-1">
        <button
          v-for="item in [
            { value: 'assistant', label: '通用助手' },
            { value: 'service', label: '智能客服' }
          ]"
          :key="item.value"
          type="button"
          class="rounded-lg px-2 py-2 text-[12.5px] font-semibold transition"
          :class="mode === item.value ? 'bg-white text-ink shadow-sm' : 'text-[#7b716c]'"
          @click="setMode(item.value)"
        >
          {{ item.label }}
        </button>
      </div>

      <div class="px-5 pb-1 pt-5 text-[10.5px] font-semibold tracking-wider text-[#b3a8a2]">最近对话</div>
      <div class="scroll-thin flex-1 overflow-y-auto px-2 pb-2">
        <div
          v-for="conv in conversations"
          :key="conv.id"
          class="group relative cursor-pointer rounded-xl px-3 py-2.5 transition"
          :class="conv.id === currentId ? 'bg-[#fff0f3]' : 'hover:bg-[#faf6f4]'"
          @click="openConversation(conv.id)"
        >
          <div class="truncate pr-5 text-[12.5px] font-medium" :class="conv.id === currentId ? 'text-brand' : 'text-ink'">
            {{ conv.title }}
          </div>
          <div class="mt-0.5 text-[10px] text-[#b3a8a2]">
            {{ conv.mode === 'service' ? '智能客服 · ' : '' }}{{ formatTime(conv.updatedAt) }}
          </div>
          <button
            type="button"
            class="absolute right-2 top-1/2 hidden -translate-y-1/2 rounded-md p-1 text-[#c4b8b2] hover:bg-white hover:text-rose-500 group-hover:block"
            title="删除对话"
            @click.stop="removeConversation(conv)"
          >
            <DeleteOutlined />
          </button>
        </div>
        <div v-if="!conversations.length" class="px-3 py-6 text-center text-[11.5px] text-[#b3a8a2]">
          还没有对话，直接开始聊吧
        </div>
      </div>

      <div class="border-t border-[#f1e9e5] px-4 py-3 text-[10.5px] text-[#a99d97]">
        <div class="flex items-center gap-1.5">
          <span class="h-1.5 w-1.5 rounded-full" :class="mockMode ? 'bg-amber-400' : 'bg-emerald-500'"></span>
          {{ mockMode ? '本地演示模式运行中' : '已连接 DeepSeek' }}
        </div>
        <div v-if="status" class="mt-1 flex gap-3 text-[10px] text-[#bcb1ab]">
          <span>知识库 {{ status.knowledgeChunks }} 段</span>
          <span>{{ status.tools.length }} 个工具</span>
          <span>{{ status.database || 'MySQL' }}</span>
          <span>{{ status.storage || 'MinIO' }}</span>
        </div>
        <div v-if="status?.mcpServers?.length" class="mt-1 flex flex-wrap gap-x-3 gap-y-0.5 text-[10px] text-[#bcb1ab]">
          <span
            v-for="server in status.mcpServers"
            :key="server.name"
            class="inline-flex items-center gap-1"
            :title="server.error || server.url"
          >
            <span
              class="h-1 w-1 rounded-full"
              :class="server.connected ? 'bg-emerald-500' : server.enabled ? 'bg-rose-400' : 'bg-slate-300'"
            ></span>
            MCP {{ server.name }}{{ server.connected ? ` (${server.tools.length})` : '' }}
          </span>
        </div>
      </div>
    </aside>

    <div v-if="sidebarOpen" class="fixed inset-0 z-20 bg-black/30 md:hidden" @click="sidebarOpen = false"></div>

    <main class="relative flex min-w-0 flex-1 flex-col">
      <header class="flex h-[62px] shrink-0 items-center gap-2.5 border-b border-[#efe6e2] bg-white/80 px-4 backdrop-blur md:px-6">
        <a-button class="md:hidden" type="text" @click="sidebarOpen = true">
          <template #icon><MenuOutlined /></template>
        </a-button>
        <div class="min-w-0 flex-1 truncate text-[14px] font-semibold">{{ title }}</div>
        <a-tag color="pink" class="!m-0 !rounded-full !border-0 !bg-[#fff0f3] !text-[10.5px] !font-semibold !text-brand">
          {{ tagText }}
        </a-tag>
        <span class="hidden rounded-full border border-[#ece3df] bg-white px-2.5 py-1 text-[11px] text-[#8a7f7a] sm:inline">
          {{ displayModel }}
        </span>
      </header>

      <div ref="scrollBox" class="scroll-thin flex-1 overflow-y-auto px-4 pb-6 pt-6 md:px-8">
        <div v-if="!messages.length" class="mx-auto flex h-full max-w-[720px] flex-col items-center justify-center pb-16 text-center">
          <div class="grid h-16 w-16 place-items-center rounded-2xl bg-white text-2xl font-bold text-brand shadow-sm ring-1 ring-rose-100">小</div>
          <h1 class="mt-5 text-[22px] font-bold">嗨，我是小小书 AI</h1>
          <p class="mt-2 text-[13px] text-[#8a7f7a]">
            可以聊生活，也能帮你查时间、做计算、检索知识库和联网搜索。
          </p>
          <div class="mt-7 grid w-full grid-cols-1 gap-2.5 sm:grid-cols-2">
            <button
              v-for="s in suggestions"
              :key="s.title"
              type="button"
              class="rounded-xl border border-[#efe6e2] bg-white px-4 py-3 text-left transition hover:-translate-y-0.5 hover:border-rose-200 hover:shadow-md"
              @click="quickAsk(s.text)"
            >
              <div class="text-[12.5px] font-semibold">{{ s.title }}</div>
              <div class="mt-0.5 truncate text-[11px] text-[#a99d97]">{{ s.text }}</div>
            </button>
          </div>
        </div>

        <div v-else class="mx-auto max-w-[820px] space-y-5">
          <MessageItem
            v-for="(msg, index) in messages"
            :key="index"
            :msg="msg"
            :index="index"
            @toggle-thinking="toggleThinking"
          />
        </div>
      </div>

      <div class="shrink-0 bg-gradient-to-t from-[#f7f4f2] via-[#f7f4f2] to-transparent px-4 pb-5 pt-2 md:px-8">
        <div class="mx-auto max-w-[820px] rounded-2xl border border-[#e7ded9] bg-white p-2.5 shadow-[0_10px_40px_rgba(60,40,30,.08)]">
          <input
            ref="fileInput"
            type="file"
            accept="image/png,image/jpeg,image/gif,image/webp"
            multiple
            class="hidden"
            @change="onFileChange"
          />
          <div v-if="pendingImages.length || uploading" class="mb-2 flex flex-wrap items-center gap-2 px-1">
            <div v-for="(img, i) in pendingImages" :key="img.objectName" class="relative">
              <img :src="img.url" :alt="img.name" class="h-16 w-16 rounded-xl object-cover ring-1 ring-[#e7ded9]" />
              <button
                type="button"
                class="absolute -right-1.5 -top-1.5 grid h-4 w-4 place-items-center rounded-full bg-ink/80 text-[10px] leading-none text-white hover:bg-rose-500"
                title="移除"
                @click="removePendingImage(i)"
              >×</button>
            </div>
            <div v-if="uploading" class="grid h-16 w-16 place-items-center rounded-xl border border-dashed border-rose-200 text-[10px] text-brand">
              上传中
            </div>
          </div>
          <div class="flex items-end gap-2.5">
            <a-button
              type="text"
              shape="circle"
              class="!h-9 !w-9 !shrink-0 !text-[#8a7f7a]"
              :disabled="streaming || uploading"
              title="上传图片"
              @click="pickImages"
            >
              <template #icon><PictureOutlined /></template>
            </a-button>
            <textarea
              v-model="input"
              rows="1"
              placeholder="给小小书 AI 发消息，也可以直接发图片..."
              class="max-h-[150px] flex-1 resize-none border-0 bg-transparent px-1 py-1.5 text-[14px] leading-6 outline-none"
              @input="autoGrow"
              @keydown="onSubmitKey"
            ></textarea>
            <a-button
              type="primary"
              shape="circle"
              class="!h-9 !w-9 !shrink-0 send-btn"
              :disabled="streaming || uploading || (!input.trim() && !pendingImages.length)"
              @click="send"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="send-icon">
                <path d="M22 2L11 13"/>
                <path d="M22 2L15 22L11 13L2 9L22 2Z"/>
              </svg>
            </a-button>
          </div>
          <div class="mt-1 flex items-center gap-2.5 px-1">
            <label class="flex cursor-pointer items-center gap-1.5 text-[10.5px] text-[#8a7f7a]">
              <a-switch size="small" :checked="reasoning" @change="toggleReasoning" />
              深度思考
            </label>
            <span class="text-[10.5px] text-[#b4aaa5]">Enter 发送 · Shift + Enter 换行</span>
            <div class="scroll-thin ml-auto flex gap-1.5 overflow-x-auto">
              <button
                v-for="s in suggestions"
                :key="s.title"
                type="button"
                class="whitespace-nowrap rounded-full border border-[#eee5e1] bg-[#faf7f5] px-2.5 py-1 text-[10.5px] text-[#7c726d] hover:border-rose-200 hover:text-brand"
                @click="quickAsk(s.text)"
              >
                {{ s.title }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<style>
.send-btn .ant-btn-icon,
.send-btn .anticon {
  margin: 0 !important;
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
  line-height: 0 !important;
}
.send-icon {
  width: 18px !important;
  height: 18px !important;
  margin: 0 !important;
  vertical-align: middle !important;
  display: block !important;
}
</style>
