#!/usr/bin/env python3
"""Decode private-group Debug JSONL evidence offline. Never changes input captures.

Requires protoc (no Python third-party packages). Output contains testing secrets.
Protobuf fields are rendered using the checked-out .proto, never guessed from bytes.
"""
import argparse
import base64
from collections import Counter
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
from urllib.parse import urlsplit

SCHEMAS = ('Groups.proto', 'DecryptedGroups.proto', 'SignalService.proto')
TYPES = {
    'signal.Group', 'signal.GroupResponse', 'signal.GroupChange.Actions',
    'signal.GroupChangeResponse', 'signal.GroupChanges', 'signal.DecryptedGroup',
    'signal.DecryptedGroupChange', 'signalservice.Content',
}


def varint(raw, pos):
    value = 0
    for shift in range(0, 70, 7):
        if pos >= len(raw):
            raise ValueError('truncated varint')
        byte = raw[pos]
        pos += 1
        if shift == 63 and byte > 1:
            raise ValueError('varint overflow')
        value |= (byte & 127) << shift
        if byte < 128:
            return value, pos
    raise ValueError('varint overflow')


def length_fields(raw, field_number):
    """Extract exact length-delimited bytes by schema-known field number."""
    pos = 0
    values = []
    while pos < len(raw):
        tag, pos = varint(raw, pos)
        if tag >> 3 == 0:
            raise ValueError('invalid field number')
        wire = tag & 7
        if wire == 0:
            _, pos = varint(raw, pos)
            continue
        if wire == 2:
            size, pos = varint(raw, pos)
        elif wire in (1, 5):
            size = 8 if wire == 1 else 4
        else:
            raise ValueError('unsupported wire type')
        end = pos + size
        if end > len(raw):
            raise ValueError('truncated field')
        if wire == 2 and tag >> 3 == field_number:
            values.append(raw[pos:end])
        pos = end
    return values


def nested_actions(raw, proto_type):
    if proto_type == 'signal.GroupChangeResponse':
        changes = length_fields(raw, 1)
    elif proto_type == 'signal.GroupChanges':
        changes = [c for state in length_fields(raw, 1) for c in length_fields(state, 1)]
    elif proto_type == 'signalservice.Content':
        # Content.dataMessage(1).groupV2(15).groupChange(3) is signed GroupChange.
        changes = [c for data in length_fields(raw, 1)
                   for group in length_fields(data, 15) for c in length_fields(group, 3)]
    else:
        changes = []
    return [actions for change in changes for actions in length_fields(change, 1)]


def select_type(method, path, direction, status=None):
    path = urlsplit(path or '').path.rstrip('/')
    if not path.startswith('/v2/groups'):
        return None
    if direction == 'responseBody':
        if status not in (200, 206):
            return None  # Error text is not a group protobuf.
        if path.startswith('/v2/groups/logs/'):
            return 'signal.GroupChanges'
        if path == '/v2/groups':
            return 'signal.GroupChangeResponse' if method == 'PATCH' else 'signal.GroupResponse'
    elif path == '/v2/groups':
        return {'PUT': 'signal.Group', 'PATCH': 'signal.GroupChange.Actions'}.get(method)
    return None


def decode(protoc, schema_dir, proto_type, raw):
    if proto_type not in TYPES:
        raise ValueError('unsupported protobuf type')
    result = subprocess.run(
        [protoc, '-I', str(schema_dir), '--decode=' + proto_type, *SCHEMAS],
        input=raw, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False
    )
    if result.returncode:
        raise ValueError('protoc rejected payload: ' + result.stderr.decode(errors='replace')[:300])
    return result.stdout.decode()


