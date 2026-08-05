# 56 - TTS 音色矩阵与方言设计

> 版本：v2.0 | 创建日期：2026-07-28 | 更新：2026-08-01
> 定位：TTS 音色 7 种差异化矩阵 + 方言条件启用（仅“方言”音色可用）+ 原生方言音色 + 情感 Instruct
> 关联文档：`design/28_语音唤醒与冷场引导设计.md`（§4 音色人设，本篇为其升级替代）、`design/19_界面详细设计.md`（§8.3 TTS 语音层）、`design/55_学生端全感官交互设计方案.md`
> 状态：**已实现**（2026-08-01 核对）

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
| 7 | qiqiu | 方言 | 🗣️ | longanhuan_v3 | 方言伙伴（方言 Instruct 载体） | 女 | 20~30 | 1.05 | zh-CN-XiaoyiNeural | 保留（改造） |

### 2.3 性别默认推荐（不变）

- 男生注册 → 默认 `xiaotaiyang`
- 女生注册 → 默认 `xiaoxing`
- 未设置性别 → 默认 `xiaoxing`

### 2.4 UI 布局（设置面板 grid-cols-4，两行 4+3）

```
Row 1: [小星🌟] [波波老师👩‍🏫] [月亮🌙] [小太阳☀️]
Row 2: [大树🌳] [豆豆⚽]    [方言🗣️*]
* 方言按钮使用琥珀色渐变特殊样式，突出方言功能入口
```

---

## 三、方言条件启用设计

> ✅ **实现状态（2026-08-01 核对）**：方言仅“方言”音色（qiqiu/longanhuan_v3）选中时可用，无需单独开关。原生方言音色（粤语/东北话/陕西话）支持普通话/方言模式切换。小太阳（longanyang）支持情感 Instruct。

### 3.1 数据模型

**User 表新增字段**：

```sql
ALTER TABLE tenant_template.users ADD COLUMN dialect VARCHAR(20) DEFAULT NULL;
COMMENT ON COLUMN tenant_template.users.dialect IS '学生方言偏好（管理端配置，可为空）';
```

**dialect 枚举值定义**（与 CosyVoice v3 系列 Instruct 对应）：

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
│ 方言仅“方言”音色（qiqiu）选中时可用，无需单独开关      │
│                                                             │
│ 选中“方言”音色后直接展开方言选择（radio group）：          │
│   ○ 广东话★  ○ 东北话★  ● 四川话(默认)  ○ 河南话       │
│   ○ 山东话  ○ 湖南话  ○ 陕西话★  ○ 安徽话            │
│   ★ = 拥有原生方言音色，可切换普通话/方言模式          │
│                                                             │
│ 原生方言音色模式切换（仅★方言显示）：                      │
│   [普通话] [方言音色]  默认=普通话                        │
│   普通话模式 → qiqiu + Instruct("请用XX话表达。")         │
│   方言音色模式 → 原生方言音色（无需 instruction）          │
│                                                             │
│ 默认选中 = student.dialect（管理端配置的值）               │
│ 当前选择即时生效（本次会话内）                             │
└─────────────────────────────────────────────────────────────┘
```

**关键规则**：
1. 方言功能仅“方言”音色（qiqiu）选中时自动启用，其他音色时方言区域隐藏
2. 无独立方言开关（选择“方言”音色 = 开启方言）
3. `student.dialect` 仅决定**默认选中项**，不限制可选范围（可选全部 8 种）
4. 原生方言音色（粤语/东北话/陕西话）显示普通话/方言模式切换，默认普通话
5. 前端存储：`localStorage` 存 personaId + dialect + languageMode

### 3.3 原生方言音色映射

| 方言 | 原生音色（女） | 原生音色（男） | 说明 |
|------|--------------|--------------|------|
| 粤语 | longjiayi_v3（知性粤语女） | longanyue_v3（欢脱粤语男） | 性别匹配 |
| 东北话 | — | longlaotie_v3（东北直率男） | 仅男声 |
| 陕西话 | — | longshange_v3（原味陕北男） | 仅男声 |

### 3.4 情感 Instruct（小太阳 longanyang）

小太阳音色支持情感 Instruct，格式严格遵循官方文档：
```
"你正在进行闲聊互动，你说话的情感是<情感值>。"
```
支持的情感值：neutral、happy、sad、angry、fearful、surprised、disgusted

### 3.5 三级降级策略

```
Level 1: CosyVoice {persona_voice} + Instruct("请用{dialect}表达。")
    ↓ 失败
Level 2: edge-tts 方言音色（仅东北/陕西有对应）
    ↓ 失败或无对应
Level 3: 普通话兜底（CosyVoice 无 Instruct / edge-tts 默认音色）
    + 前端 toast：“当前网络环境暂不支持方言朗读，已切换为普通话”
```

### 3.6 TTS 接口扩展

**请求体新增可选字段**：

```json
{
  "text": "你好呀",
  "persona": "qiqiu",
  "emotion": "happy",
  "speed": 1.0,
  "dialect": "cantonese",
  "language_mode": "dialect"
}
```

**后端处理逻辑**：
- `dialect` 为空 → 正常合成（普通话）
- `dialect` 非空 + `language_mode=mandarin` → qiqiu + Instruct("请用XX话表达。")
- `dialect` 非空 + `language_mode=dialect` → 原生方言音色（无需 instruction）
- `persona=xiaotaiyang` + 无方言 → 情感 Instruct("你正在进行闲聊互动，你说话的情感是XX。")

---

## 四、涉及文件与变更清单

| 层 | 文件 | 变更内容 |
|----|------|---------|
| DB | Flyway 迁移脚本 | users 表新增 dialect 列 |
| Domain | `User.java` | 新增 dialect 字段 + getter/setter |
| TTS 微服务 | `backend/tts-service/app.py` | VOICE_PERSONAS 扩展 7 音色 + dialect 参数 + Instruct 逻辑 |
| Java 后端 | `TtsService.java` | synthesize 方法透传 dialect |
| Java 后端 | `TtsController.java` | 请求体接收 dialect |
| 前端 Hook | `useVoicePersona.ts` | 7 音色配置 + 方言独立维度（无 dialectCapable） |
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

1. 设置面板显示 7 个音色卡片（4+3 布局），“方言”按钮琥珀色特殊样式
2. 男生默认小太阳、女生默认小星（不变）
3. 方言仅“方言”音色选中时可见，无独立开关
4. 原生方言（粤语/东北话/陕西话）显示普通话/方言音色模式切换，默认普通话
5. 小太阳音色自动使用情感 Instruct（根据孩子情绪）
6. CosyVoice 不可用时降级 edge-tts，方言不可用时降级普通话 + toast
7. 全部新增逻辑有对应单元测试（覆盖率 ≥ 80%）
