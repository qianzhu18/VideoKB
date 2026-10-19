import json
import unittest
from pathlib import Path
from evidence_metrics import score_case, summarize, validate_golden

class EvidenceMetricsTest(unittest.TestCase):
    def setUp(self):
        self.case = {'id':'multi', 'split':'dev', 'critical':True, 'requiredEvidence':[
            {'alternatives':[{'mediaKey':'a','startMs':0,'endMs':1000,'quote':'原文甲'}]},
            {'alternatives':[{'mediaKey':'b','startMs':2000,'endMs':3000,'quote':'原文乙'}]}]}
        self.a = {'mediaId':1,'startMs':0,'endMs':1000,'transcript':'原文甲'}
        self.b = {'mediaId':2,'startMs':2000,'endMs':3000,'transcript':'原文乙'}
        self.keys = {'1':'a','2':'b'}

    def test_a_single_expected_source_cannot_pass_a_composite_question(self):
        row = score_case(self.case, [self.a], self.keys, 8)
        self.assertEqual(row['covered'], 1)
        self.assertFalse(row['allEvidence'])
        self.assertEqual(summarize([row])['criticalAllEvidenceRate'], 0)

    def test_wrong_time_or_summary_only_cannot_pass(self):
        wrong = {**self.b, 'startMs':0}
        summary = {**self.b, 'transcript':'', 'summary':'原文乙'}
        for hit in (wrong, summary):
            self.assertFalse(score_case(self.case,[self.a,hit],self.keys,8)['allEvidence'])

    def test_original_windows_mapped_by_a_retrieval_block_can_pass(self):
        block = {'mediaId':2,'startMs':0,'endMs':9999,'evidence':[self.b]}
        self.assertTrue(score_case(self.case,[self.a,block],self.keys,8)['allEvidence'])

    def test_duplicates_do_not_inflate_coverage(self):
        self.assertEqual(score_case(self.case,[self.a,self.a],self.keys,8)['covered'],1)

    def test_wrong_source_with_same_quote_and_time_cannot_pass(self):
        self.assertEqual(score_case(self.case,[{**self.a,'mediaId':3}],self.keys,8)['covered'],0)

    def test_committed_annotations_match_immutable_original_corpus(self):
        root = Path(__file__).resolve().parent.parent
        golden = json.loads((root/'eval/evidence-golden-v1.json').read_text())
        self.assertEqual(len(validate_golden(golden,root)['videos']),13)

class PercentileTest(unittest.TestCase):
    def test_nearest_rank_preserves_tail_in_small_quality_sets(self):
        from evidence_benchmark import percentile
        self.assertEqual(14, percentile(list(range(1,15)), .95))
        self.assertEqual(95, percentile(list(range(1,101)), .95))

if __name__ == '__main__': unittest.main()
