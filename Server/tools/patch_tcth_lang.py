# -*- coding: utf-8 -*-
"""把 TCTH 源码的完整中文语言文件(68键)打进 jar，并补写材质包汉化。"""
import zipfile, shutil, os, json

root = os.getcwd()

# 1. 定位材质包目录（含 pack.mcmeta + assets 的顶层目录）
pack_dir = None
for d in os.listdir(root):
    p = os.path.join(root, d)
    if os.path.isdir(p) and os.path.exists(os.path.join(p, 'pack.mcmeta')) \
            and os.path.isdir(os.path.join(p, 'assets')):
        pack_dir = p
print('材质包目录:', pack_dir)
assert pack_dir, '未找到材质包目录'

# 2. 汉化源：模组源码的完整 zh_cn.json
src_lang = os.path.join(root, 'mod develop', 'tcthintegration-template-1.21.1',
                        'src', 'main', 'resources', 'assets', 'tcth', 'lang', 'zh_cn.json')
with open(src_lang, encoding='utf-8') as f:
    data = json.load(f)
print('汉化源键数:', len(data))

# 3. 写入材质包 assets/tcth/lang/zh_cn.json
out = os.path.join(pack_dir, 'assets', 'tcth', 'lang', 'zh_cn.json')
os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
print('材质包已写入:', out, os.path.getsize(out), 'bytes')

# 4. 打进 jar（替换 assets/tcth/lang/zh_cn.json）
jar = os.path.join(root, 'Server', 'mods', 'tcth-0.1.0.jar')
tmp_jar = jar + '.new'
payload = json.dumps(data, ensure_ascii=False, indent=2).encode('utf-8')
with zipfile.ZipFile(jar) as zin, zipfile.ZipFile(tmp_jar, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        raw = zin.read(item.filename)
        if item.filename == 'assets/tcth/lang/zh_cn.json':
            raw = payload
        zout.writestr(item, raw)
shutil.move(tmp_jar, jar)
print('jar 已更新，键数:', len(data))
