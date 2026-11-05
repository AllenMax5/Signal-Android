import base64
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

import e2ee_group_decode as decoder

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / 'lib/libsignal-service/src/main/protowire'
PROTOC = shutil.which('protoc')


def blob(raw, **extra):
    return {'rawBase64': base64.b64encode(raw).decode(), **extra}


class WireTests(unittest.TestCase):
    def test_signed_actions_extracted_from_patch_and_message(self):
        actions = b'\x10\x01'  # revision 1
        change = b'\x0a\x02' + actions
        patch = b'\x0a\x04' + change
        self.assertEqual(decoder.nested_actions(patch, 'signal.GroupChangeResponse'), [actions])
        group = b'\x1a\x04' + change
        data = b'\x7a\x06' + group
        content = b'\x0a\x08' + data
        self.assertEqual(decoder.nested_actions(content, 'signalservice.Content'), [actions])

    def test_malformed_wire_is_not_success(self):
        for raw in (b'\x0a\xff', b'\x0a\x04x', b'\x00', b'\x80' * 10 + b'\x01'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                decoder.length_fields(raw, 1)

    def test_exact_endpoint_and_status(self):
        self.assertEqual(decoder.select_type('PATCH', '/v2/groups/', 'responseBody', 200), 'signal.GroupChangeResponse')
        self.assertEqual(decoder.select_type('GET', '/v2/groups/logs/1?maxSupportedChangeEpoch=7', 'responseBody', 206), 'signal.GroupChanges')
        self.assertIsNone(decoder.select_type('PUT', '/v2/groups/', 'responseBody', 403))
        self.assertIsNone(decoder.select_type('GET', '/v2/groups/token', 'responseBody', 200))

    def test_incomplete_bytes_are_never_decoded(self):
        for extra in ({'complete': False}, {'truncated': True}):
            self.assertEqual(decoder.decode_body(blob(b'bad', **extra), 'signal.Group', None, SCHEMAS)['decodeStatus'], 'incomplete')


@unittest.skipUnless(PROTOC, 'protoc required for schema integration')
class SchemaTests(unittest.TestCase):
    def test_member_ciphertext_has_schema_names_and_role(self):
        raw = subprocess.check_output([PROTOC, '-I', str(SCHEMAS), '--encode=signal.Group', 'Groups.proto'],
                                      input=b'version: 1 members { userId: "ciphertext" role: ADMINISTRATOR }')
        result = decoder.decode_body(blob(raw), 'signal.Group', PROTOC, SCHEMAS)
        self.assertEqual(result['decodeStatus'], 'decoded')
        self.assertIn('userId: "ciphertext"', result['protobufText'])
        self.assertIn('ADMINISTRATOR', result['protobufText'])
        self.assertNotIn('aci:', result['protobufText'])

    def test_bad_base64_and_bad_protobuf(self):
        for body in ({'rawBase64': '**'}, blob(b'\x0a\xff')):
            self.assertEqual(decoder.decode_body(body, 'signal.Group', PROTOC, SCHEMAS)['decodeStatus'], 'decode_error')

    def test_http_call_crosses_rotation_boundary(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp)
            common = {'callId': 'c1', 'runId': 'new_run', 'device': 'accountA'}
            request = dict(common, kind='request', data={'method': 'GET', 'path': '/v2/groups/'})
            response = dict(common, kind='response', data={'status': 200, 'responseBody': blob(b'', complete=True)})
            (source / 'capture-0001.jsonl').write_text(json.dumps(request) + '\n')
            (source / 'capture-0002.jsonl').write_text(json.dumps(response) + '\n')
            subprocess.run([sys.executable, str(ROOT / 'tools/e2ee_group_decode.py'), str(source),
                            '--output', str(source / 'decoded'), '--protoc', PROTOC],
                           check=True, capture_output=True)
            manifest = json.loads((source / 'decoded/manifest.json').read_text())
            self.assertEqual(manifest['counts']['decoded'], 1)

    def test_cli_keeps_source_and_refuses_existing_output(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / 'capture.jsonl'
            output = Path(temp) / 'decoded'
            events = [
                {'kind': 'request', 'callId': 'c1', 'data': {'method': 'PUT', 'path': '/v2/groups/'}},
                {'kind': 'request_body', 'callId': 'c1', 'data': {'requestBody': blob(b'\x30\x01', complete=True)}},
                {'operation': 'group_send_path_selected', 'device': 'accountA', 'data': {'pairwiseTargets': ['B']}},
                {'operation': 'group_state_decrypted', 'data': {'protobufType': 'signal.DecryptedGroup', 'protobuf': blob(b'')}}
            ]
            original = ''.join(json.dumps(e) + '\n' for e in events) + '{partial\n'
            source.write_text(original)
            command = [sys.executable, str(ROOT / 'tools/e2ee_group_decode.py'), str(source), '--output', str(output), '--protoc', PROTOC]
            subprocess.run(command, check=True, capture_output=True)
            manifest = json.loads((output / 'manifest.json').read_text())
            self.assertEqual(manifest['counts']['decoded'], 2)
            self.assertEqual(manifest['counts']['malformed_jsonl'], 1)
            self.assertEqual(source.read_text(), original)
            before = (output / 'decoded.jsonl').read_bytes()
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
            self.assertEqual((output / 'decoded.jsonl').read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
