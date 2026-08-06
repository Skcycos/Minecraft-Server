# -*- coding: utf-8 -*-
"""扫描所有 mod jar：解析 mods.toml 依赖 + 判断端侧（客户端/服务器端/双端），生成 CSV"""
import zipfile, re, os, csv, sys, json

SERVER_MODS_DIR = 'mods'
CLIENT_MODS_DIR = 'automodpack/host-modpack/main/mods'
OUT_CSV = 'mod依赖分析.csv'

def read_toml_text(z):
    for t in ('META-INF/neoforge.mods.toml', 'META-INF/mods.toml'):
        if t in z.namelist():
            return z.read(t).decode('utf-8', errors='replace')
    return None

def parse_mods_toml(text):
    """解析 mods.toml：返回 (mod 信息 dict, 依赖列表 list)"""
    mods_info = {}
    deps = []
    try:
        import tomllib
        data = tomllib.loads(text)
        for m in data.get('mods', []):
            mods_info = {
                'modId': m.get('modId', ''),
                'displayName': m.get('displayName', ''),
                'version': m.get('version', ''),
                'displayTest': m.get('displayTest', ''),
            }
            if mods_info['modId']:
                break
        for dep_key, dep_list in data.get('dependencies', {}).items():
            if not isinstance(dep_list, list):
                continue
            for d in dep_list:
                if not isinstance(d, dict):
                    continue
                deps.append({
                    'modId': d.get('modId', dep_key),
                    'type': str(d.get('type', d.get('mandatory', 'required'))).lower(),
                    'side': d.get('side', 'BOTH'),
                    'versionRange': d.get('versionRange', ''),
                })
    except Exception:
        # 回退：正则提取
        mm = re.search(r'\[\[mods\]\]\s*(.*?)(?=\n\[\[|\Z)', text, re.S)
        if mm:
            block = mm.group(1)
            mid = re.search(r'^\s*modId\s*=\s*"([^"]+)"', block, re.M)
            dn = re.search(r'^\s*displayName\s*=\s*"([^"]*)"', block, re.M)
            v = re.search(r'^\s*version\s*=\s*"([^"]*)"', block, re.M)
            dt = re.search(r'^\s*displayTest\s*=\s*"([^"]*)"', block, re.M)
            mods_info = {
                'modId': mid.group(1) if mid else '',
                'displayName': dn.group(1) if dn else '',
                'version': v.group(1) if v else '',
                'displayTest': dt.group(1) if dt else '',
            }
        for block in re.finditer(r'\[\[dependencies\.[^\]]+\]\]\s*(.*?)(?=\n\[\[|\n\[|\Z)', text, re.S):
            b = block.group(1)
            mid = re.search(r'^\s*modId\s*=\s*"([^"]+)"', b, re.M)
            typ = re.search(r'^\s*(?:type|mandatory)\s*=\s*"?([A-Za-z]+)"?', b, re.M)
            side = re.search(r'^\s*side\s*=\s*"([^"]+)"', b, re.M)
            vr = re.search(r'^\s*versionRange\s*=\s*"([^"]*)"', b, re.M)
            deps.append({
                'modId': mid.group(1) if mid else '',
                'type': typ.group(1).lower() if typ else 'required',
                'side': side.group(1) if side else 'BOTH',
                'versionRange': vr.group(1) if vr else '',
            })
    return mods_info, deps

def has_client_ref(jar_path):
    """解压 class 文件检查是否引用 net/minecraft/client（含 jarjar 嵌套 jar 递归）"""
    try:
        def scan(zf):
            for n in zf.namelist():
                if n.endswith('.class'):
                    data = zf.read(n)
                    if b'net/minecraft/client' in data or b'net.minecraft.client' in data:
                        return True
                elif n.endswith('.jar') and not n.startswith('META-INF/'):
                    # jarjar 嵌套 jar，递归
                    try:
                        inner = zipfile.ZipFile(__import__('io').BytesIO(zf.read(n)))
                        if scan(inner):
                            return True
                    except Exception:
                        pass
            return False
        with zipfile.ZipFile(jar_path) as z:
            return scan(z)
    except Exception:
        return None

def find_toml_text(z):
    """在 jar（含嵌套 jar）里找 mods.toml 文本"""
    for t in ('META-INF/neoforge.mods.toml', 'META-INF/mods.toml'):
        if t in z.namelist():
            return z.read(t).decode('utf-8', errors='replace')
    for n in z.namelist():
        if n.endswith('.jar') and not n.startswith('META-INF/'):
            try:
                inner = zipfile.ZipFile(__import__('io').BytesIO(z.read(n)))
                txt = find_toml_text(inner)
                if txt:
                    return txt
            except Exception:
                pass
    return None

