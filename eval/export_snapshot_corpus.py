#!/usr/bin/env python3
"""Freeze the raw 13-course evidence revision; never overwrite an existing corpus."""
import difflib
import hashlib
import json
from pathlib import Path
from prepare_snapshot_benchmark import sql

ROOT = Path(__file__).resolve().parent.parent

def main():
    old_path = ROOT / 'evaluation/seeds/bilibili-mianshi-v1.json'
    old = json.loads(old_path.read_text())
    rows = [json.loads(x) for x in sql("""
      SELECT JSON_OBJECT('title',s.title,'versionId',v.id)
      FROM knowledge_sources s JOIN knowledge_source_versions v
      ON s.id=v.source_id AND s.current_version=v.version_no
      WHERE s.space_id=14 AND s.status='READY' ORDER BY s.id
    """).splitlines()]
    assert len(rows) == 13
    corpus = {'schemaVersion': 1, 'provenance':
        'Exact raw ASR/OCR exported from an independent stopped-service copy of the existing 13-course index on 2026-10-08. Original archival corpus unchanged; no ASR/OCR/embedding rerun.',
        'videos': []}
    differences = []
    for row in rows:
        original = old['videos'][int(row['title'].split('-')[0]) - 1]
        segments = [json.loads(x) for x in sql(f"""
          SELECT JSON_OBJECT('startMs',start_ms,'endMs',end_ms,'text',transcript,'ocrText',ocr_text)
          FROM knowledge_segments WHERE version_id={row['versionId']} ORDER BY start_ms
        """).splitlines()]
        assert len(segments) == len(original['segments'])
        for actual, archived in zip(segments, original['segments']):
            assert (actual['startMs'], actual['endMs']) == (archived['startMs'], archived['endMs'])
            ocr = actual.pop('ocrText')
            actual['ocr'] = [ocr] if ocr else []
            if actual['text'] != archived['text']:
                differences.append({'mediaKey': original['mediaKey'], 'startMs': actual['startMs'],
                    'similarity': round(difflib.SequenceMatcher(None, actual['text'], archived['text']).ratio(), 5)})
        corpus['videos'].append({'mediaKey': original['mediaKey'], 'title': row['title'], 'segments': segments})
    path = ROOT / 'evaluation/seeds/course-index-snapshot-v1.json'
    data = json.dumps(corpus, ensure_ascii=False, indent=2) + '\n'
    if path.exists() and path.read_text() != data:
        raise SystemExit('Frozen corpus already exists with different content; use a new version')
    path.write_text(data)
    (ROOT / 'eval/reports/corpus-revision-20261008.json').write_text(json.dumps({
        'archivedSha256': hashlib.sha256(old_path.read_bytes()).hexdigest(),
        'snapshotSha256': hashlib.sha256(path.read_bytes()).hexdigest(),
        'videos': 13, 'windows': 674, 'changedTextWindows': len(differences),
        'timeBoundariesChanged': False, 'differences': differences}, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'videos': 13, 'windows': 674, 'changedTextWindows': len(differences)}))

if __name__ == '__main__':
    main()
