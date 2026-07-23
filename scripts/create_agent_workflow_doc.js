const { Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
        HeadingLevel, AlignmentType, BorderStyle, WidthType, ShadingType,
        LevelFormat, PageBreak } = require('docx');
const fs = require('fs');

const OUTPUT_PATH = '/Users/minjianq/Documents/AI-Counseling-System/PRD/子主题/13_Agent工作流.docx';

// 边框样式
const border = { style: BorderStyle.SINGLE, size: 1, color: "CCCCCC" };
const borders = { top: border, bottom: border, left: border, right: border };

// 标题样式
const h1 = { heading: HeadingLevel.HEADING_1, children: [new TextRun("一、Agent架构概览")] };
const h2 = { heading: HeadingLevel.HEADING_2, children: [new TextRun("二、核心Agent详细设计")] };
const h2Sub = (text) => ({ heading: HeadingLevel.HEADING_2, children: [new TextRun(text)] });
const h3 = (text) => ({ heading: HeadingLevel.HEADING_3, children: [new TextRun(text)] });

// 段落
const p = (text, bold = false) => new Paragraph({
  children: [new TextRun({ text, bold })]
});

const pRun = (runs) => new Paragraph({ children: runs });

// 无序列表项
const bullet = (text, level = 0) => new Paragraph({
  numbering: { reference: "bullets", level },
  children: [new TextRun(text)]
});

// 有序列表项
const numbered = (text, level = 0) => new Paragraph({
  numbering: { reference: "numbers", level },
  children: [new TextRun(text)]
});

// 创建表格
const createTable = (headers, rows, widths) => {
  const headerCells = headers.map((h, i) => new TableCell({
    borders,
    width: { size: widths[i], type: WidthType.DXA },
    shading: { fill: "D5E8F0", type: ShadingType.CLEAR },
    margins: { top: 80, bottom: 80, left: 120, right: 120 },
    children: [new Paragraph({ children: [new TextRun({ text: h, bold: true })] })]
  }));

  const dataRows = rows.map(row => new TableRow({
    children: row.map((cell, i) => new TableCell({
      borders,
      width: { size: widths[i], type: WidthType.DXA },
      margins: { top: 80, bottom: 80, left: 120, right: 120 },
      children: [new Paragraph({ children: [new TextRun(cell)] })]
    }))
  }));

  return new Table({
    width: { size: widths.reduce((a, b) => a + b, 0), type: WidthType.DXA },
    columnWidths: widths,
    rows: [new TableRow({ children: headerCells }), ...dataRows]
  });
};