def decode_body(body, proto_type, protoc, schema_dir):
    if body.get('complete') is False or body.get('truncated') is True:
        return {'decodeStatus': 'incomplete', 'protobufType': proto_type}
    if 'rawBase64' not in body:
        return {'decodeStatus': 'missing_bytes', 'protobufType': proto_type}
    try:
        raw = base64.b64decode(body['rawBase64'], validate=True)
        return {
            'decodeStatus': 'decoded', 'protobufType': proto_type,
            'byteLength': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(),
            'protobufText': decode(protoc, schema_dir, proto_type, raw),
            'nestedGroupChangeActions': [
                {'sha256': hashlib.sha256(a).hexdigest(),
                 'protobufText': decode(protoc, schema_dir, 'signal.GroupChange.Actions', a)}
                for a in nested_actions(raw, proto_type)
            ]
        }
    except (ValueError, OSError) as error:
        return {'decodeStatus': 'decode_error', 'protobufType': proto_type, 'error': str(error)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('inputs', nargs='+', type=Path, help='JSONL files or export directories')
    parser.add_argument('--output', type=Path, required=True, help='New private directory; refuses to overwrite')
    parser.add_argument('--source-root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--protoc', default=shutil.which('protoc'))
    args = parser.parse_args()
    if not args.protoc:
        parser.error('protoc is required')
    schema_dir = args.source_root / 'lib/libsignal-service/src/main/protowire'
    files = sorted({f.resolve() for p in args.inputs for f in (p.glob('*.jsonl') if p.is_dir() else [p])})
    if not files:
        parser.error('no input JSONL files')
    # Validate schemas/tooling before creating output. Empty Content is legal proto2.
    decode(args.protoc, schema_dir, 'signalservice.Content', b'')
    args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
    counts = Counter()
    operations = Counter()
    sources = []
    calls = {}  # A request/response can span rotated JSONL segments.
    with (args.output / 'decoded.jsonl').open('x') as output:
        for source in files:
            digest = hashlib.sha256()
            with source.open('rb') as handle:
                for line_no, line in enumerate(handle, 1):
                    digest.update(line)
                    try:
                        event = json.loads(line)
                    except (ValueError, UnicodeError):
                        counts['malformed_jsonl'] += 1
                        output.write(json.dumps({'sourceFile': str(source), 'sourceLine': line_no,
                                                 'decodeStatus': 'malformed_jsonl'}) + '\n')
                        continue
                    data = event.get('data', {})
                    call_id = event.get('callId')
                    call_key = (event.get('runId'), event.get('device'), call_id)
                    if event.get('kind') == 'request' and call_id:
                        calls[call_key] = data
                    request = calls.get(call_key, data)
                    typed = []
                    proto_type = data.get('protobufType')
                    if proto_type in TYPES:
                        body = data.get('protobuf', data.get('content'))
                        if isinstance(body, dict):
                            typed.append(decode_body(body, proto_type, args.protoc, schema_dir))
                    # Legacy inbound captures have Content without explicit type.
                    elif event.get('operation') == 'inbound_message_decrypted' and isinstance(data.get('content'), dict):
                        typed.append(decode_body(data['content'], 'signalservice.Content', args.protoc, schema_dir))
                    for direction in ('requestBody', 'responseBody'):
                        body = data.get(direction)
                        proto_type = select_type(request.get('method'), request.get('path'), direction, data.get('status'))
                        if proto_type and isinstance(body, dict):
                            decoded = decode_body(body, proto_type, args.protoc, schema_dir)
                            typed.append({'bodyField': direction, **decoded})
                    op = event.get('operation', '')
                    relevant = typed or op.startswith(('group_', 'profile_credential_', 'profile_commitment_', 'pairwise_content_'))
                    if relevant:
                        record = {key: event.get(key) for key in ('runId', 'device', 'stepId', 'timestampMs', 'monotonicNs', 'callId', 'kind', 'operation')}
                        record.update(sourceFile=str(source), sourceLine=line_no, data=data, decodedProtobuf=typed)
                        output.write(json.dumps(record, ensure_ascii=False) + '\n')
                        operations[f"{event.get('device')}:{op}"] += 1
                        for item in typed:
                            counts[item['decodeStatus']] += 1
            sources.append({'path': str(source), 'sha256': digest.hexdigest()})
    manifest = {
        'sources': sources, 'counts': dict(counts), 'operations': dict(operations),
        'schemas': {s: hashlib.sha256((schema_dir / s).read_bytes()).hexdigest() for s in SCHEMAS},
        'sourceRoot': str(args.source_root.resolve()),
        'protocVersion': subprocess.check_output([args.protoc, '--version'], text=True).strip(),
        'limits': ['Local verification is not observation of server internals.',
                   'Cross-layer events without a common callId require byte hashes and context; time alone is insufficient.',
                   'No event or missing summary does not prove that an operation did not occur.']
    }
    (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2, ensure_ascii=False))
    print(json.dumps({'output': str(args.output), 'sourceFiles': len(sources), 'counts': dict(counts)}))


if __name__ == '__main__':
    main()
