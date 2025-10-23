#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import re

# 读取文件
with open('src/main/kotlin/cn/lcofficial/guozhan/manager/TechnologyManager.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# 批量替换所有损坏的模式
replacements = [
    ('�?', ''),
    ('科技管理�', '科技管理器'),
    ('科技状�', '科技状态'),
    ('已存�', '已存在'),
    ('已加�', '已加载'),
    ('数据库错�', '数据库错误'),
    ('完整�', '完整性'),
    ('完�', '完成'),
    ('失�', '失败'),
    ('出�', '出错'),
    ('检�', '检查'),
    ('缓�', '缓存'),
    ('任�', '任务'),
    ('集�', '集合'),
    ('表�', '表'),
    ('数�', '数据'),
    ('问�', '问题'),
    ('修�', '修复'),
    ('验�', '验证'),
    ('通�', '通过'),
    ('启�', '启用'),
    ('未启�', '未启用'),
    ('研�', '研究'),
    ('等�', '等级'),
    ('成�', '成本'),
    ('充�', '充足'),
    ('恢�', '恢复'),
    ('重�', '重建'),
    ('时�', '时间'),
    ('消�', '消息'),
    ('线�', '线程'),
    ('秒', '秒'),
    ('钻�', '钻石'),
    ('金�', '金币'),
    ('国�', '国家'),
    ('科�', '科技'),
    ('日�', '日志'),
    ('毫�', '毫秒'),
    ('间�', '间隔'),
    ('剩�', '剩余'),
    ('�', ''),
]

for old, new in replacements:
    content = content.replace(old, new)

# 写回文件
with open('src/main/kotlin/cn/lcofficial/guozhan/manager/TechnologyManager.kt', 'w', encoding='utf-8') as f:
    f.write(content)

print('TechnologyManager.kt 修复完成')

