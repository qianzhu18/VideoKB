#!/usr/bin/env node
// 检查 markdown 库的本地链接完整性（docs-ops skill 通用版）
// 用法: node check-links.mjs <docs根目录> [--include-archive] [--include-reference]
// 只检查头部 12 行内标注 状态：`active|draft|blocked` 的文档；默认跳过 archive 与 99-archive 目录。

import fs from 'node:fs';
import path from 'node:path';

const positional = process.argv.slice(2).filter((a) => !a.startsWith('--'));
const includeArchive = process.argv.includes('--include-archive');
const includeReference = process.argv.includes('--include-reference');
const docsRoot = path.resolve(positional[0] ?? process.cwd());
const ignoredDirectories = new Set(['.git', 'node_modules']);

function listMarkdownFiles(directory) {
  const files = [];
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    if (ignoredDirectories.has(entry.name)) continue;
    const fullPath = path.join(directory, entry.name);
    const relativePath = path.relative(docsRoot, fullPath);
    if (entry.isDirectory()) {
      const isArchive = relativePath === 'archive' || relativePath.startsWith('archive/')
        || relativePath === '99-archive' || relativePath.startsWith('99-archive/');
      if (!includeArchive && isArchive) continue;
      files.push(...listMarkdownFiles(fullPath));
    } else if (entry.isFile() && entry.name.endsWith('.md')) {
      files.push(fullPath);
    }
  }
  return files;
}

function isActiveDocument(content) {
  const header = content.split('\n').slice(0, 12).join('\n').toLowerCase();
  return /状态：[^\n]*`(?:active|draft|blocked|in-progress|open)[^`]*`/.test(header)
    || /status:?[^\n]*`?(?:active|draft|blocked|in-progress|open)`?/i.test(header);
}

function targetIsExternal(target) {
  return /^(https?:|mailto:|tel:|data:)/i.test(target);
}

function localTarget(rawTarget) {
  const trimmed = rawTarget.trim().replace(/^<|>$/g, '');
  const withoutTitle = trimmed.replace(/\s+(?:"[^"]*"|'[^']*')$/, '');
  const hashIndex = withoutTitle.indexOf('#');
  const fileTarget = hashIndex === -1 ? withoutTitle : withoutTitle.slice(0, hashIndex);
  const anchorTarget = hashIndex === -1 ? '' : withoutTitle.slice(hashIndex + 1);
  try {
    return {
      fileTarget: decodeURIComponent(fileTarget).replace(/\\ /g, ' '),
      anchorTarget: decodeURIComponent(anchorTarget),
    };
  } catch {
    return { fileTarget: fileTarget.replace(/\\ /g, ' '), anchorTarget };
  }
}

function headingSlug(heading) {
  return heading
    .replace(/`([^`]+)`/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .toLowerCase()
    .replace(/[!"#$%&'()*+,./:;<=>?@[\\\]^`{|}~，。！？：；、（）【】《》“”‘’]/g, '')
    .trim()
    .replace(/\s+/g, '-');
}

function hasAnchor(markdownFile, anchorTarget) {
  const wanted = anchorTarget.toLowerCase();
  const headings = fs.readFileSync(markdownFile, 'utf8').matchAll(/^#{1,6}\s+(.+?)\s*#*\s*$/gm);
  const seen = new Map();
  for (const match of headings) {
    const base = headingSlug(match[1]);
    const count = seen.get(base) ?? 0;
    seen.set(base, count + 1);
    const slug = count === 0 ? base : `${base}-${count}`;
    if (slug === wanted) return true;
  }
  return false;
}

if (!fs.existsSync(docsRoot)) {
  console.error(`docs root not found: ${docsRoot}`);
  process.exit(1);
}

const failures = [];
let checkedLinks = 0;
for (const markdownFile of listMarkdownFiles(docsRoot)) {
  const content = fs.readFileSync(markdownFile, 'utf8');
  if (!includeReference && !isActiveDocument(content)) continue;
  const linkPattern = /!?\[[^\]]*\]\(([^)]+)\)/g;
  // Code examples are not document navigation; preserve offsets for diagnostics.
  const prose = content.replace(/^(`{3,}|~{3,})[^\n]*\n[\s\S]*?^\1[^\n]*$/gm, m => m.replace(/[^\n]/g, ' '))
    .replace(/(`+)([\s\S]*?)\1/g, m => m.replace(/[^\n]/g, ' '));
  for (const match of prose.matchAll(linkPattern)) {
    const target = localTarget(match[1]);
    if ((!target.fileTarget && !target.anchorTarget) || targetIsExternal(target.fileTarget)) continue;
    checkedLinks += 1;
    const resolved = target.fileTarget
      ? path.resolve(path.dirname(markdownFile), target.fileTarget)
      : markdownFile;
    if (!fs.existsSync(resolved)) {
      const line = content.slice(0, match.index).split('\n').length;
      failures.push(`${path.relative(docsRoot, markdownFile)}:${line} -> ${match[1]}`);
    } else if (target.anchorTarget && fs.statSync(resolved).isFile() && !hasAnchor(resolved, target.anchorTarget)) {
      const line = content.slice(0, match.index).split('\n').length;
      failures.push(`${path.relative(docsRoot, markdownFile)}:${line} -> missing anchor in ${match[1]}`);
    }
  }
}

if (failures.length > 0) {
  console.error(`Broken local Markdown links (${failures.length}/${checkedLinks} checked):`);
  for (const failure of failures) console.error(`- ${failure}`);
  process.exitCode = 1;
} else {
  console.log(`Link check passed (${checkedLinks} local links checked).`);
}
