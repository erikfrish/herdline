"""Owned staging fixtures; no OpenCode runtime, HTTP server or provider."""
import base64
import hashlib
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from opencode_media import MAX_BYTES, MAX_FILES, MediaRefusal, prepare


class MediaTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.home = Path(self.temp.name)
        self.root = self.home / '.cache' / 'herdline' / 'attachments'
        self.root.mkdir(parents=True, mode=0o700)
        self.bound = {'node':'qa-node', 'session':'qa', 'boot':'boot-1', 'pane':'w1:p1',
                      'instance':{'pane_id':'w1:p1', 'terminal_id':'t1', 'pid':42,
                                  'agent':'opencode', 'source':'herdr:opencode', 'kind':'id', 'path':'ses_a'}}
        self.addCleanup(self.temp.cleanup)

    def staged(self, name, data, kind='file', group='ABCDEFGHIJ'):
        folder = self.root / group
        folder.mkdir(mode=0o700, exist_ok=True)
        target = folder / name
        target.write_bytes(data)
        target.chmod(0o600)
        return {**self.bound, 'path':str(target), 'name':name, 'kind':kind,
                'size':len(data), 'sha256':hashlib.sha256(data).hexdigest()}

    def run_prepare(self, records):
        return prepare(records, self.bound, str(self.home))

    def refuses(self, records, code):
        with self.assertRaises(MediaRefusal) as failure:
            self.run_prepare(records)
        self.assertEqual(failure.exception.code, code)

    def test_image_and_binary_file_arrive_byte_exact_without_reopening_staging(self):
        png = b'\x89PNG\r\n\x1a\nfixture-pixels'
        binary = b'\0\xff\x01\x80'
        records = [self.staged('p.png', png, 'image'), self.staged('data.bin', binary)]
        output = self.run_prepare(records)
        self.assertEqual([p['name'] for p in output], ['p.png', 'data.bin'])
        self.assertEqual(output[0]['uri'].split(',',1)[0], 'data:image/png;base64')
        self.assertEqual(output[1]['uri'].split(',',1)[0], 'data:application/octet-stream;base64')
        for part, expected in zip(output, (png, binary)):
            payload = part['uri'].split(',',1)[1]
            self.assertEqual(base64.b64decode(payload, validate=True), expected)
            self.assertEqual(base64.b64encode(expected).decode(), payload)
        Path(records[0]['path']).write_bytes(b'replaced after conversion')
        self.assertEqual(base64.b64decode(output[0]['uri'].split(',',1)[1]), png)

    def test_bound_target_and_upload_hash_are_required_for_every_file(self):
        record = self.staged('a.txt', b'original')
        foreign = {**record, 'pane':'w1:p2'}
        self.refuses([foreign], 'attachment_target')
        Path(record['path']).write_bytes(b'different')
        self.refuses([record], 'attachment_missing')
        record = self.staged('b.png', b'not an image', 'image')
        self.refuses([record], 'unsupported_type')

    def test_links_hardlinks_permissions_and_urls_cannot_authorize_other_bytes(self):
        record = self.staged('a.txt', b'safe')
        path = Path(record['path'])
        path.chmod(0o644)
        self.refuses([record], 'attachment_target')
        path.chmod(0o600)
        os.link(path, self.home / 'hardlink')
        self.refuses([record], 'attachment_target')
        (self.home / 'hardlink').unlink()
        outside = self.home / 'outside.txt'
        outside.write_bytes(b'safe')
        path.unlink()
        path.symlink_to(outside)
        self.refuses([record], 'attachment_target')
        self.refuses([{**record, 'path':'https://example.invalid/private'}], 'attachment_target')

    def test_count_and_individual_limits_preserve_multi_file_capacity(self):
        record = self.staged('a.bin', b'abcd')
        self.refuses([record] * (MAX_FILES + 1), 'too_large')
        with patch('opencode_media.MAX_BYTES', 3):
            self.refuses([record], 'too_large')
        other = self.staged('b.bin', b'efgh', group='KLMNOPQRST')
        with patch('opencode_media.MAX_BYTES', 4):
            output = self.run_prepare([record, other])
        self.assertEqual([base64.b64decode(p['uri'].split(',',1)[1]) for p in output], [b'abcd', b'efgh'])

    def test_staging_ancestor_link_is_refused_and_empty_attachments_need_no_store(self):
        self.assertEqual(prepare([], self.bound, str(self.home / 'missing')), [])
        record = self.staged('a.bin', b'safe')
        moved = self.home / 'other-staging'
        self.root.rename(moved)
        self.root.symlink_to(moved, target_is_directory=True)
        self.refuses([record], 'attachment_target')


if __name__ == '__main__':
    unittest.main()
