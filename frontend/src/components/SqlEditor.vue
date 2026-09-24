<template>
  <div ref="editorRef" :style="{ height: height, width: '100%' }"></div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import * as monaco from 'monaco-editor'
import editorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'
import 'monaco-editor/esm/vs/basic-languages/sql/sql.contribution'

self.MonacoEnvironment = {
  getWorker: () => new editorWorker()
}

const props = defineProps({
  modelValue: { type: String, default: '' },
  height: { type: String, default: '240px' },
  language: { type: String, default: 'sql' },
  theme: { type: String, default: 'vs' },
  readOnly: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue'])

const editorRef = ref(null)
let editor = null

onMounted(() => {
  editor = monaco.editor.create(editorRef.value, {
    value: props.modelValue,
    language: props.language,
    theme: props.theme,
    readOnly: props.readOnly,
    fontSize: 13,
    minimap: { enabled: false },
    scrollBeyondLastLine: false,
    lineNumbers: 'on',
    glyphMargin: false,
    folding: false,
    lineDecorationsWidth: 0,
    lineNumbersMinChars: 3,
    automaticLayout: true
  })

  editor.onDidChangeModelContent(() => {
    emit('update:modelValue', editor.getValue())
  })
})

watch(() => props.modelValue, (newVal) => {
  if (editor && newVal !== editor.getValue()) {
    editor.setValue(newVal)
  }
})

onUnmounted(() => {
  if (editor) {
    editor.dispose()
    editor = null
  }
})

defineExpose({
  focus: () => editor?.focus(),
  getValue: () => editor?.getValue(),
  insertAtCursor: (text) => {
    if (!editor) return
    const sel = editor.getSelection()
    if (sel) {
      editor.executeEdits('insert', [{
        range: sel,
        text,
        forceMoveMarkers: true
      }])
      editor.focus()
    }
  }
})
</script>
