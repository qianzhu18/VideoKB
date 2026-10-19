// Optional browser acceptance: requires Playwright and an installed Chrome.
// Only uses the independently copied benchmark DB and the local 15174/19092 services.
import fs from 'node:fs'
import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
const require = createRequire(import.meta.url)
const { chromium } = require('playwright')
const root = new URL('../', import.meta.url)
const mapping = JSON.parse(fs.readFileSync(new URL('eval/reports/evidence-block-map-20261008.json', root)))
const session = JSON.parse(fs.readFileSync('/tmp/videoagent-evidence-benchmark-session.json'))
for (const name of ['video-kb-benchmark-snapshot', 'video-kb-benchmark-qdrant']) {
  const mounts = JSON.parse(execFileSync('docker', ['--context', 'desktop-linux', 'inspect', name]))[0].Mounts
  if (!mounts.some(m => /^\/(private\/)?tmp\/videoagent-benchmark-/.test(m.Source)))
    throw new Error('Refusing acceptance outside the independent snapshot')
}
const base = 'http://127.0.0.1:19092'
const login = await fetch(base + '/user/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: session.username, password: session.password }) }).then(r => r.json())
if (login.code !== 0) throw new Error('Benchmark login failed')
const token = login.data.token
const user = { ...login.data.userInfo, nickname: 'BM25 验收账号' }
const spaces = await fetch(base + '/knowledge/spaces', { headers: { Authorization: 'Bearer ' + token } }).then(r => r.json())
const target = spaces.data.find(s => s.id === mapping.spaceId)
const checks = []
function check(name, passed) { checks.push({ name, passed: Boolean(passed) }); if (!passed) throw new Error(name) }
const browser = await chromium.launch({ headless: true, channel: 'chrome' })
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } })
  await page.addInitScript(({ token, user }) => {
    localStorage.setItem('authToken', token); localStorage.setItem('user', JSON.stringify(user))
  }, { token, user })
  await page.goto('http://127.0.0.1:15174')
  await page.getByRole('button', { name: '知识库', exact: true }).click()
  await page.getByRole('button', { name: `拖动内容源到 ${target.name} 归类`, exact: true }).click()
  await page.locator('.cross-search-row input').fill('缓存击穿')
  async function search() {
    const response = page.waitForResponse(r => r.url().includes('/knowledge/search/details') && r.request().method() === 'POST')
    await page.getByRole('button', { name: '仅搜证据', exact: true }).click()
    const result = await (await response).json()
    if (result.code !== 0) throw new Error('UI search failed')
    await page.locator('.search-hit').first().waitFor()
    return result.data
  }
  const healthy = await search()
  check('default hybrid workflow returns evidence without warnings', healthy.hits.length > 0 && healthy.warnings.length === 0)
  await page.locator('.original-evidence summary').first().click()
  check('original windows can be expanded with source and timestamp', await page.locator('.original-evidence').first().getAttribute('open') !== null
    && await page.locator('.original-evidence').first().getByRole('button').count() > 0)
  await page.locator('.search-results').scrollIntoViewIfNeeded()
  await page.screenshot({ path: fileURLToPath(new URL('eval/reports/milvus-bm25-ui-20261009.png', root)) })
  const source = mapping.videos[0]
  // Delete only a disposable completion receipt; preserve all evidence and external indexes.
  execFileSync('python3', ['-c', `import sys;sys.path.insert(0,'eval');from prepare_snapshot_benchmark import sql;sql('DELETE FROM knowledge_lexical_generations WHERE version_id=${Number(source.versionId)}')`], { cwd: root })
  try {
    const degraded = await search()
    await page.getByText(/BM25 词法索引未就绪或不可用/).waitFor()
    check('real incomplete-backfill warning is visible in Vue', degraded.warnings.some(w => w.includes('降级')))
    await page.locator('.search-results').scrollIntoViewIfNeeded()
    await page.screenshot({ path: fileURLToPath(new URL('eval/reports/milvus-bm25-ui-degraded-20261009.png', root)) })
  } finally {
    const restored = await fetch(base + `/knowledge/sources/${source.sourceId}/lexical-index`, {
      method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' }, body: '{}' }).then(r => r.json())
    if (restored.code !== 0) throw new Error('Failed to restore acceptance receipt')
  }
  check('recovered UI clears degradation warning', (await search()).warnings.length === 0)
  fs.writeFileSync(new URL('eval/reports/milvus-bm25-ui-20261009.json', root), JSON.stringify({ date: '2026-10-09',
    corpus: '13 frozen courses, real Milvus/SQL/Qdrant; ephemeral acceptance principal', checks,
    generationModelInvoked: false, productionAcceptance: false }, null, 2) + '\n')
  console.log(JSON.stringify({ passed: checks.length }))
} finally { await browser.close() }
