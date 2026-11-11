#!/usr/bin/env python3
# Summarize a single CI run in the stable PR evidence comment.
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
import json
import os
from pathlib import Path

root = Path(os.environ['UI_RETURN_DIR'])
evidence = Path(os.environ['UI_EVIDENCE_DIR'])
blob = (root / 'blob-sha.txt').read_text().strip() if (root / 'blob-sha.txt').exists() else ''
jobs = json.loads((root / 'job-status.json').read_text())
reports = json.loads((root / 'bounds-report.json').read_text())
errors = json.loads((root / 'lint-errors.json').read_text())
lines = ['<!-- arena-ui-evidence -->', '## 模拟器截图自动回传', '',
         f"运行：{os.environ['GITHUB_RUN_ID']}；提交：{os.environ['UI_SOURCE_SHA']}",
         f"Git blob：`{blob or '上传失败，不可用'}`（未提交到分支，可能被清理）", '',
         ]
apk_meta_path = root / 'apk-metadata.json'
apk_blob_path = root / 'apk-blob-sha.txt'
if apk_meta_path.is_file() and apk_blob_path.is_file():
    apk = json.loads(apk_meta_path.read_text())
    apk_blob = apk_blob_path.read_text().strip()
    lines += [f"APK Git blob：`{apk_blob or '上传失败，不可用'}`（debug，未提交到分支，可能被清理）",
              f"APK 来源：`{apk['commit']}`；包名：`{apk['applicationId']}`；versionCode：`{apk['versionCode']}`；versionName：`{apk['versionName']}`",
              f"APK SHA-256：`{apk['apkSha256']}`；签名证书 SHA-256：`{apk['signerSha256']}`", '',
              '覆盖安装如遇签名或版本冲突，请停止；不要卸载或清除数据。', '']
else:
    lines += ['APK 未回传或未验证。', '']
lines += ['| Job | 状态 |', '|---|---|']
for job in jobs:
    lines.append(f"| {job['name']} | {job['status']} |")
lines += ['', '### 失败日志末尾（过滤摘要）']
failed = [j for j in jobs if j['status'] not in ('success', 'skipped')]
if not failed:
    lines.append('无失败 job。')
for job in failed:
    name = job['name']
    if name == 'lint':
        path = evidence / 'ui-lint-evidence' / 'failure-tail.txt'
    else:
        matrix = name.removeprefix('screenshots (').removesuffix(')').replace(', ', '-')
        path = evidence / ('ui-' + matrix) / 'failure-tail.txt'
    text = path.read_text(errors='replace')[:3500] if path.is_file() else '未捕获该阶段日志，请查看 Actions job。'
    lines += [f'**{name}**', '```', text.replace('```', "'''").strip(), '```']
lines += ['', '### lint errors']
lines += [f"- {x['id']}: {x['message']}" for x in errors[:30]] if errors else ['无。']
analyzed = [v for v in reports.values() if v.get('status') == 'analyzed']
small = sum(len(v['small_candidates']) for v in analyzed)
overlap = sum(len(v['overlap_candidates']) for v in analyzed)
lines += ['', '### bounds 自动检查（候选，不是验收结论）',
          f'分析 {len(analyzed)}/{len(reports)} 个界面；小于 48dp 的可点击 bounds 候选 {small} 个；同级或非祖先可点击 bounds 重叠候选 {overlap} 个。',
          'XML 无法可靠证明文字截断、TouchDelegate 实际点击范围或画布遮挡；明细在 `bounds-report.json`，需逐图复核。',
          '', '### 压缩包内路径',
          '`ui-baseline-light/`、`ui-baseline-dark/`、`ui-candidate-light/`、`ui-candidate-dark/`：各含状态 PNG、XML 和模拟器配置。',
          '`bounds-report.json`、`job-status.json`、`lint-errors.json`：自动诊断。',
          '', 'baseline 使用 0694e08（本次重设计前）；旧版强制深色，baseline light 不是浅色。candidate 按实际色面亮度验证浅/深色。',
          '这些是合成素材的模拟器截图，不代表手机 MCP 真机验收。']
(root / 'comment.md').write_text('\n'.join(lines) + '\n')
