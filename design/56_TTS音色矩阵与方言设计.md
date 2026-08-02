# 56 - TTS 音色矩阵与方言设计

> 版本：v1.0 | 创建日期：2026-07-28
> 定位：TTS 音色从 4 种扩展为 7 种差异化矩阵 + 方言条件启用（会话级可选可切换）
> 关联文档：`design/28_语音唤醒与冷场引导设计.md`（§4 音色人设，本篇为其升级替代）、`design/19_界面详细设计.md`（§8.3 TTS 语音层）、`design/55_学生端全感官交互设计方案.md`
> 状态：**设计完成，待实施**

---

## 一、背景与问题

### 1.1 现状（design/28 §4.2 的 4 音色）

| persona | 名称 | CosyVoice voice | 特质 |
|---------|------|----------------|------|
| xiaoxing | 小星 | longxing_v3 | 温婉邻家女 20~25 |
| qiqiu | 气球 | longanhuan_v3 | 欢脱元气女 20~30 |
| yueliang | 月亮 | longwan_v3 | 细腻柔声女 20~30 |
| xiaotaiyang | 小太阳 | longanyang | 阳光大男孩 20~30 |

### 1.2 问题

1. **同质化严重**：前 3 个均为 20~30 岁年轻女声，音色区分度低，用户反馈"听起来差不多"
2. **性别失衡**：3 女 1 男，男生只有"小太阳"一个选择
3. **缺乏角色层次**：没有"老师感"、"同龄伙伴感"、"长辈感"的差异化定位
4. **无方言能力**：产品面向全国小学，部分地区学生更亲切于方言交互

---

## 二、7 音色差异化矩阵

### 2.1 设计原则

- **角色层次覆盖**：姐姐 / 老师 / 故事者 / 哥哥 / 大叔 / 同龄伙伴 / 元气伙伴
- **性别均衡**：3 女 + 3 男 + 1 女童（气球定位为元气伙伴，承载方言）
- **音色辨识度**：每个音色在年龄、气质、语速上有明显差异
- **Instruct 能力保留**：longanyang（小太阳）支持情感/场景/角色 Instruct，longanhuan_v3（气球）支持方言 Instruct

### 2.2 音色矩阵表

| # | persona ID | 名称 | emoji | CosyVoice voice | 特质定位 | 性别 | 年龄 | 语速 | edge-tts 降级 | 状态 |
|---|-----------|------|-------|----------------|---------|------|------|------|--------------|------|
| 1 | xiaoxing | 小星 | 🌟 | longxing_v3 | 温婉邻家姐姐 | 女 | 20~25 | 1.0 | zh-CN-XiaoxiaoNeural | 保留 |
| 2 | bobo | 波波老师 | 👩‍🏫 | longyingling_v3 | 温和共情女老师 | 女 | 25~30 | 0.95 | zh-CN-XiaoxiaoNeural | **新增** |
| 3 | yueliang | 月亮 | 🌙 | longwan_v3 | 细腻柔声讲故事 | 女 | 20~30 | 0.92 | zh-CN-XiaohanNeural | 保留 |
| 4 | xiaotaiyang | 小太阳 | ☀️ | longanyang | 阳光大哥哥 | 男 | 20~30 | 1.05 | zh-CN-YunxiNeural | 保留 |
| 5 | dashu | 大树 | 🌳 | longanyun_v3 | 居家暖男/班主任 | 男 | 30~35 | 0.95 | zh-CN-YunyangNeural | **新增** |
| 6 | doudou | 豆豆 | ⚽ | longjielidou_v3 | 阳光顽皮同龄男孩 | 男童 | 10 | 1.05 | zh-CN-YunxiaNeural | **新增** |
| 7 | qiqiu | 气球 | 🎈 | longanhuan_v3 | 欢脱元气伙伴（方言载体） | 女 | 20~30 | 1.05 | zh-CN-XiaoyiNeural | 保留（改造） |

### 2.3 性别默认推荐（不变）

- 男生注册 → 默认 `xiaotaiyang`
- 女生注册 → 默认 `xiaoxing`
- 未设置性别 → 默认 `xiaoxing`

### 2.4 UI 布局（设置面板 grid-cols-4，两行 4+3）

```
Row 1: [小星🌟] [波波老师👩‍🏫] [月亮🌙] [小太阳☀️]
Row 2: [大树🌳] [豆豆⚽]    [气球🎈]
```

---

## 三、方言条件启用设计

### 3.1 数据模型

**User 表新增字段**：

```sql
ALTER TABLE tenant_template.users ADD COLUMN dialect VARCHAR(20) DEFAULT NULL;
COMMENT ON COLUMN tenant_template.users.dialect IS '学生方言偏好（管理端配置，可为空）';
```

**dialect 枚举值定义**（与 CosyVoice longanhuan_v3 Instruct 严格对应）：

