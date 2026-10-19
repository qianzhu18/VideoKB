export function readProductDestination(hash = '') {
  const value = hash.replace(/^#/, '')
  return ['workspace', 'knowledge', 'agent'].includes(value) ? value : 'home'
}

export const PRODUCT_TASKS = [
  { id: 'learn', label: '找讲解', title: '找回讲过的内容', description: '定位概念、上下文与原片时间。', question: '这些视频对同一个概念有哪些不同解释？请列出原文依据。', icon: 'search' },
  { id: 'steps', label: '理步骤', title: '整理操作与前提', description: '先找证据，再整理步骤和注意事项。', question: '把视频中提到的操作整理成步骤，说明前置条件和注意事项，并引用原文。', icon: 'list' },
  { id: 'compare', label: '比方案', title: '对照观点和方案', description: '比较适用条件、收益与代价。', question: '对比这些分享中的方案，各自的适用条件、收益与代价是什么？请引用原文。', icon: 'layers' },
  { id: 'research', label: '找素材', title: '收集有出处的观点', description: '把关键观点与原始来源放在一起。', question: '找出这个主题的关键观点与分歧，分别列出来源和原文依据。', icon: 'file' }
]
