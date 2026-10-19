"""Strict original-evidence metrics shared by API, offline, and release evaluation."""
import hashlib
import json
import re
import unicodedata
from pathlib import Path

def normalize(text):
    return ''.join(c for c in unicodedata.normalize('NFKC', text or '').casefold()
                   if not c.isspace() and not unicodedata.category(c).startswith('P'))

def validate_golden(golden, root):
    path = Path(root) / golden['corpus']['path']
    if hashlib.sha256(path.read_bytes()).hexdigest() != golden['corpus']['sha256']:
        raise ValueError('Corpus changed; create a new benchmark version')
    corpus = json.loads(path.read_text())
    videos = {v['mediaKey']: v for v in corpus['videos']}
    seen = set()
    for case in golden['cases']:
        if case['id'] in seen or case['split'] not in ('dev', 'holdout'):
            raise ValueError('Duplicate case or invalid split')
        seen.add(case['id'])
        requirements = case['requiredEvidence']
        if bool(case.get('expectRefusal')) == bool(requirements):
            raise ValueError('Answerable/refusal label disagrees with evidence requirements')
        for requirement in requirements:
            if not requirement['alternatives']:
                raise ValueError('Evidence requirement must have at least one valid anchor')
            for anchor in requirement['alternatives']:
                windows = videos[anchor['mediaKey']]['segments']
                original = next((s for s in windows if s['startMs'] == anchor['startMs']
                                 and s['endMs'] == anchor['endMs']), None)
                raw = normalize((original or {}).get('text', '') + '\n' + '\n'.join((original or {}).get('ocr', [])))
                if not original or not normalize(anchor['quote']) or normalize(anchor['quote']) not in raw:
                    raise ValueError('Annotation does not map to original text/time: ' + case['id'])
    return corpus

def raw_evidence(hit):
    # New retrieval blocks carry their original windows. Legacy hits are individual windows.
    evidence = hit.get('evidence')
    return evidence if evidence is not None else [hit]

def supports(hit, anchor, media_keys):
    if media_keys.get(str(hit.get('mediaId')), hit.get('mediaKey')) != anchor['mediaKey']:
        return False
    for raw in raw_evidence(hit):
        if raw.get('startMs') != anchor['startMs'] or raw.get('endMs') != anchor['endMs']:
            continue
        text = normalize((raw.get('transcript') or '') + '\n' + (raw.get('ocrText') or ''))
        # Derived summaries never qualify as original evidence.
        if normalize(anchor['quote']) in text:
            return True
    return False

def score_case(case, hits, media_keys, k):
    candidates = hits[:k]
    requirements = case['requiredEvidence']
    ranks = []
    for requirement in requirements:
        ranks.append(next((rank for rank, hit in enumerate(candidates, 1)
                           if any(supports(hit, anchor, media_keys) for anchor in requirement['alternatives'])), None))
    required_sources = {a['mediaKey'] for r in requirements for a in r['alternatives']}
    found_sources = {media_keys.get(str(h.get('mediaId')), h.get('mediaKey')) for h in candidates}
    covered = sum(rank is not None for rank in ranks)
    return {'id': case['id'], 'split': case['split'], 'k': k,
            'required': len(requirements), 'covered': covered, 'ranks': ranks,
            'allEvidence': bool(requirements) and covered == len(requirements),
            'sourceCoverage': len(required_sources & found_sources) / len(required_sources) if required_sources else None,
            'refusalExpected': bool(case.get('expectRefusal')),
            'emptyRetrieval': not candidates,
            'critical': bool(case.get('critical'))}

def summarize(rows):
    answerable = [r for r in rows if r['required']]
    required = sum(r['required'] for r in answerable)
    critical = [r for r in answerable if r['critical']]
    refusals = [r for r in rows if r['refusalExpected']]
    return {'cases': len(rows), 'answerable': len(answerable),
            'requiredEvidenceRecall': sum(r['covered'] for r in answerable) / required if required else None,
            'allEvidenceRate': sum(r['allEvidence'] for r in answerable) / len(answerable) if answerable else None,
            'criticalAllEvidenceRate': sum(r['allEvidence'] for r in critical) / len(critical) if critical else None,
            'emptyRetrievalForRefusalRate': sum(r['emptyRetrieval'] for r in refusals) / len(refusals) if refusals else None}