def classify(filename, label, client_ref, distributed, server_has_same):
    """按 Automodpack 实际分发行为判定端侧
    - 服务器 mods 目录 + 清单分发 -> 双端
    - 服务器 mods 目录 + 未分发 -> 服务器端
    - 客户端目录且服务器无同名 -> 客户端
    """
    if label == '客户端目录' and not server_has_same:
        return '客户端', '仅客户端目录(main/mods)'
    if distributed:
        return '双端', '服务器+客户端均加载(Automodpack分发)'
    return '服务器端', '仅服务器加载(被autoExclude排除)'

def fmt_deps(deps, types):
    out = []
    for d in deps:
        if d['type'] in types:
            s = d['modId']
            if d['versionRange']:
                s += f"@{d['versionRange']}"
            out.append(s)
    return '; '.join(out)

def main():
    rows = []
    all_jars = []
    for d, label in ((SERVER_MODS_DIR, '服务器mods'), (CLIENT_MODS_DIR, '客户端目录')):
        if os.path.isdir(d):
            for n in sorted(os.listdir(d)):
                if n.endswith('.jar') or n.endswith('.jar.disabled'):
                    all_jars.append((os.path.join(d, n), label))

    # 读取 automodpack 分发清单（客户端实际会下载的 mod 列表）
    distributed = set()
    try:
        with open('automodpack/host-modpack/automodpack-content.json', encoding='utf-8') as f:
            content = json.load(f)
        for item in content.get('list', []):
            if item.get('type') == 'mod' and item.get('file', '').startswith('/mods/'):
                distributed.add(os.path.basename(item['file']))
    except Exception as e:
        print(f'警告: 读取 automodpack 清单失败: {e}')

    # 服务器 mods 目录全部文件名（判断同名）
    server_names = set()
    for d in (SERVER_MODS_DIR,):
        if os.path.isdir(d):
            for n in os.listdir(d):
                b = n[:-9] if n.endswith('.disabled') else n
                server_names.add(b)

    print(f"共发现 {len(all_jars)} 个 jar\n")
    for path, label in all_jars:
        fn = os.path.basename(path)
        disabled = fn.endswith('.disabled')
        base_fn = fn[:-9] if disabled else fn
        try:
            with zipfile.ZipFile(path) as z:
                text = find_toml_text(z)
                if text is None:
                    no_meta_side, no_meta_reason = classify(base_fn, label, None, base_fn in distributed, base_fn in server_names)
                    rows.append([label, base_fn, '', '', '', no_meta_side, no_meta_reason + '；无mods.toml元数据', '?', '', '', '', '文件已禁用' if disabled else ''])
                    print(f"[无mods.toml] {fn} -> {no_meta_side}")
                    continue
                mods_info, deps = parse_mods_toml(text)
        except Exception as e:
            rows.append([label, base_fn, '', '', '', '解析失败', str(e)[:60], '?', '', '', '', '文件已禁用' if disabled else ''])
            print(f"[解析失败] {fn}: {e}")
            continue

        client_ref = has_client_ref(path)
        side, reason = classify(base_fn, label, client_ref, base_fn in distributed, base_fn in server_names)
        # 特例：automodpack 是分发器本体，客户端必须安装（requireAutoModpackOnClient）
        if mods_info.get('modId') == 'automodpack':
            side, reason = '双端', '分发器本体(requireAutoModpackOnClient)，客户端必装'
        req = fmt_deps(deps, ('required', 'mandatory', 'true'))
        opt = fmt_deps(deps, ('optional',))
        inc = fmt_deps(deps, ('incompatible', 'disabled'))
        note = []
        if disabled:
            note.append('文件已禁用(.disabled)未加载')
        if client_ref is None:
            note.append('无法读取文件')
        note = '; '.join(note)
        rows.append([
            label, base_fn, mods_info.get('modId', ''), mods_info.get('displayName', ''),
            mods_info.get('version', ''), side, reason,
            '是' if client_ref else ('否' if client_ref is not None else '?'),
            req, opt, inc, note
        ])
        flag = '' if side in ('双端', '客户端', '服务器端') else '  <<< 需人工确认'
        print(f"[{side:4s}] {fn}{flag}")

    # 统计
    print("\n===== 端侧统计 =====")
    from collections import Counter
    c = Counter(r[5] for r in rows)
    for k, v in c.most_common():
        print(f"  {k}: {v}")

    # 写 CSV（utf-8-sig 便于 Excel 打开）
    with open(OUT_CSV, 'w', encoding='utf-8-sig', newline='') as f:
        w = csv.writer(f)
        w.writerow(['来源', '文件名', 'modId', '显示名', '版本', '端侧', '判断依据', '引用client类',
                    '必选依赖', '可选依赖', '不兼容', '备注'])
        w.writerows(rows)
    print(f"\n已生成: {os.path.abspath(OUT_CSV)} ({len(rows)} 行)")

if __name__ == '__main__':
    main()
