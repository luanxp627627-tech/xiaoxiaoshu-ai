<script setup>
import { computed } from 'vue'
import { renderMarkdown } from '../markdown'

const props = defineProps({
  msg: { type: Object, required: true },
  index: { type: Number, required: true }
})
const emit = defineEmits(['toggle-thinking'])

const isUser = computed(() => props.msg.role === 'user')
const html = computed(() => renderMarkdown(props.msg.content))
const showThinking = computed(
  () => !isUser.value && (props.msg.reasoning || props.msg.reasoningText)
)
const citations = computed(() => props.msg.citations || [])
const images = computed(() => props.msg.images || [])
</script>

<template>
  <div class="flex gap-3" :class="isUser ? 'flex-row-reverse' : ''">
    <div
      class="h-8 w-8 shrink-0 rounded-full grid place-items-center text-xs font-semibold"
      :class="isUser ? 'bg-ink text-white' : 'bg-white text-brand border border-rose-100 shadow-sm'"
    >
      {{ isUser ? '我' : '小' }}
    </div>

    <div class="max-w-[86%] min-w-0">
      <div v-if="showThinking" class="mb-2 overflow-hidden rounded-xl border border-dashed border-violet-200 bg-violet-50/70">
        <button
          type="button"
          class="flex w-full items-center gap-2 px-3 py-1.5 text-left text-[11px] text-violet-700"
          @click="emit('toggle-thinking', index)"
        >
          <span class="h-1.5 w-1.5 rounded-full bg-violet-400"></span>
          <span>深度思考</span>
          <span class="ml-auto text-[10px] text-violet-400">{{ msg.thinkingCollapsed ? '展开' : '收起' }}</span>
        </button>
        <div v-show="!msg.thinkingCollapsed" class="max-h-56 overflow-y-auto whitespace-pre-wrap border-t border-violet-100 px-3 py-2 text-[11.5px] leading-relaxed text-slate-500 scroll-thin">
          {{ msg.reasoningText || '正在思考...' }}
        </div>
      </div>

      <div
        class="rounded-2xl px-4 py-3 text-[14px] leading-7"
        :class="[
          isUser ? 'bg-brand text-white rounded-tr-md' : 'bg-white text-ink rounded-tl-md shadow-sm border border-[#efe6e2]',
          msg.error ? '!bg-rose-50 !text-rose-700 !border-rose-200' : ''
        ]"
      >
        <template v-if="isUser">
          <div v-if="msg.content" class="whitespace-pre-wrap">{{ msg.content }}</div>
        </template>
        <div v-else class="markdown" v-html="html"></div>
        <div v-if="images.length" class="mt-2 flex flex-wrap gap-2" :class="{ '!mt-0': !msg.content }">
          <a-image
            v-for="(img, i) in images"
            :key="i"
            :src="img.url"
            :width="132"
            class="overflow-hidden rounded-xl"
          />
        </div>
        <div v-if="!isUser && !msg.content && msg.streaming" class="flex h-6 items-center gap-1">
          <i class="typing-dot"></i><i class="typing-dot"></i><i class="typing-dot"></i>
        </div>
      </div>

      <div v-if="citations.length" class="mt-2 flex flex-wrap gap-1.5">
        <a-tooltip v-for="(c, i) in citations" :key="i" :title="c.snippet || ''">
          <a-tag color="pink" class="!m-0 !max-w-[220px] !truncate !text-[10.5px]">
            {{ c.source }} · {{ c.heading }}
          </a-tag>
        </a-tooltip>
      </div>

      <div v-if="!isUser" class="mt-1.5 flex items-center gap-1.5 text-[10.5px] text-[#b0a6a1]">
        <span>{{ citations.length ? `知识库检索命中 ${citations.length} 条` : '小小书 AI' }}</span>
        <span v-if="msg.streaming" class="flex items-center gap-1">
          <i class="typing-dot"></i><i class="typing-dot"></i><i class="typing-dot"></i>
        </span>
      </div>
    </div>
  </div>
</template>