const doc = new Document({
  numbering: {
    config: [
      {
        reference: "bullets",
        levels: [{
          level: 0,
          format: LevelFormat.BULLET,
          text: "•",
          alignment: AlignmentType.LEFT,
          style: { paragraph: { indent: { left: 720, hanging: 360 } } }
        }, {
          level: 1,
          format: LevelFormat.BULLET,
          text: "◦",
          alignment: AlignmentType.LEFT,
          style: { paragraph: { indent: { left: 1080, hanging: 360 } } }
        }]
      },
      {
        reference: "numbers",
        levels: [{
          level: 0,
          format: LevelFormat.DECIMAL,
          text: "%1.",
          alignment: AlignmentType.LEFT,
          style: { paragraph: { indent: { left: 720, hanging: 360 } } }
        }]
      }
    ]
  },
  styles: {
    default: {
      document: {
        run: { font: "Arial", size: 24 }
      }
    },
    paragraphStyles: [
      {
        id: "Heading1",
        name: "Heading 1",
        basedOn: "Normal",
        next: "Normal",
        quickFormat: true,
        run: { size: 36, bold: true, font: "Arial", color: "2E5A8A" },
        paragraph: { spacing: { before: 360, after: 240 }, outlineLevel: 0 }
      },
      {
        id: "Heading2",
        name: "Heading 2",
        basedOn: "Normal",
        next: "Normal",
        quickFormat: true,
        run: { size: 30, bold: true, font: "Arial", color: "2E5A8A" },
        paragraph: { spacing: { before: 300, after: 180 }, outlineLevel: 1 }
      },
      {
        id: "Heading3",
        name: "Heading 3",
        basedOn: "Normal",
        next: "Normal",
        quickFormat: true,
        run: { size: 26, bold: true, font: "Arial", color: "2E5A8A" },
        paragraph: { spacing: { before: 240, after: 120 }, outlineLevel: 2 }
      }
    ]
  },
  sections: [{
    properties: {
      page: {
        size: { width: 11906, height: 16838 },
        margin: { top: 1440, right: 1440, bottom: 1440, left: 1440 }
      }
    },
    children: [
      // 标题
      new Paragraph({
        heading: HeadingLevel.HEADING_1,
        children: [new TextRun({ text: "Agent工作流详细设计文档", bold: true, size: 44, font: "Arial" })]
      }),
      new Paragraph({ children: [new TextRun("AI心理陪伴系统 v1.0")] }),
      new Paragraph({ children: [new TextRun(" ")] }),

      // ==================== 一、Agent架构概览 ====================
      new Paragraph(h1),
      new Paragraph(h3("1.1 Agent定义与角色")),
      p("Agent是AI心理陪伴系统中的核心智能单元，每个Agent承担特定职责，通过协作完成复杂的心智支持任务。系统包含7类核心Agent，分别负责安全监护、情绪识别、认知行为干预、对话交互、风险升级、报告生成和记忆管理。"),

      new Paragraph(h3("1.2 Agent间关系图")),
      p("Agent关系采用星型拓扑结构，Conversation Agent作为核心入口，其他Agent围绕其协同工作："),
      bullet("Conversation Agent：用户交互入口，负责接收用户输入、生成回复"),
      bullet("Safety Agent：安全守护者，检测有害内容，触发风险评估"),
      bullet("Emotion Agent：情绪识别器，分析用户情绪状态和强度"),
      bullet("CBT Agent：认知行为治疗师，执行结构化干预"),
      bullet("Escalation Agent：升级管理者，处理高风险情况"),
      bullet("Report Agent：报告生成器，汇总对话内容生成分析报告"),
      bullet("Memory Agent：记忆管理者，维护用户历史信息和会话上下文"),
      new Paragraph({ children: [new TextRun(" ")] }),

      new Paragraph(h3("1.3 消息传递机制")),
      p("Agent间通过结构化消息进行通信，消息格式定义如下："),
      createTable(
        ["字段", "类型", "说明"],
        [
          ["source_agent", "string", "消息发送方Agent名称"],
          ["target_agent", "string", "消息接收方Agent名称（*表示广播）"],
          ["message_type", "enum", "请求/响应/事件/错误"],
          ["payload", "object", "消息内容体"],
          ["timestamp", "datetime", "消息时间戳"],
          ["conversation_id", "string", "所属会话ID"],
          ["priority", "enum", "普通/高优先级/紧急"]
        ],
        [2800, 1800, 4760]
      ),
      new Paragraph({ children: [new TextRun(" ")] }),

      // ==================== 二、核心Agent详细设计 ====================
      new Paragraph({ children: [new PageBreak()] }),
      new Paragraph(h2),

      // 2.1 Safety Agent
      new Paragraph(h2Sub("2.1 Safety Agent（安全监护Agent）")),
      new Paragraph(h3("职责范围")),
      bullet("内容安全检测：识别自杀倾向、暴力倾向、虐待儿童、色情内容等"),
      bullet("风险等级评估：0-5级风险判定"),
      bullet("安全干预触发：必要时中断对话或限制回答"),
      bullet("儿童保护合规：确保符合儿童在线保护法规"),

      new Paragraph(h3("输入/输出定义")),
      createTable(
        ["类型", "内容"],
        [
          ["输入", "用户消息文本、会话上下文、历史对话"],
          ["输出", "安全检测结果、风险等级、干预建议"]
        ],
        [3000, 6360]
      ),
      new Paragraph({ children: [new TextRun(" ")] }),

      new Paragraph(h3("决策逻辑")),
      p("Safety Agent采用多层级决策树：", true),
      numbered("内容过滤层：正则匹配 + 关键词库过滤已知风险内容"),
      numbered("语义分析层：LLM判断内容的潜在风险意图"),
      numbered("风险聚合层：综合当前输入和历史上下文计算风险分"),
      numbered("干预决策层：根据风险等级决定干预方式"),

      new Paragraph(h3("Prompt模板")),
      new Paragraph({ children: [new TextRun({ text: "【系统提示】", bold: true, color: "2E5A8A" })] }),
      p("你是一名专业的儿童心理安全检测专家。你的职责是分析用户输入内容，检测是否存在以下风险："),
      bullet("自杀/自残倾向（谈论死亡、绝望感、无价值感）"),
      bullet("暴力倾向（攻击他人、破坏物品的想法）"),
      bullet("虐待儿童（身体虐待、性虐待、忽视）"),
      bullet("色情内容"),
      bullet("其他可能危害儿童安全的内容"),
      p("请以JSON格式返回检测结果，包含：is_safe(布尔值)、risk_level(0-5整数)、risk_type(风险类型数组)、concerns(关注点描述数组)、recommended_action(建议操作)。"),
      new Paragraph({ children: [new TextRun(" ")] }),

      // 2.2 Emotion Agent
      new Paragraph(h2Sub("2.2 Emotion Agent（情绪识别Agent）")),
      new Paragraph(h3("职责范围")),
      bullet("情绪分类识别：将用户情绪归类到预设类别"),
      bullet("情绪强度计算：量化情绪的强烈程度"),
      bullet("情绪趋势追踪：监控情绪随时间的变化"),
      bullet("情绪触发因素识别：识别导致情绪变化的关键事件"),

      new Paragraph(h3("情绪分类体系")),
      createTable(
        ["情绪类别", "子类别", "关键词示例"],
        [
          ["喜悦", "开心、兴奋、满足、乐观", "开心、快乐、棒、太好了"],
          ["悲伤", "沮丧、失望、孤独、抑郁", "难过、伤心、失落、孤独"],
          ["愤怒", "烦躁、挫折、敌意、报复", "生气、气愤、讨厌、恨"],
          ["恐惧", "焦虑、担忧、害怕、恐慌", "害怕、担心、紧张、恐怖"],
          ["惊讶", "震惊、意外、困惑", "震惊、没想到、奇怪"],
          ["厌恶", "反感、蔑视、羞耻", "恶心、讨厌、讨厌自己"],
          ["平静", "放松、满足、中性", "还好、一般、平静"]
        ],
        [2000, 3000, 4360]
      ),
      new Paragraph({ children: [new TextRun(" ")] }),

      new Paragraph(h3("情绪强度计算")),
      p("情绪强度采用0-100分量表计算：", true),
      bullet("基础强度：由情绪类别和表达强度词决定（0-60分）"),
      bullet("上下文加成：根据对话历史和事件严重程度调整（0-25分）"),
      bullet("持续时间因子：情绪持续时间越长，强度可能累积（0-15分）"),
      new Paragraph({ children: [new TextRun(" ")] }),

      new Paragraph(h3("输出格式定义")),
      p("Emotion Agent输出JSON格式："),
      p("{ emotion_category, emotion_subcategory, intensity_score, intensity_label(轻度/中度/强度/极度), triggers, trends, confidence }"),

      // 2.3 CBT Agent
      new Paragraph({ children: [new PageBreak()] }),
      new Paragraph(h2Sub("2.3 CBT Agent（认知行为治疗Agent）")),
      new Paragraph(h3("职责范围")),
      bullet("认知重构：帮助用户识别和改变负面思维模式"),
      bullet("行为激活：引导用户参与积极活动"),
      bullet("情绪调节：教授情绪管理技巧"),
      bullet("问题解决训练：指导用户面对和解决问题"),

      new Paragraph(h3("工作流程")),
      numbered("建立关系：表达共情，建立信任"),
      numbered("问题探索：了解用户面临的具体问题"),
      numbered("认知评估：识别自动思维和认知扭曲"),
      numbered("认知重构：挑战负面思维，建立替代思维"),
      numbered("行为实验：设计并执行行为改变计划"),
      numbered("总结反馈：回顾进展，制定后续计划"),

      new Paragraph(h3("状态机设计")),
      createTable(
        ["状态", "描述", "触发条件", "转移动作"],
        [
          ["IDLE", "等待任务", "无输入", "接收用户输入"],
          ["ENGAGING", "建立关系", "新对话开始", "确认用户问题"],
          ["ASSESSING", "问题评估", "了解问题后", "收集更多信息"],
          ["INTERVENING", "干预执行", "评估完成", "应用CBT技术"],
          ["MONITORING", "进度监控", "干预中", "评估效果"],
          ["CLOSING", "结束会话", "目标达成或退出", "总结反馈"],
          ["ESCALATING", "升级处理", "检测到高风险", "通知Escalation Agent"]
        ],
        [1500, 2500, 2500, 2860]
      ),
      new Paragraph({ children: [new TextRun(" ")] }),

      new Paragraph(h3("Prompt模板")),
      new Paragraph({ children: [new TextRun({ text: "【系统提示】", bold: true, color: "2E5A8A" })] }),
      p("你是一名专业的儿童认知行为治疗师(CBT)。你的服务对象是儿童和青少年，你需要："),
      bullet("使用简单易懂的语言，适应儿童的认知水平"),
      bullet("通过游戏、比喻、故事等有趣的方式进行干预"),
      bullet("保持温暖、支持的态度，建立安全的治疗环境"),
      bullet("遵循CBT治疗框架：识别思维、挑战思维、建立新思维"),
      bullet("在适当时候使用放松技巧和正念练习"),
      p("当前对话状态：[STATE]，请根据状态执行相应的治疗动作。"),

      // 2.4 Conversation Agent
      new Paragraph(h2Sub("2.4 Conversation Agent（对话交互Agent）")),
      new Paragraph(h3("职责范围")),
      bullet("用户输入处理：接收和解析用户消息"),
      bullet("回复生成：生成符合儿童特点的友好回复"),
      bullet("对话管理：维护对话流程和话题"),
      bullet("多模态输出：支持文字、表情、动作等多样化表达"),

      new Paragraph(h3("儿童语言适配")),
      bullet("词汇简化：使用简单、日常的词汇"),
      bullet("句子简短：保持句子长度适中，便于理解"),
      bullet("正向表达：多用积极、鼓励性语言"),
      bullet("情感确认：认可和反映用户的情绪体验"),
      bullet("互动性：增加提问和互动，保持儿童参与度"),

      new Paragraph(h3("对话策略")),
      createTable(
        ["策略", "适用场景", "示例"],
        [
          ["共情回应", "用户表达情绪", "\"我能感觉到你现在很难过\""],
          ["好奇询问", "需要更多信息", "\"能告诉我发生了什么吗？\""],
          ["正向反馈", "用户分享正面信息", "\"你做得真好！\""],
          ["温和引导", "需要转移话题", "\"我们来聊聊别的吧\""],
          ["明确直接", "提供指导建议", "\"下次可以试试这样做\""]
        ],
        [2000, 3000, 4360]
      ),
      new Paragraph({ children: [new TextRun(" ")] }),

      new Paragraph(h3("Prompt模板")),
      new Paragraph({ children: [new TextRun({ text: "【系统提示】", bold: true, color: "2E5A8A" })] }),
      p("你是一个友善的AI心理陪伴助手，名为\"心灵伙伴\"。你的服务对象是儿童和青少年。"),
      bullet("保持温暖、友好、耐心、支持的语气"),
      bullet("使用儿童友好的语言，避免复杂术语"),
      bullet("适当使用表情符号增加亲和力"),
      bullet("回复简洁明了，每次聚焦一个要点"),
      bullet("鼓励用户表达，但不要过度询问"),
      bullet("Never提供专业医疗诊断或药物治疗建议"),

      // 2.5 Escalation Agent
      new Paragraph({ children: [new PageBreak()] }),
      new Paragraph(h2Sub("2.5 Escalation Agent（升级管理Agent）")),
      new Paragraph(h3("职责范围")),
      bullet("风险监测：接收Safety Agent的风险警报"),
      bullet("升级评估：判断是否需要人工介入"),
      bullet("流程协调：协调升级流程的执行"),
      bullet("通知管理：向老师/家长发送通知"),
      bullet("记录归档：维护升级事件记录"),

      new Paragraph(h3("触发条件")),
      createTable(
        ["风险等级", "触发条件", "升级目标"],
        [
          ["L4", "自杀/自残意念明确表达", "立即通知老师+建议专业咨询"],
          ["L4", "暴力行为倾向", "立即通知老师+视情况报警"],
          ["L5", "正在发生的危险行为", "立即通知老师+可能需要紧急服务"],
          ["L5", "严重虐待儿童怀疑", "通知儿童保护机构"]
        ],
        [1500, 4000, 3860]
      ),
      new Paragraph({ children: [new TextRun(" ")] }),

      new Paragraph(h3("升级流程")),
      numbered("接收风险警报和相关信息"),
      numbered("验证风险等级的准确性"),
      numbered("根据风险等级启动相应通知流程"),
      numbered("发送紧急通知（短信/APP推送/电话）"),
      numbered("持续监控对话状态"),
      numbered("记录升级事件详情"),
      numbered("在风险解除后发送解除通知"),

      new Paragraph(h3("报告生成")),
      p("升级事件报告包含以下内容："),
      bullet("事件基本信息（时间、会话ID、用户信息）"),
      bullet("风险评估详情（风险类型、等级、依据）"),
      bullet("对话摘要（相关消息记录）"),
      bullet("采取的行动（通知发送、响应情况）"),
      bullet("后续建议"),

      // 2.6 Report Agent
      new Paragraph(h2Sub("2.6 Report Agent（报告生成Agent）")),
      new Paragraph(h3("职责范围")),
      bullet("会话摘要生成：为每次会话生成结构化摘要"),
      bullet("周期报告生成：生成周报/月报/学期报告"),
      bullet("情绪趋势分析：分析用户情绪变化趋势"),
      bullet("干预效果评估：评估CBT干预的效果"),

      new Paragraph(h3("摘要生成规则")),
      bullet("长度限制：单次会话摘要不超过500字"),
      bullet("关键信息保留：必须包含情绪状态、主要话题、干预要点"),
      bullet("隐私保护：删除可识别个人身份的信息"),
      bullet("格式化输出：使用结构化格式便于阅读"),

      new Paragraph(h3("隐私保护原则")),
      bullet("数据最小化：仅收集必要信息"),
      bullet("匿名化处理：报告中的用户信息必须脱敏"),
      bullet("访问控制：报告仅授权人员可访问"),
      bullet("加密存储：敏感数据必须加密保存"),
      bullet("保留期限：超过保留期限的数据必须删除"),

      new Paragraph(h3("输出格式")),
      p("会话摘要JSON格式："),
      p("{ session_id, user_id(脱敏), date, duration, emotion_summary, topic_summary, interventions_applied, outcomes, follow_up_recommendations }"),

      // 2.7 Memory Agent
      new Paragraph({ children: [new PageBreak()] }),
      new Paragraph(h2Sub("2.7 Memory Agent（记忆管理Agent）")),
      new Paragraph(h3("职责范围")),
      bullet("短期记忆管理：维护当前会话的上下文"),
      bullet("长期记忆管理：存储用户的历史信息"),
      bullet("记忆检索：根据需要检索相关信息"),
      bullet("记忆整合：将新信息整合到用户档案"),

      new Paragraph(h3("记忆分层")),
      createTable(
        ["层级", "内容", "容量", "保留时间"],
        [
          ["工作记忆", "当前对话上下文、当前话题状态", "50条消息", "会话期间"],
          ["情景记忆", "历史会话摘要、关键事件", "最近100次会话", "6个月"],
          ["语义记忆", "用户画像、偏好设置、已知问题", "无限制", "长期"]
        ],
        [2000, 3000, 2000, 2360]
      ),
      new Paragraph({ children: [new TextRun(" ")] }),

      new Paragraph(h3("存储策略")),
      bullet("向量嵌入：对记忆内容进行语义向量化，便于检索"),
      bullet("结构化存储：关键信息使用结构化格式存储"),
      bullet("时间衰减：长时间未访问的记忆逐步降低权重"),
      bullet("重要性标记：重要事件（如升级）永久保留"),

      new Paragraph(h3("检索机制")),
      numbered("语义检索：根据语义相似度查找相关记忆"),
      numbered("时间范围检索：查找特定时间段内的记忆"),
      numbered("类型检索：按记忆类型（情绪事件、干预记录等）筛选"),
      numbered("相关性排序：综合多维度因素排序检索结果"),

      // ==================== 三、工作流编排 ====================
      new Paragraph({ children: [new PageBreak()] }),
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("三、工作流编排")] }),

      new Paragraph(h2Sub("3.1 正常对话流程")),
      p("标准对话流程包括以下阶段：", true),
      createTable(
        ["阶段", "执行Agent", "输入", "输出"],
        [
          ["用户输入", "Conversation Agent", "用户消息", "结构化用户意图"],
          ["Safety检测", "Safety Agent", "用户消息+上下文", "安全检测结果"],
          ["Emotion识别", "Emotion Agent", "用户消息+上下文", "情绪分析结果"],
          ["流程路由", "Orchestrator", "Safety+Emotion结果", "目标Agent和策略"],
          ["CBT干预", "CBT Agent", "用户输入+记忆", "干预响应"],
          ["Response生成", "Conversation Agent", "CBT输出+上下文", "最终回复"],
          ["Output审查", "Safety Agent", "生成回复", "安全性确认"],
          ["返回用户", "Conversation Agent", "审查通过", "最终输出"]
        ],
        [2000, 2500, 2500, 2360]
      ),
      new Paragraph({ children: [new TextRun(" ")] }),

      new Paragraph(h2Sub("3.2 风险检测流程")),
      p("当Safety Agent检测到风险时，触发风险检测流程：", true),
      numbered("Safety触发：Safety Agent检测到L3及以上风险"),
      numbered("风险评估：Escalation Agent进行详细风险评估"),
      numbered("等级判定：确认风险等级（L3/L4/L5）"),
      numbered("L4/L5升级：启动紧急升级流程，通知相关人员"),
      numbered("人工通知：发送紧急通知给老师/家长"),
      numbered("对话限制：根据风险等级限制对话内容或暂停对话"),

      new Paragraph(h2Sub("3.3 预警生成流程")),
      numbered("风险触发：检测到需要生成预警的风险条件"),
      numbered("数据聚合：收集相关对话、情绪数据、风险评估"),
      numbered("报告生成：Report Agent生成结构化预警报告"),
      numbered("通知发送：通过预设渠道发送预警通知"),
      numbered("老师处理：老师接收并处理预警"),
      numbered("记录归档：预警事件存档用于后续分析"),

      // ==================== 四、LangGraph工作流实现 ====================
      new Paragraph({ children: [new PageBreak()] }),
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("四、LangGraph工作流实现")] }),

      new Paragraph(h2Sub("4.1 节点定义")),
      p("系统定义以下LangGraph节点：", true),
      createTable(
        ["节点名称", "Agent绑定", "功能描述"],
        [
          ["node_safety_check", "Safety Agent", "内容安全检测"],
          ["node_emotion_recognize", "Emotion Agent", "情绪识别分析"],
          ["node_cbt_intervene", "CBT Agent", "认知行为干预"],
          ["node_conversation", "Conversation Agent", "对话生成"],
          ["node_escalate", "Escalation Agent", "风险升级处理"],
          ["node_report", "Report Agent", "报告生成"],
          ["node_memory", "Memory Agent", "记忆存取"]
        ],
        [3000, 2500, 3860]
      ),
      new Paragraph({ children: [new TextRun(" ")] }),

      new Paragraph(h2Sub("4.2 边定义")),
      p("节点间的边定义及其条件：", true),
      bullet("START -> node_safety_check：接收用户输入"),
      bullet("node_safety_check -> node_emotion_recognize：Safety通过"),
      bullet("node_safety_check -> node_escalate：Safety未通过（L3+）"),
      bullet("node_emotion_recognize -> node_cbt_intervene：正常流程"),
      bullet("node_emotion_recognize -> node_conversation：直接回复场景"),
      bullet("node_cbt_intervene -> node_conversation：生成最终回复"),
      bullet("node_conversation -> END：输出结果"),

      new Paragraph(h2Sub("4.3 条件路由")),
      p("关键条件路由逻辑：", true),
      bullet("Safety路由：safe -> emotion_flow, unsafe -> escalate_flow"),
      bullet("风险等级路由：L3 -> 警告+限制, L4 -> 升级+通知, L5 -> 紧急升级"),
      bullet("情绪路由：high_intensity -> cbt_flow, low_intensity -> supportive_flow"),
      bullet("会话结束路由：normal_close -> report, urgent_close -> escalate"),

      new Paragraph(h2Sub("4.4 状态管理")),
      p("LangGraph状态定义：", true),
      p("{ conversation_id, messages, user_profile, safety_result, emotion_result, risk_level, current_agent, intervention_history, memory_context, should_escalate, escalation_reason }"),

      // ==================== 五、对话状态管理 ====================
      new Paragraph({ children: [new PageBreak()] }),
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("五、对话状态管理")] }),

      new Paragraph(h2Sub("5.1 会话上下文")),
      bullet("会话ID：唯一标识每次对话"),
      bullet("用户标识：脱敏后的用户ID"),
      bullet("时间戳：对话开始时间、最后活跃时间"),
      bullet("消息历史：完整的对话记录"),
      bullet("当前状态：对话进行到的阶段"),

      new Paragraph(h2Sub("5.2 流程阶段状态")),
      createTable(
        ["阶段", "状态值", "状态描述"],
        [
          ["INIT", "0", "对话初始化"],
          ["RECEIVING", "1", "接收用户输入"],
          ["PROCESSING", "2", "处理分析中"],
          ["RESPONDING", "3", "生成回复中"],
          ["MONITORING", "4", "监控风险中"],
          ["ESCALATING", "5", "升级处理中"],
          ["CLOSING", "6", "对话结束"],
          ["PAUSED", "7", "对话暂停"]
        ],
        [2000, 1500, 5860]
      ),
      new Paragraph({ children: [new TextRun(" ")] }),

      new Paragraph(h2Sub("5.3 历史记忆")),
      bullet("短期记忆：当前会话的完整上下文"),
      bullet("中期记忆：用户的历史会话摘要"),
      bullet("长期记忆：用户画像、偏好设置、已知风险因素"),

      // ==================== 六、错误处理与降级 ====================
      new Paragraph({ children: [new PageBreak()] }),
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("六、错误处理与降级")] }),

      new Paragraph(h2Sub("6.1 Agent失败降级策略")),
      createTable(
        ["失败Agent", "降级策略", "备选方案"],
        [
          ["Safety Agent", "使用规则引擎替代", "基于关键词的简单过滤"],
          ["Emotion Agent", "返回中性情绪", "使用默认情绪分类"],
          ["CBT Agent", "使用通用响应", "提供标准支持性回复"],
          ["Memory Agent", "使用缓存数据", "降级到无记忆模式"],
          ["Escalation Agent", "触发最高级别升级", "自动通知所有紧急联系人"]
        ],
        [2500, 3000, 3860]
      ),
      new Paragraph({ children: [new TextRun(" ")] }),

      new Paragraph(h2Sub("6.2 LLM调用失败处理")),
      numbered("首次失败：等待1秒后重试"),
      numbered("第二次失败：等待3秒后重试，使用缓存结果"),
      numbered("第三次失败：触发降级策略，使用规则引擎替代"),
      numbered("持续失败：记录错误日志，通知运维人员"),

      new Paragraph(h2Sub("6.3 超时处理")),
      bullet("Safety检查超时：30秒，触发降级策略"),
      bullet("Emotion识别超时：10秒，返回默认情绪"),
      bullet("CBT干预超时：60秒，返回通用支持回复"),
      bullet("整体对话超时：120秒，结束当前交互"),

      // ==================== 七、性能优化 ====================
      new Paragraph({ children: [new PageBreak()] }),
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("七、性能优化")] }),

      new Paragraph(h2Sub("7.1 并发处理")),
      bullet("独立Agent并行：Safety和Emotion分析可并行执行"),
      bullet("请求队列：使用消息队列处理高并发请求"),
      bullet("连接池：复用LLM API连接，减少建立连接开销"),
      bullet("负载均衡：分发请求到多个Agent实例"),

      new Paragraph(h2Sub("7.2 缓存策略")),
      createTable(
        ["缓存类型", "缓存内容", "TTL", "更新策略"],
        [
          ["用户画像", "用户基本信息、偏好", "24小时", "主动失效"],
          ["情绪上下文", "最近情绪分析结果", "5分钟", "时间过期"],
          ["Safety结果", "相同内容的检测结果", "1小时", "内容变化失效"],
          ["对话摘要", "会话摘要", "会话结束", "主动失效"]
        ],
        [2000, 3000, 1500, 2860]
      ),
      new Paragraph({ children: [new TextRun(" ")] }),

      new Paragraph(h2Sub("7.3 异步处理")),
      bullet("通知发送：升级通知异步发送，不阻塞主流程"),
      bullet("报告生成：会话结束后异步生成报告"),
      bullet("日志记录：关键操作异步写入日志系统"),
      bullet("监控指标：性能指标异步上报"),

      // ==================== 八、监控与日志 ====================
      new Paragraph({ children: [new PageBreak()] }),
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("八、监控与日志")] }),

      new Paragraph(h2Sub("8.1 Agent调用日志")),
      p("日志记录内容包括：", true),
      bullet("请求元数据：会话ID、时间戳、Agent名称"),
      bullet("输入数据：用户输入（脱敏后）"),
      bullet("处理结果：Agent输出结果"),
      bullet("性能数据：处理耗时、Token消耗"),
      bullet("错误信息：失败时的错误详情"),

      new Paragraph(h2Sub("8.2 决策追踪")),
      bullet("Safety决策链：记录每次Safety判断的依据和结果"),
      bullet("情绪识别置信度：记录情绪分类的置信度"),
      bullet("CBT干预路径：记录应用的CBT技术和效果评估"),
      bullet("升级决策过程：记录升级判断的完整推理过程"),

      new Paragraph(h2Sub("8.3 性能指标")),
      createTable(
        ["指标类别", "具体指标", "告警阈值"],
        [
          ["响应时间", "P50/P95/P99延迟", ">2s/>5s/>10s"],
          ["可用性", "Agent成功率", "<99%"],
          ["风险检测", "漏检率", ">0.1%"],
          ["资源消耗", "Token消耗速率", ">预设上限"]
        ],
        [2000, 3500, 3860]
      ),

      // 文档结束
      new Paragraph({ children: [new TextRun(" ")] }),
      new Paragraph({ children: [new TextRun({ text: "文档结束", italics: true, color: "888888" })] })
    ]
  }]
});

Packer.toBuffer(doc).then(buffer => {
  fs.writeFileSync(OUTPUT_PATH, buffer);
  console.log('Document created successfully:', OUTPUT_PATH);
}).catch(err => {
  console.error('Error creating document:', err);
});