| dialect 值 | 中文标签 | CosyVoice Instruct 指令 | edge-tts 降级音色 |
|-----------|---------|------------------------|-----------------|
| `cantonese` | 广东话 | "请用广东话表达。" | 无 → 普通话兜底 |
| `northeastern` | 东北话 | "请用东北话表达。" | zh-CN-liaoning-XiaobeiNeural |
| `sichuan` | 四川话 | "请用四川话表达。" | 无 → 普通话兜底 |
| `henan` | 河南话 | "请用河南话表达。" | 无 → 普通话兜底 |
| `shandong` | 山东话 | "请用山东话表达。" | 无 → 普通话兜底 |
| `hunan` | 湖南话 | "请用湖南话表达。" | 无 → 普通话兜底 |
| `shaanxi` | 陕西话 | "请用陕西话表达。" | zh-CN-shaanxi-XiaoniNeural |
| `anhui` | 安徽话 | "请用安徽话表达。" | 无 → 普通话兜底 |

**配置入口**：教师管理后台 → 学生管理 → CSV 导入 / 单个编辑时可填（非必填）。

### 3.2 方言交互逻辑

```
┌─────────────────────────────────────────────────────────────┐
│ 前置条件：选中"气球"音色 AND student.dialect != null          │
│                                                             │
│ [toggle] 用家乡话聊天                                        │
│                                                             │
│ 开启后展开方言选择（radio group）：                            │
│   ○ 广东话  ○ 东北话  ● 四川话(默认)  ○ 河南话               │
│   ○ 山东话  ○ 湖南话  ○ 陕西话  ○ 安徽话                    │
│                                                             │
│ 默认选中 = student.dialect（管理端配置的值）                   │
│ 学生可自由切换为其他方言                                      │
│ 当前选择即时生效（本次会话内）                                 │
│ 新会话重置：toggle OFF + 方言回归 student.dialect 默认值       │
└─────────────────────────────────────────────────────────────┘
```

**关键规则**：
1. 方言 toggle 默认 **OFF**（标准普通话），需学生主动开启
2. 方言开关 **不持久化**（localStorage 不存方言状态），每次新会话重置
3. `student.dialect` 仅决定**默认选中项**，不限制可选范围（可选全部 8 种）
4. 仅"气球"音色支持方言（`dialect_capable=True`），切换其他音色时方言自动失效
5. 前端存储：`localStorage` 只存 `personaId`；方言状态存 React state（会话级）

### 3.3 三级降级策略

```
Level 1: CosyVoice longanhuan_v3 + Instruct("请用{dialect}表达。")
    ↓ 失败
Level 2: edge-tts 方言音色（仅东北/陕西有对应）
    ↓ 失败或无对应
Level 3: 普通话兜底（CosyVoice longanhuan_v3 无 Instruct / edge-tts zh-CN-XiaoyiNeural）
    + 前端 toast："当前网络环境暂不支持方言朗读，已切换为普通话"
```

### 3.4 TTS 接口扩展

**请求体新增可选字段**：

```json
{
  "text": "你好呀",
  "persona": "qiqiu",
  "emotion": "happy",
  "speed": 1.0,
  "dialect": "sichuan"    // 新增，可选，仅 dialect_capable 音色生效
}
```

**后端处理逻辑**：
- `dialect` 为空 → 正常合成（普通话）
- `dialect` 非空 + 音色 `dialect_capable=True` → 附加 Instruct 指令
- `dialect` 非空 + 音色不支持 → 忽略 dialect，正常合成

---

## 四、涉及文件与变更清单

| 层 | 文件 | 变更内容 |
|----|------|---------|
| DB | Flyway 迁移脚本 | users 表新增 dialect 列 |
| Domain | `User.java` | 新增 dialect 字段 + getter/setter |
| TTS 微服务 | `backend/tts-service/app.py` | VOICE_PERSONAS 扩展 7 音色 + dialect 参数 + Instruct 逻辑 |
| Java 后端 | `TtsService.java` | synthesize 方法透传 dialect |
| Java 后端 | `TtsController.java` | 请求体接收 dialect |
| 前端 Hook | `useVoicePersona.ts` | 7 音色配置 + dialectCapable 标记 |
| 前端 Hook | `useTtsPlayer.ts` | 请求体携带 dialect |
| 前端 UI | `SettingsPanel.tsx` | grid-cols-4 布局 + 方言 toggle + radio group |
| 管理后台 | 学生编辑/CSV 导入 | dialect 字段配置入口 |
| 测试 | 各层对应 test 文件 | TDD 先行 |

---

## 五、与 design/28 的关系

本篇为 design/28 §4（音色人设）的**升级替代**：
- design/28 §4.2 的 4 音色表 → 由本篇 §2.2 的 7 音色矩阵替代
- design/28 §4.3/4.4 的后端缺口修复 → 已在前期完成（xiaotaiyang 已补齐）
- design/28 其余部分（语音唤醒 §2、冷场引导 §3、三功能协同 §5）不受影响

---

## 六、验收标准

1. 设置面板显示 7 个音色卡片（4+3 布局），切换后 TTS 即时生效
2. 男生默认小太阳、女生默认小星（不变）
3. 气球音色 + student.dialect 有值时 → 显示方言 toggle
4. 方言开启后默认选中 student.dialect，可切换其他方言
5. 方言选择即时生效，新会话重置为 OFF
6. CosyVoice 不可用时降级 edge-tts，方言不可用时降级普通话 + toast
7. 全部新增逻辑有对应单元测试（覆盖率 ≥ 80%）
