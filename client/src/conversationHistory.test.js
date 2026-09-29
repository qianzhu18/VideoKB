import test from 'node:test'
import assert from 'node:assert/strict'
import {
  loadConversationHistory,
  MAX_CONVERSATION_HISTORY,
  saveConversationHistory
} from './conversationHistory.js'

function createStorage(initial = {}) {
  const values = new Map(Object.entries(initial))
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value)
  }
}

test('conversation history survives a reload and keeps only valid entries', () => {
  const storage = createStorage()
  const history = [{ id: 'one', query: 'What happened?', createdAt: '2026-09-29T00:00:00.000Z', result: { answer: '**A result**' } }]

  assert.equal(saveConversationHistory('videokb:chat:1', history, storage), true)
  assert.deepEqual(loadConversationHistory('videokb:chat:1', storage), history)
})

test('history loading tolerates corrupt storage and enforces the recent item limit', () => {
  const storage = createStorage({ 'broken': '{', 'long': JSON.stringify(Array.from({ length: MAX_CONVERSATION_HISTORY + 4 }, (_, index) => ({
    id: String(index), query: `Question ${index}`, createdAt: '2026-09-29T00:00:00.000Z', result: { answer: 'Answer' }
  }))) })

  assert.deepEqual(loadConversationHistory('broken', storage), [])
  const loaded = loadConversationHistory('long', storage)
  assert.equal(loaded.length, MAX_CONVERSATION_HISTORY)
  assert.equal(loaded[0].id, '4')
})
