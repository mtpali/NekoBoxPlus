#!/usr/bin/env python3
"""Compare serialized model fields in pre-R8 class files with the actual APK DEX.

R8's mapping can omit unchanged fields. It cannot prove their preservation by
counting mapping entries, so enumerate input and output field tables instead.
"""
import json
import re
import struct
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / 'upstream/workspace/NekoBoxForAndroid/app'
MODEL = 'Lmoe/matsuri/nb4a/SingBoxOptions$'
TUN = 'Lio/nekohasekai/sagernet/AndroidTunPayload;'


def class_fields(data):
    assert data[:4] == b'\xca\xfe\xba\xbe', 'Invalid Java class'
    pos = 8

    def u2():
        nonlocal pos
        value = struct.unpack_from('>H', data, pos)[0]
        pos += 2
        return value

    size = u2()
    pool = [None] * size
    index = 1
    while index < size:
        tag = data[pos]
        pos += 1
        if tag == 1:
            length = u2()
            pool[index] = data[pos:pos + length].decode('utf-8', errors='replace')
            pos += length
        elif tag == 7:
            pool[index] = u2()
        elif tag in (8, 16, 19, 20):
            pos += 2
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            index += 1
        elif tag == 15:
            pos += 3
        else:
            raise AssertionError('Unsupported constant-pool tag: ' + str(tag))
        index += 1
    u2()  # class access
    name = 'L' + pool[pool[u2()]] + ';'
    u2()  # superclass
    interfaces = u2()
    pos += interfaces * 2
    fields = set()
    for _ in range(u2()):
        access, field_name, field_type = u2(), pool[u2()], pool[u2()]
        for _ in range(u2()):
            u2()  # attribute name
            length = struct.unpack_from('>I', data, pos)[0]
            pos += 4 + length
        if access & (0x0008 | 0x0080 | 0x1000):
            continue  # static, transient and synthetic fields are not JSON input
        if name.startswith(MODEL) and not access & 0x0001:
            continue
        fields.add((field_name, field_type))
    return name, fields


def dex_fields(data):
    assert data[:4] == b'dex\n', 'Invalid DEX'

    def u4(offset):
        return struct.unpack_from('<I', data, offset)[0]

    def uleb(offset):
        value = 0
        for shift in range(0, 35, 7):
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if not byte & 128:
                return value, offset
        raise AssertionError('Invalid DEX ULEB128')

    string_count, string_offset = u4(56), u4(60)
    strings = []
    for index in range(string_count):
        offset = u4(string_offset + index * 4)
        _, offset = uleb(offset)  # UTF-16 length
        strings.append(data[offset:data.index(b'\0', offset)].decode('utf-8', errors='replace'))
    type_count, type_offset = u4(64), u4(68)
    types = [strings[u4(type_offset + index * 4)] for index in range(type_count)]
    field_count, field_offset = u4(80), u4(84)
    fields = []
    for index in range(field_count):
        owner, kind, name = struct.unpack_from('<HHI', data, field_offset + index * 8)
        fields.append((types[owner], strings[name], types[kind]))
    result = {}
    class_count, class_offset = u4(96), u4(100)
    for index in range(class_count):
        offset = class_offset + index * 32
        name = types[u4(offset)]
        if not (name.startswith(MODEL) or name == TUN):
            continue
        declared = result.setdefault(name, set())
        offset = u4(offset + 24)
        if not offset:
            continue
        counts = []
        for _ in range(4):
            count, offset = uleb(offset)
            counts.append(count)
        for count in counts[:2]:
            field_index = 0
            for _ in range(count):
                difference, offset = uleb(offset)
                _, offset = uleb(offset)  # access flags
                field_index += difference
                owner, field_name, field_type = fields[field_index]
                assert owner == name, 'DEX field owner mismatch'
                declared.add((field_name, field_type))
    return result


def verify(apk):
    build = APP / 'build'
    expected = {}
    inputs = set(build.rglob('SingBoxOptions$*.class')) | set(build.rglob('AndroidTunPayload.class'))
    for path in sorted(inputs):
        if 'plusrelease' not in str(path).lower():
            continue
        name, fields = class_fields(path.read_bytes())
        if not (name.startswith(MODEL) or name == TUN):
            continue
        if name in expected:
            assert expected[name] == fields, 'Inconsistent pre-R8 classes: ' + name
        else:
            expected[name] = fields
    count = sum(len(fields) for fields in expected.values())
    assert count > 50 and expected.get(TUN), 'Pre-R8 serialized model classes missing'
    actual = {}
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if re.fullmatch(r'classes\d*\.dex', name):
                for owner, fields in dex_fields(archive.read(name)).items():
                    assert owner not in actual, 'Duplicate serialized model in DEX: ' + owner
                    actual[owner] = fields
    for owner, fields in expected.items():
        if not fields:
            continue
        missing = fields - actual.get(owner, set())
        assert not missing, 'Core JSON field missing or renamed: ' + owner + ' ' + repr(sorted(missing))
    report = {'schema': 1, 'comparison': 'pre-R8 class fields vs declared release DEX fields',
              'classes': len(expected), 'fields': count,
              'expected': {name: [list(item) for item in sorted(fields)]
                           for name, fields in sorted(expected.items()) if fields}}
    target = build / 'outputs/mapping/plusRelease/json-field-contract.json'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, indent=2) + '\n')
    return count
