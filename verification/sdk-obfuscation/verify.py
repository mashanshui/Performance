"""核对 AAR 消费者规则与最终 APK，验证 SDK 原始类和成员名称完整保留。"""

from pathlib import Path
from zipfile import ZipFile
from io import BytesIO
import struct
from collections import Counter
import argparse

# 从 class 文件独立读取声明，避免用生成规则自身作为验证依据。
def class_members(data):
    pos = 8
    def u2():
        nonlocal pos
        value = struct.unpack_from('>H', data, pos)[0]
        pos += 2
        return value
    count = u2()
    cp = [None] * count
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            size = u2()
            cp[i] = data[pos:pos+size].decode('utf-8', errors='replace')
            pos += size
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18): pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1
        elif tag in (7, 8, 16, 19, 20): pos += 2
        elif tag == 15: pos += 3
        else: raise AssertionError(('未知常量池类型', tag))
        i += 1
    pos += 6
    interface_count = u2()
    pos += 2 * interface_count
    groups = []
    for _ in range(2):
        members = Counter()
        for _ in range(u2()):
            u2()
            name = cp[u2()]
            u2()
            members[name] += 1
            for _ in range(u2()):
                u2()
                size = struct.unpack_from('>I', data, pos)[0]
                pos += 4 + size
        groups.append(members)
    return groups

# DEX 的 class_data 只包含真实声明；不能用全局方法表中的引用冒充成员仍然存在。
def dex_members(data):
    def uint(pos): return struct.unpack_from('<I', data, pos)[0]
    def uleb(pos):
        value = shift = 0
        while True:
            byte = data[pos]
            pos += 1
            value |= (byte & 127) << shift
            if byte < 128: return value, pos
            shift += 7
    strings = []
    for i in range(uint(56)):
        _, pos = uleb(uint(uint(60) + i * 4))
        end = data.index(b'\0', pos)
        strings.append(data[pos:end].decode('utf-8', errors='replace'))
    types = [strings[uint(uint(68) + i * 4)] for i in range(uint(64))]
    fields = [strings[uint(uint(84) + i * 8 + 4)] for i in range(uint(80))]
    methods = [strings[uint(uint(92) + i * 8 + 4)] for i in range(uint(88))]
    result = {}
    for i in range(uint(96)):
        row = uint(100) + i * 32
        name = types[uint(row)][1:-1].replace('/', '.')
        groups = [Counter(), Counter()]
        pos = uint(row + 24)
        if pos:
            sizes = []
            for _ in range(4):
                value, pos = uleb(pos)
                sizes.append(value)
            for group, size in enumerate(sizes):
                index = 0
                for _ in range(size):
                    delta, pos = uleb(pos)
                    index += delta
                    _, pos = uleb(pos)
                    if group >= 2: _, pos = uleb(pos)
                    groups[group // 2][(fields if group < 2 else methods)[index]] += 1
        result[name] = groups
    return result

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk', type=Path, help='待核对的最终 APK')
parser.add_argument('modules', nargs='*', help='SDK 模块后缀；不填时检查全部八个模块')
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
modules = args.modules or ['core', 'transport', 'crash', 'jank', 'metrics', 'leak', 'native-tools', 'sdk']
apk = args.apk
actual = {}
with ZipFile(apk) as z:
    for name in z.namelist():
        if name.startswith('classes') and name.endswith('.dex'):
            actual.update(dex_members(z.read(name)))
classes = fields = methods = 0
for module in modules:
    module = 'performance-' + module
    aar = root / module / 'build/outputs/aar' / (module + '-release.aar')
    with ZipFile(aar) as z, ZipFile(BytesIO(z.read('classes.jar'))) as jar:
        rules = z.read('proguard.txt').decode()
        for entry in jar.namelist():
            if not entry.endswith('.class'): continue
            name = entry[:-6].replace('/', '.')
            assert '-keep class ' + name + ' { *; }' in rules, ('AAR 缺少规则', name)
            assert name in actual, ('APK 缺少原类名', name)
            expected = class_members(jar.read(entry))
            for old, new in zip(expected, actual[name]):
                missing = old - new
                assert not missing, ('APK 成员名称或重载丢失', name, missing)
            classes += 1
            fields += sum(expected[0].values())
            methods += sum(expected[1].values())
print(apk.name, '原类名', classes, '原字段名', fields, '原方法名（含重载）', methods, '全部保留')
