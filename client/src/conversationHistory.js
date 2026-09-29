export const MAX_CONVERSATION_HISTORY = 30

export function loadConversationHistory(key, storage = globalThis.localStorage) {
  if (!key || !storage) return []
  try {
    const value = JSON.parse(storage.getItem(key) || '[]')
    if (!Array.isArray(value)) return []
    return value
      .filter(item => item
        && typeof item.id === 'string'
        && typeof item.query === 'string'
        && typeof item.createdAt === 'string'
        && item.result
        && typeof item.result.answer === 'string')
      .slice(-MAX_CONVERSATION_HISTORY)
  } catch {
    return []
  }
}

export function saveConversationHistory(key, history, storage = globalThis.localStorage) {
  if (!key || !storage) return false
  try {
    storage.setItem(key, JSON.stringify(history.slice(-MAX_CONVERSATION_HISTORY)))
    return true
  } catch {
    return false
  }
}
