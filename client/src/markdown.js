import { marked } from 'marked'

const ALLOWED_TAGS = new Set([
  'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'P', 'BR', 'HR', 'BLOCKQUOTE',
  'UL', 'OL', 'LI', 'STRONG', 'EM', 'DEL', 'CODE', 'PRE', 'A',
  'TABLE', 'THEAD', 'TBODY', 'TR', 'TH', 'TD', 'INPUT', 'SUP', 'SUB', 'IMG'
])

export function renderMarkdown(markdown) {
  if (!markdown) return ''

  let cleanText = markdown.replace(/<think>[\s\S]*?<\/think>/gi, '')
  if (cleanText.includes('</think>')) cleanText = cleanText.split('</think>').pop()
  if (!cleanText.trim()) cleanText = markdown
  cleanText = linkVideoTimestamps(cleanText)

  const template = document.createElement('template')
  template.innerHTML = marked.parse(cleanText, { gfm: true, breaks: false, async: false })
  template.content.querySelectorAll('*').forEach(sanitizeNode)
  return template.innerHTML
}

function sanitizeNode(node) {
  if (!ALLOWED_TAGS.has(node.tagName)) {
    node.replaceWith(document.createTextNode(node.textContent || ''))
    return
  }

  if (node.tagName === 'INPUT') {
    const isCheckbox = (node.getAttribute('type') || '').toLowerCase() === 'checkbox'
    if (!isCheckbox) {
      node.remove()
      return
    }
    const checked = node.hasAttribute('checked')
    node.setAttribute('type', 'checkbox')
    node.setAttribute('disabled', '')
    for (const attribute of [...node.attributes]) {
      if (!['type', 'checked', 'disabled'].includes(attribute.name)) node.removeAttribute(attribute.name)
    }
    if (checked) node.setAttribute('checked', '')
    return
  }

  if (node.tagName === 'IMG') {
    const source = node.getAttribute('src') || ''
    if (!/^https?:\/\//i.test(source)) {
      node.replaceWith(document.createTextNode(node.getAttribute('alt') || ''))
      return
    }
    const alt = node.getAttribute('alt') || ''
    const title = node.getAttribute('title')
    for (const attribute of [...node.attributes]) node.removeAttribute(attribute.name)
    node.setAttribute('src', source)
    node.setAttribute('alt', alt)
    if (title) node.setAttribute('title', title)
    node.setAttribute('loading', 'lazy')
    node.setAttribute('referrerpolicy', 'no-referrer')
    return
  }

  for (const attribute of [...node.attributes]) {
    const allowed = node.tagName === 'A'
      && (attribute.name === 'href' || attribute.name === 'title')
    if (!allowed) node.removeAttribute(attribute.name)
  }
  if (node.tagName !== 'A') return

  const href = node.getAttribute('href') || ''
  if (!/^(https?:|mailto:|\/|#)/i.test(href)) node.removeAttribute('href')
  node.setAttribute('rel', 'noopener noreferrer')
  if (!href.startsWith('#video-t=')) node.setAttribute('target', '_blank')
}

function linkVideoTimestamps(markdown) {
  return markdown.replace(/\[((?:\d{1,2}:)?\d{1,2}:\d{2})\](?!\()/g, (match, timestamp) => {
    const parts = timestamp.split(':').map(Number)
    const seconds = parts.length === 3
      ? parts[0] * 3600 + parts[1] * 60 + parts[2]
      : parts[0] * 60 + parts[1]
    return `[${timestamp}](#video-t=${seconds})`
  })
}
