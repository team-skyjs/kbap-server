import tempfile
import unittest
from pathlib import Path
from baseline import run, Snapshot, RESERVE, CAP, summarize

class BaselineTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.rows = [dict(key=str(i), truth='MENU', xs_menu='1', s22_menu='0', imagePath='test') for i in range(2)]

    def test_ambiguous_charge_is_never_repeated(self):
        calls = []
        def lost(row):
            calls.append(row['key'])
            raise RuntimeError('lost response')
        with self.assertRaises(RuntimeError):
            run(self.rows, self.root, lost, lambda r: None)
        with self.assertRaises(RuntimeError):
            run(self.rows, self.root, lost, lambda r: None)
        self.assertEqual(calls, ['0'])

    def test_success_is_skipped_and_ceiling_is_reserved_before_next_call(self):
        calls = []
        def ok(row):
            calls.append(row['key'])
            return Snapshot('OTHER', 2000, 500, 'gpt-5.6-luna', 7)
        run(self.rows[:1], self.root, ok, lambda r: None)
        run(self.rows, self.root, ok, lambda r: None, cap=RESERVE)
        self.assertEqual(calls, ['0'])
        run(self.rows, self.root, ok, lambda r: None)
        self.assertEqual(calls, ['0', '1'])
        summary = summarize(self.rows, self.root)
        self.assertIn('2/2', summary)
        self.assertIn('판정 보류', summary)

    def test_invalid_usage_halts_before_next_call(self):
        def bad(row):
            return Snapshot('MENU', 0, 0, 'gpt-5.6-luna', 10)
        with self.assertRaises(ValueError):
            run(self.rows, self.root, bad, lambda r: None)
        self.assertFalse((self.root/'1.pending').exists())

if __name__ == '__main__':
    unittest.main()
