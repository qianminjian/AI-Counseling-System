const { Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
        HeadingLevel, AlignmentType, BorderStyle, WidthType, ShadingType,
        LevelFormat, PageBreak, Header, Footer, TableOfContents } = require('docx');
const fs = require('fs');

const OUTPUT_PATH = '/Users/minjianq/Documents/AI-Counseling-System/PRD/子主题/03_CBT对话流程树.docx';

// 边框样式
const border = { style: BorderStyle.SINGLE, size: 1, color: "CCCCCC" };
const borders = { top: border, bottom: border, left: border, right: border };

// 表格创建函数
const createTable = (headers, rows, widths) => {
  const headerCells = headers.map((h, i) => new TableCell({
    borders,
    width: { size: widths[i], type: WidthType.DXA },
    shading: { fill: "2E5A8A", type: ShadingType.CLEAR },
    margins: { top: 80, bottom: 80, left: 120, right: 120 },
    children: [new Paragraph({ children: [new TextRun({ text: h, bold: true, color: "FFFFFF" })] })]
  }));

  const dataRows = rows.map(row => new TableRow({
    children: row.map((cell, i) => new TableCell({
      borders,
      width: { size: widths[i], type: WidthType.DXA },
      margins: { top: 80, bottom: 80, left: 120, right: 120 },
      children: [new Paragraph({ children: [new TextRun(cell.toString())] })]
    }))
  }));

  return new Table({
    width: { size: widths.reduce((a, b) => a + b, 0), type: WidthType.DXA },
    columnWidths: widths,
    rows: [new TableRow({ children: headerCells }), ...dataRows]
  });
};

// 青色表头表格
const createTableCyan = (headers, rows, widths) => {
  const headerCells = headers.map((h, i) => new TableCell({
    borders,
    width: { size: widths[i], type: WidthType.DXA },
    shading: { fill: "1ABC9C", type: ShadingType.CLEAR },
    margins: { top: 80, bottom: 80, left: 120, right: 120 },
    children: [new Paragraph({ children: [new TextRun({ text: h, bold: true, color: "FFFFFF" })] })]
  }));

  const dataRows = rows.map(row => new TableRow({
    children: row.map((cell, i) => new TableCell({
      borders,
      width: { size: widths[i], type: WidthType.DXA },
      margins: { top: 80, bottom: 80, left: 120, right: 120 },
      children: [new Paragraph({ children: [new TextRun(cell.toString())] })]
    }))
  }));

  return new Table({
    width: { size: widths.reduce((a, b) => a + b, 0), type: WidthType.DXA },
    columnWidths: widths,
    rows: [new TableRow({ children: headerCells }), ...dataRows]
  });
};

// 橙色表头表格（用于警告）
const createTableOrange = (headers, rows, widths) => {
  const headerCells = headers.map((h, i) => new TableCell({
    borders,
    width: { size: widths[i], type: WidthType.DXA },
    shading: { fill: "E67E22", type: ShadingType.CLEAR },
    margins: { top: 80, bottom: 80, left: 120, right: 120 },
    children: [new Paragraph({ children: [new TextRun({ text: h, bold: true, color: "FFFFFF" })] })]
  }));

  const dataRows = rows.map(row => new TableRow({
    children: row.map((cell, i) => new TableCell({
      borders,
      width: { size: widths[i], type: WidthType.DXA },
      margins: { top: 80, bottom: 80, left: 120, right: 120 },
      children: [new Paragraph({ children: [new TextRun(cell.toString())] })]
    }))
  }));

  return new Table({
    width: { size: widths.reduce((a, b) => a + b, 0), type: WidthType.DXA },
    columnWidths: widths,
    rows: [new TableRow({ children: headerCells }), ...dataRows]
  });
};

// 红色表头表格（用于高风险）
const createTableRed = (headers, rows, widths) => {
  const headerCells = headers.map((h, i) => new TableCell({
    borders,
    width: { size: widths[i], type: WidthType.DXA },
    shading: { fill: "C0392B", type: ShadingType.CLEAR },
    margins: { top: 80, bottom: 80, left: 120, right: 120 },
    children: [new Paragraph({ children: [new TextRun({ text: h, bold: true, color: "FFFFFF" })] })]
  }));

  const dataRows = rows.map(row => new TableRow({
    children: row.map((cell, i) => new TableCell({
      borders,
      width: { size: widths[i], type: WidthType.DXA },
      margins: { top: 80, bottom: 80, left: 120, right: 120 },
      children: [new Paragraph({ children: [new TextRun(cell.toString())] })]
    }))
  }));

  return new Table({
    width: { size: widths.reduce((a, b) => a + b, 0), type: WidthType.DXA },
    columnWidths: widths,
    rows: [new TableRow({ children: headerCells }), ...dataRows]
  });
};

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

// 紫色标签样式
const tagPurple = (text) => new Paragraph({
  children: [new TextRun({ text: `【${text}】`, bold: true, color: "8E44AD" })]
});

// 绿色标签样式
const tagGreen = (text) => new Paragraph({
  children: [new TextRun({ text: `【${text}】`, bold: true, color: "27AE60" })]
});

// 蓝色标签样式
const tagBlue = (text) => new Paragraph({
  children: [new TextRun({ text: `【${text}】`, bold: true, color: "2E5A8A" })]
});

// 红色标签样式
const tagRed = (text) => new Paragraph({
  children: [new TextRun({ text: `【${text}】`, bold: true, color: "C0392B" })]
});

// 空行
const emptyLine = () => new Paragraph({ children: [new TextRun(" ")] });

// 系统提示框样式
const systemPrompt = (title, content) => {
  return [
    new Paragraph({ children: [new TextRun({ text: title, bold: true, color: "2E5A8A" })] }),
    new Paragraph({
      shading: { fill: "F8F9FA", type: ShadingType.CLEAR },
      children: [new TextRun({ text: content, font: "SimSun" })]
    })
  ];
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
        }, {
          level: 2,
          format: LevelFormat.BULLET,
          text: "□",
          alignment: AlignmentType.LEFT,
          style: { paragraph: { indent: { left: 1440, hanging: 360 } } }
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
        }, {
          level: 1,
          format: LevelFormat.DECIMAL,
          text: "%1.%2.",
          alignment: AlignmentType.LEFT,
          style: { paragraph: { indent: { left: 1080, hanging: 360 } } }
        }]
      },
      {
        reference: "letters",
        levels: [{
          level: 0,
          format: LevelFormat.LOWER_LETTER,
          text: "%1)",
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
      },
      {
        id: "Heading4",
        name: "Heading 4",
        basedOn: "Normal",
        next: "Normal",
        quickFormat: true,
        run: { size: 24, bold: true, font: "Arial", color: "34495E" },
        paragraph: { spacing: { before: 200, after: 100 }, outlineLevel: 3 }
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
    headers: {
      default: new Header({
        children: [new Paragraph({
          alignment: AlignmentType.RIGHT,
          children: [new TextRun({ text: "CBT对话流程树详细设计文档", color: "888888", size: 20 })]
        })]
      })
    },
    footers: {
      default: new Footer({
        children: [new Paragraph({
          alignment: AlignmentType.CENTER,
          children: [new TextRun({ text: "AI心理陪伴系统 v1.0 - 第 ", color: "888888", size: 20 }), new TextRun({ children: ["Page Number"], color: "888888", size: 20 }), new TextRun({ text: " 页", color: "888888", size: 20 })]
        })]
      })
    },
    children: [
      // 标题
      new Paragraph({
        heading: HeadingLevel.HEADING_1,
        alignment: AlignmentType.CENTER,
        children: [new TextRun({ text: "CBT对话流程树详细设计文档", bold: true, size: 44, font: "Arial" })]
      }),
      new Paragraph({
        alignment: AlignmentType.CENTER,
        children: [new TextRun({ text: "AI心理陪伴系统 v1.0", size: 24, color: "666666" })]
      }),
      emptyLine(),

      // 目录
      new Paragraph({
        heading: HeadingLevel.HEADING_2,
        children: [new TextRun("目录")]
      }),
      new Paragraph({ children: [new TextRun("自动生成的目录将显示在这里")] }),
      emptyLine(),

      // ==================== 第一章 总体流程架构 ====================
      new Paragraph({ children: [new PageBreak()] }),
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("一、总体流程架构")] }),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("1.1 CBT流程树设计原则")] }),
      p("认知行为治疗（CBT）是一种结构化、目标导向的心理治疗方法，其核心理念是通过改变负面思维模式来改善情绪和行为。在AI心理陪伴系统中实现CBT流程树需要遵循以下设计原则：", true),
      emptyLine(),
      createTable(
        ["设计原则", "描述", "在AI系统中的实现方式"],
        [
          ["结构化引导", "CBT治疗师按照标准化流程进行干预", "将治疗过程分解为7个明确阶段，每个阶段有明确目标和输出"],
          ["协作参与", "治疗师与来访者共同探索问题", "设计交互式对话节点，让用户参与问题解决过程"],
          ["目标导向", "每次治疗设定具体可测量目标", "在对话中嵌入目标确认和进度检查节点"],
          ["技能泛化", "将在治疗中学到的技能应用到日常生活", "设计微行动建议模块，确保行为改变可迁移到真实场景"],
          ["循证实践", "基于研究证据的治疗方法", "整合经过验证的认知重构技术和行为激活策略"]
        ],
        [2000, 3500, 3860]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("1.2 完整流程状态机设计")] }),
      p("CBT对话流程采用状态机管理，每个状态代表对话进行到的阶段：", true),
      emptyLine(),
      createTable(
        ["状态名称", "状态码", "描述", "进入条件", "退出条件"],
        [
          ["INIT", "0", "对话初始化", "用户发起对话", "完成欢迎语发送"],
          ["SAFE", "1", "建立安全感", "完成初始化", "用户表达初步信任感"],
          ["EMOTION", "2", "识别情绪", "进入安全感阶段", "识别并命名情绪"],
          ["EVENT", "3", "确认事件", "情绪识别完成", "明确核心问题事件"],
          ["THOUGHT", "4", "识别自动化想法", "事件确认完成", "识别负面自动思维"],
          ["COGNITIVE", "5", "认知修正", "识别自动思维", "完成认知重构"],
          ["ACTION", "6", "微行动建议", "认知修正完成", "确定具体行动"],
          ["CLOSE", "7", "稳定结束", "行动建议完成或达到轮次限制", "完成收尾语发送"],
          ["ESCALATE", "8", "风险升级", "检测到L3+风险", "完成升级流程"],
          ["PAUSE", "9", "对话暂停", "收到暂停信号", "收到恢复信号"]
        ],
        [1200, 1000, 2500, 2500, 3060]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("1.3 流程阶段定义与转换条件")] }),
      p("各阶段之间的转换需要满足特定条件，系统通过意图识别和槽位填充来判断是否满足转换条件：", true),
      emptyLine(),
      createTable(
        ["当前阶段", "目标阶段", "转换条件", "转换信号"],
        [
          ["INIT", "SAFE", "发送欢迎语完成", "system:welcome_sent"],
          ["SAFE", "EMOTION", "用户表达初步情绪或问题", "intent:express_emotion OR intent:share_problem"],
          ["EMOTION", "EVENT", "用户命名了具体情绪", "slot:emotion_filled"],
          ["EVENT", "THOUGHT", "用户描述了相关事件", "slot:event_filled"],
          ["THOUGHT", "COGNITIVE", "用户表达了负面想法", "intent:negative_thought"],
          ["COGNITIVE", "ACTION", "用户认可新的认知角度", "intent:cognitive_shift"],
          ["ACTION", "CLOSE", "用户确认行动计划或达到最大轮次", "intent:confirm_action OR system:max_turns"],
          ["ANY", "ESCALATE", "Safety Agent检测到L3+风险", "risk_level >= 3"],
          ["CLOSE", "PAUSE", "用户表示需要暂停", "intent:pause"]
        ],
        [1500, 1800, 3000, 3060]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("1.4 对话轮次控制策略")] }),
      p("为防止对话无限延伸并确保用户体验，系统实施以下轮次控制策略：", true),
      emptyLine(),
      createTable(
        ["控制策略", "参数设置", "触发动作", "说明"],
        [
          ["每阶段最大轮次", "3-5轮", "进入下一阶段或提示", "每个阶段允许的对话轮数上限"],
          ["单次对话最大轮次", "30轮", "执行收尾流程", "整个对话的总轮数限制"],
          ["无响应超时", "120秒", "发送关心提示", "用户超过120秒无输入"],
          ["情绪强度阈值", ">80分", "自动进入风险检测", "情绪强度超过阈值时强制检测"],
          ["自动收尾触发", "连续2次重复内容", "执行收尾流程", "检测到对话陷入循环时"]
        ],
        [2500, 2000, 2500, 2360]
      ),
      emptyLine(),

      // ==================== 第二章 基础CBT流程树详细设计 ====================
      new Paragraph({ children: [new PageBreak()] }),
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("二、基础CBT流程树详细设计（7个阶段）")] }),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("2.1 第一阶段：建立安全感（SAFE）")] }),
      p("目标：让用户感受到被理解、被接纳，建立初步的信任关系。", true),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("2.1.1 阶段目标与成功标准")] }),
      createTable(
        ["目标", "成功标准", "失败信号"],
        [
          ["建立情感连接", "用户感受到被倾听", "用户持续重复相同负面内容"],
          ["传递安全感", "用户愿意继续表达", "用户表现出防御或抵触"],
          ["初步收集信息", "了解用户大致问题方向", "用户回避问题或沉默"]
        ],
        [3000, 3500, 2860]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("2.1.2 标准话术模板")] }),
      ...systemPrompt("【欢迎语模板】", "你好呀！我是你的心灵伙伴，很高兴见到你。今天你愿意和我聊聊吗？不管你想说什么，我都会认真听的哦。"),
      emptyLine(),
      ...systemPrompt("【共情回应模板】", "我听到你说了……（复述用户内容），这让你感到……（识别情绪词），对吗？"),
      emptyLine(),
      ...systemPrompt("【确认感受模板】", "我能感觉到你现在有点……（猜测情绪），这种感觉确实不太好受。你想多说说吗？"),
      emptyLine(),
      ...systemPrompt("【安全声明模板】", "在这里你可以放心说出心里话，我会尊重你说的每一句话，除非你告诉我可以告诉别人，否则我不会告诉任何人。"),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("2.1.3 年龄段适配话术")] }),
      createTable(
        ["年龄段", "语言风格", "示例话术"],
        [
          ["6-8岁", "简单、亲切、用游戏化语言", "你好呀！我是心灵小伙伴，我们可以一起玩一个叫做\"说说心里话\"的游戏，你想玩吗？"],
          ["9-12岁", "友好、支持、适度幽默", "嗨！很高兴认识你。我是你的心灵伙伴，有什么想聊的尽管说，我会认真听的。"],
          ["13岁以上", "尊重、平等、专业", "你好，欢迎来到这里。如果你愿意，可以和我分享最近发生了什么，我会和你一起面对。"]
        ],
        [1500, 3000, 4860]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("2.2 第二阶段：识别情绪（EMOTION）")] }),
      p("目标：帮助用户准确识别和命名自己的情绪，为后续认知重构奠定基础。", true),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("2.2.1 情绪卡片设计")] }),
      p("情绪卡片是帮助儿童识别情绪的重要工具，系统提供多种情绪卡片交互方式：", true),
      emptyLine(),
      createTableCyan(
        ["卡片类型", "适用场景", "卡片内容"],
        [
          ["基础情绪卡", "初学者、情绪识别困难", "开心、难过、生气、害怕、惊讶、讨厌"],
          ["情绪强度卡", "需要区分情绪强度", "一点点、有一些、比较、非常、超级"],
          ["复合情绪卡", "高级用户、复杂情绪", "又开心又紧张、又难过又生气、既害怕又期待"],
          ["情绪原因卡", "情绪与事件关联", "因为……所以感到……"]
        ],
        [2000, 3500, 3860]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("2.2.2 选择式对话设计")] }),
      p("针对不同年龄和情绪识别能力，设计渐进式的选择对话：", true),
      emptyLine(),
      createTable(
        ["对话模式", "示例", "适用对象"],
        [
          ["是/否确认", "\"你现在感到难过吗？\"", "幼儿、情绪极度混乱时"],
          ["二选一", "\"你现在是感到难过，还是生气呢？\"", "幼儿、初学者"],
          ["多选一", "\"你感到开心、难过、还是生气？\"", "有一定情绪认知能力"],
          ["排序选择", "\"把下面的情绪按强烈程度排排队：一点点、有一些、非常\"", "需要了解情绪强度"],
          ["开放询问", "\"你现在的心情是什么样的？能告诉我吗？\"", "情绪识别能力强"]
        ],
        [2500, 4500, 2360]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("2.2.3 情绪识别话术模板")] }),
      ...systemPrompt("【情绪命名引导】", "我们每个人都会有各种心情。你现在的心情是什么样的呢？可以试着说出来，或者从这些表情里选一个你觉得最像的：）：（：（"),
      emptyLine(),
      ...systemPrompt("【情绪强度询问】", "你说感到很难过/很害怕，这种感觉是很强烈吗？还是只有一点点？"),
      emptyLine(),
      ...systemPrompt("【情绪原因关联】", "你能想起是什么事情让你有这种感受吗？有时候知道原因会帮助我们更好地理解自己的心情。"),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("2.3 第三阶段：确认事件（EVENT）")] }),
      p("目标：明确引发情绪的具体事件或情境，帮助用户将情绪与事件关联。", true),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("2.3.1 开放式提问设计")] }),
      p("开放式提问用于获取事件全貌，鼓励用户详细描述：", true),
      emptyLine(),
      createTable(
        ["问题类型", "示例问题", "获取信息"],
        [
          ["情境探索", "\"能告诉我发生了什么吗？\"", "事件基本情况"],
          ["时间线追问", "\"这件事是今天发生的，还是已经有一段时间了？\"", "事件时间跨度"],
          ["人物关系", "\"当时还有谁在场？\"", "事件涉及人物"],
          ["过程描述", "\"事情是怎么发生的呢？\"", "事件发展过程"],
          ["结果探索", "\"这件事让你觉得怎么样？\"", "事件影响"]
        ],
        [2000, 4000, 3360]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("2.3.2 引导式提问设计")] }),
      p("当用户难以主动描述时，使用引导式提问帮助回忆：", true),
      emptyLine(),
      createTable(
        ["引导类型", "示例话术", "使用时机"],
        [
          ["时间引导", "\"上周有没有发生什么让你印象深刻的事情？\"", "用户无法确定时间"],
          ["场景引导", "\"在学校有没有遇到什么不顺心的事？\"", "用户回避具体场景"],
          ["人物引导", "\"和朋友相处时，有没有让你不舒服的情况？\"", "涉及人际关系问题"],
          ["情绪回溯", "\"你说感到很沮丧，回想一下，是从什么时候开始的？\"", "需要明确时间起点"]
        ],
        [2000, 4500, 2860]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("2.3.3 事件确认话术模板")] }),
      ...systemPrompt("【开放式询问】", "你愿意和我详细说说吗？把你经历的事情慢慢告诉我，我会认真听的。"),
      emptyLine(),
      ...systemPrompt("【引导式询问】", "有时候一下子说不清楚也没关系。我可以问你几个问题吗？这样能帮你理清思路。"),
      emptyLine(),
      ...systemPrompt("【确认理解】", "让我确认一下我理解对了：你遇到的情况是……（复述用户描述），是这样的吗？"),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("2.4 第四阶段：识别自动化想法（THOUGHT）")] }),
      p("目标：帮助用户识别在事件发生时脑海中自动浮现的负面思维，这是CBT的核心环节。", true),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("2.4.1 苏格拉底式提问设计")] }),
      p("苏格拉底式提问通过一系列精心设计的问题，引导用户自己发现思维中的认知扭曲：", true),
      emptyLine(),
      createTable(
        ["提问类型", "问题示例", "目的"],
        [
          ["澄清提问", "\"你说的'没人喜欢我'，具体是指谁呢？\"", "具体化模糊的负面想法"],
          ["假设检验", "\"如果明天真的有一个人愿意和你做朋友，你会怎么想？\"", "检验想法的绝对性"],
          ["证据收集", "\"有什么证据支持这个想法？有什么证据反对它？\"", "平衡看待想法"],
          ["观点转换", "\"如果你的好朋友遇到同样的情况，你会对他说什么？\"", "从他人视角看问题"],
          ["后果探索", "\"这个想法让你有什么感受？它是怎么影响你的行为的？\"", "认识想法的影响"],
          ["重新归因", "\"除了这个原因，还有什么可能的解释吗？\"", "寻找替代解释"]
        ],
        [2500, 4000, 2860]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("2.4.2 常见认知扭曲类型识别表")] }),
      createTableOrange(
        ["认知扭曲类型", "定义", "儿童常见表达", "引导话术"],
        [
          ["非黑即白", "用极端的两极化方式看待事物", "\"要么最好，要么最差\"", "\"事情是不是只有好和坏两种可能？有没有中间地带？\""],
          ["以偏概全", "根据单一事件得出普遍结论", "\"我永远做不好任何事\"", "\"有一次没做好，能说明每次都做不好吗？\""],
          ["心理过滤", "只关注负面细节而忽略正面", "\"老师说了一句批评的话\"（忽略10句表扬）", "\"除了这句话，老师还说了什么？\""],
          ["贬低积极", "把积极体验转化消极", "\"只是运气好\"", "\"你觉得这次成功和运气有多大关系？\""],
          ["跳跃结论", "未经证实就做负面预测", "\"如果我告诉他，他一定会讨厌我\"", "\"你怎么知道他的反应？有什么依据吗？\""],
          ["放大夸大", "夸大问题的严重性", "\"这太可怕了，我受不了了\"", "\"用1-10分评价的话，这件事的严重程度是多少？\""],
          ["情绪推理", "认为情绪反映了现实", "\"我感到害怕，所以一定有危险\"", "\"害怕就一定意味着有危险吗？\""],
          ["应该陈述", "用\"应该\"给自己压力", "\"我应该总是表现很好\"", "\"如果换成你的朋友，你会对他说'应该'吗？\""]
        ],
        [2000, 2500, 3000, 2860]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("2.4.3 识别自动化想法话术模板")] }),
      ...systemPrompt("【想法引入】", "当这件事发生的时候，你的脑子里有没有冒出过什么想法？即使只是一个小小的念头，也可以告诉我。"),
      emptyLine(),
      ...systemPrompt("【想法探索】", "那时候你在想什么？比如'我一定做不好'或者'没人会喜欢我'这样的想法？"),
      emptyLine(),
      ...systemPrompt("【想法影响】", "那个想法出现的时候，你心里有什么感觉？它让你想做什么或者说什么？"),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("2.5 第五阶段：认知修正（COGNITIVE）")] }),
      p("目标：通过认知重构技术，帮助用户建立更平衡、更理性的思维方式。", true),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("2.5.1 认知重构技术详解")] }),
      p("认知重构是CBT的核心技术，包括以下具体技术：", true),
      emptyLine(),
      createTable(
        ["技术名称", "操作步骤", "示例应用"],
        [
          ["证据检验法", "1.列出支持负面想法的证据 2.列出反对的证据 3.形成平衡观点", "想法：\"没人喜欢我\" → 证据检验后：\"其实有1-2个同学愿意和我说话\""],
          ["苏格拉底追问", "通过系列问题引导自我发现", "连续追问\"为什么你觉得...\"直到发现更合理的解释"],
          ["行为实验", "设计小任务验证想法的真实性", "想法\"没人喜欢我\" → 尝试主动和别人打招呼，验证是否被拒绝"],
          ["认知重评", "从不同角度重新诠释事件", "考试失败：从\"我很失败\"重评为\"这次没准备好，下次可以改进\""],
          ["接纳承诺", "承认负面想法的存在，但选择不去认同", "想法存在，但\"我是一个有价值的人，这个想法不等于我\""],
          ["积极自我对话", "用积极陈述替代消极陈述", "将\"我做不了\"替换为\"我可以尝试，如果不行再想办法\""]
        ],
        [2000, 3500, 3860]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("2.5.2 认知重构话术模板")] }),
      ...systemPrompt("【引入认知重构】", "我注意到你有一个想法……（复述负面想法）。我们来一起看看，这个想法是不是完全准确的呢？"),
      emptyLine(),
      ...systemPrompt("【证据检验】", "有没有什么证据支持这个想法？又有没有什么证据表明这个想法可能不完全对？"),
      emptyLine(),
      ...systemPrompt("【替代想法生成】", "如果换一种方式来想这件事，你觉得可能会是什么样的想法？有没有不那么让你难受的解释？"),
      emptyLine(),
      ...systemPrompt("【新想法强化】", "你刚才找到了一个更平衡的想法……（复述新想法）。你觉得这个新想法对你来说感觉怎么样？"),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("2.6 第六阶段：微行动建议（ACTION）")] }),
      p("目标：将认知改变转化为具体可执行的行动，确保CBT技能能够迁移到日常生活中。", true),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("2.6.1 微行动设计原则")] }),
      createTable(
        ["设计原则", "说明", "正面示例", "反面示例"],
        [
          ["小而具体", "行动必须明确可执行", "\"今天课间主动和一个同学说话\"", "\"多交朋友\""],
          ["即时可行", "能在24小时内完成", "\"今天回家后做3分钟深呼吸\"", "\"改变我的性格\""],
          ["难度匹配", "难度略高于当前水平", "\"尝试在课堂上举手发言1次\"", "\"下次考试必须考到90分\""],
          ["正向强化", "关注积极行为而非消除负面", "\"每天夸自己一句\"", "\"不再发脾气\""],
          ["可测量", "能够明确判断是否完成", "\"运动20分钟\"", "\"少看手机\""]
        ],
        [2000, 2000, 2500, 2860]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("2.6.2 微行动类型分类")] }),
      createTableCyan(
        ["行动类型", "具体行动示例", "对应问题"],
        [
          ["行为激活", "和同学一起吃午饭、参加兴趣小组", "孤独、回避社交"],
          ["放松训练", "深呼吸5次、渐进式肌肉放松、正念冥想", "焦虑、紧张"],
          ["认知练习", "每天记录一个负面想法并反驳、写积极日记", "负面思维模式"],
          ["社交技能", "主动打招呼、表达感谢、倾听练习", "人际关系困难"],
          ["问题解决", "列出问题解决方案、评估利弊、选择执行", "面对具体困难"],
          ["自我关爱", "做喜欢的事、保证睡眠、健康饮食", "低自尊、自我忽视"]
        ],
        [2000, 4000, 3360]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("2.6.3 微行动建议话术模板")] }),
      ...systemPrompt("【行动提议】", "我们刚才找到了一个更积极的思考方式。现在我们来想一想，你可以做什么小事把这个想法变成行动呢？"),
      emptyLine(),
      ...systemPrompt("【共同制定】", "你觉得有什么小事情是你今天或者明天就可以做的？不用很大，只要你觉得能做到就行。"),
      emptyLine(),
      ...systemPrompt("【确认计划】", "那我们就定一个小目标……（复述行动）。你觉得你能做到吗？有什么可能阻碍你的吗？"),
      emptyLine(),
      ...systemPrompt("【鼓励承诺】", "你愿意试试看吗？如果你做到了，告诉我，我会为你高兴的！"),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("2.7 第七阶段：稳定结束（CLOSE）")] }),
      p("目标：确保用户带着积极感受和明确计划离开，总结整个对话的要点，约定后续跟进。", true),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("2.7.1 标准收尾话术")] }),
      ...systemPrompt("【进展回顾】", "今天我们聊了很多，你说了……（总结用户分享的内容），我们一起发现了……（总结认知重构的发现），你决定……（总结行动承诺）。"),
      emptyLine(),
      ...systemPrompt("【正向强化】", "我很欣赏你今天愿意敞开心扉，和我分享这些。你很勇敢，能够面对自己的感受并尝试改变。"),
      emptyLine(),
      ...systemPrompt("【行动提醒】", "记得你的小计划是……（重复行动承诺）。如果你做到了，你会发现……（描述积极结果）。"),
      emptyLine(),
      ...systemPrompt("【开放邀请】", "下次你还想聊聊，或者遇到困难想找人支持的时候，随时可以来找我。我会在这里等你的。"),
      emptyLine(),
      ...systemPrompt("【告别语】", "今天的聊天就到这里啦。照顾好自己，记得你是一个很棒的人！再见！"),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("2.7.2 收尾阶段检查清单")] }),
      createTable(
        ["检查项", "完成标准", "未完成时的补救措施"],
        [
          ["情绪确认", "用户情绪状态已稳定或改善", "进行额外的情绪安抚"],
          ["认知收获", "用户表达了认知上的新认识", "重述关键认知重构点"],
          ["行动计划", "用户确认了具体可执行的行动", "协助制定更简单的行动"],
          ["后续途径", "用户知道如何在需要时获得帮助", "提供多种求助途径信息"],
          ["积极感受", "用户带着正面感受结束对话", "给予额外鼓励和肯定"]
        ],
        [2500, 3500, 3360]
      ),
      emptyLine(),

      // ==================== 第三章 场景化CBT流程树 ====================
      new Paragraph({ children: [new PageBreak()] }),
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("三、场景化CBT流程树（8个场景）")] }),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("3.1 考试焦虑场景")] }),
      p("适用情境：用户在考试前、考试中或考试后表现出明显的焦虑、紧张或自我否定。", true),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("3.1.1 典型认知扭曲识别")] }),
      createTableOrange(
        ["认知扭曲", "考试焦虑中的典型表现", "重构方向"],
        [
          ["灾难化", "\"我一定会考砸，会让所有人失望\"", "\"即使考不好，也不代表一切都完了\""],
          ["预测未来", "\"这次考试我肯定过不了\"", "\"我还没有考试，怎么知道结果呢？\""],
          ["能力否定", "\"我就是不够聪明/不够好\"", "\"我的价值不是由一次考试决定的\""],
          ["过度泛化", "\"每次考试我都会紧张\"", "\"有时候我考试也顺利的\""],
          ["自责思维", "\"我应该更努力，应该不紧张\"", "\"紧张是正常的，我可以学着应对\""]
        ],
        [2500, 3500, 3360]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("3.1.2 考试焦虑专用话术")] }),
      ...systemPrompt("【情绪识别】", "考试前感到紧张是很常见的。你现在想到考试的时候，心里是什么样的感觉？能试着说说吗？"),
      emptyLine(),
      ...systemPrompt("【想法探索】", "当你想到即将来临的考试时，脑子里有没有冒出过什么让你更紧张的念头？"),
      emptyLine(),
      ...systemPrompt("【放松引导】", "我们来做一个小练习。深吸一口气，慢慢地吐出来。再吸一口气，慢慢吐出。感受一下你的身体，现在有没有放松一点点？"),
      emptyLine(),
      ...systemPrompt("【认知重构】", "你说\"我一定会考砸\"，我们来想一想：有什么证据支持这个想法？又有什么证据表明你可能考得没有想象中那么糟？"),
      emptyLine(),
      ...systemPrompt("【行为建议】", "考试前你可以做一件小事情来帮助自己准备，比如：整理好考试用具、提前看看考场、或者做几分钟放松练习。你想试试哪个？"),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("3.2 人际关系冲突场景")] }),
      p("适用情境：用户与同伴、老师、家人发生冲突或关系紧张，表现出委屈、愤怒、失落等情绪。", true),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("3.2.1 同伴交往问题处理")] }),
      createTable(
        ["问题类型", "表现特征", "引导策略", "话术示例"],
        [
          ["被孤立", "没人一起玩、被排斥", "情绪确认→寻找例外→社交技能练习", "\"被同伴排斥的感觉确实很难受，你能告诉我具体发生了什么吗？\""],
          ["被欺负", "被嘲笑、被恶意对待", "情绪确认→事件澄清→自我保护→转介判断", "\"有人对你不好，这是他们的错。你能告诉我他们是怎么做的吗？\""],
          ["友谊破裂", "好朋友疏远、争吵", "情绪确认→事件还原→沟通练习→关系修复", "\"失去一个好朋友是很伤心的事情。你们之间发生了什么？\""],
          ["意见不合", "与他人观点冲突", "情绪确认→换位思考→表达技巧", "\"和朋友想法不一样是正常的。你当时想说什么？\""]
        ],
        [1500, 2000, 2500, 3360]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("3.2.2 人际关系冲突话术")] }),

      ...systemPrompt("【情绪识别】", "和同伴相处中遇到问题真的会让人很难受。你现在心里是什么感觉？是生气、难过、还是委屈？"),
      emptyLine(),
      ...systemPrompt("【事件探索】", "发生了这样的事情，你能告诉我具体的情况吗？给我讲讲事情的经过。"),
      emptyLine(),
      ...systemPrompt("【换位思考】", "从对方的角度来看，他为什么要这样做呢？他可能有什么原因？"),
      emptyLine(),
      ...systemPrompt("【自我保护】", "如果下次再遇到类似的情况，你能做什么来保护自己？有没有什么办法可以让自己不那么受伤？"),
      emptyLine(),
      ...systemPrompt("【沟通建议】", "你觉得如果有机会和对方好好谈谈，你可以怎么说？要不要我来帮你想想怎么说？"),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("3.3 自卑与低自我评价场景")] }),
      p("适用情境：用户表现出自我否定、自我贬低、觉得自己不够好或不值得被爱。", true),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("3.3.1 自卑思维模式识别")] }),
      createTableOrange(
        ["思维模式", "典型表现", "识别问话", "重构方向"],
        [
          ["条件性自我价值", "\"只有...我才值得被爱\"", "\"你觉得一个人要怎样才值得被喜欢？\"", "\"你的价值不是由条件决定的\""],
          ["社会比较", "\"别人都比我好\"", "\"你在和谁比较？这种比较公平吗？\"", "\"每个人都有自己的长处\""],
          ["完美主义", "\"我必须做到完美\"", "\"完美是可能的吗？\"", "\"努力比完美更重要\""],
          ["过去决定论", "\"我就是这样的，改不了了\"", "\"过去的事情能决定你现在是谁吗？\"", "\"你可以从现在开始改变\""],
          ["读心术", "\"别人一定觉得我很差\"", "\"你怎么知道别人在想什么？\"", "\"你无法确定别人的想法\""]
        ],
        [2500, 2500, 2500, 2360]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("3.3.2 自卑话题话术")] }),
      ...systemPrompt("【共情切入】", "我听到你说你觉得自己不够好。其实很多人都有过这样的想法，包括我自己有时候也会有。"),
      emptyLine(),
      ...systemPrompt("【例外探索】", "有没有什么时候，即使你觉得自己不够好，但事情其实还是顺利的？有没有人曾经夸奖过你？"),
      emptyLine(),
      ...systemPrompt("【优势识别】", "如果让你说出自己身上的三个优点，你会说什么呢？不要谦虚，试着说说看。"),
      emptyLine(),
      ...systemPrompt("【去个性化】", "你说你\"很糟糕\"，但这是指你做的某件事，还是你整个人？你觉得一个人可以因为一件事没做好就被定义为\"糟糕\"吗？"),
      emptyLine(),
      ...systemPrompt("【希望注入】", "如果有一天你不再这样觉得自己不够好了，你想过会是什么样子吗？那个版本的你会做什么不同的事？"),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("3.4 愤怒情绪管理场景")] }),
      p("适用情境：用户表现出明显的愤怒、暴躁、有破坏性行为的冲动。", true),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("3.4.1 愤怒控制对话流程")] }),
      createTable(
        ["阶段", "目标", "话术要点", "注意事项"],
        [
          ["冷静确认", "帮助用户降温", "共情但不强化愤怒", "避免说\"你不应该生气\""],
          ["情绪命名", "识别愤怒及其强度", "0-10分量表评估", "探索愤怒背后的其他情绪"],
          ["触发事件", "明确愤怒来源", "开放式询问", "不评判事件对错"],
          ["认知探索", "识别愤怒背后的想法", "苏格拉底式提问", "\"那让你想到了什么？\""],
          ["替代反应", "探索其他应对方式", "行为实验设计", "从小步骤开始"],
          ["预防计划", "建立长期应对策略", "触发-预警-行动计划", "识别早期预警信号"]
        ],
        [1500, 2000, 3500, 2360]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("3.4.2 愤怒管理话术")] }),
      ...systemPrompt("【情绪确认】", "我能感觉到你现在非常生气。生气是每个人都会有的情绪，重要的是我们怎么和它相处。"),
      emptyLine(),
      ...systemPrompt("【强度评估】", "如果用0到10来评分，10是你能想象的最生气，0是一点都不生气，你现在大概是多少？"),
      emptyLine(),
      ...systemPrompt("【身体觉察】", "生气的时候，身体会有什么反应？比如心跳加快、脸发热、拳头握紧？", "引导觉察身体信号有助于情绪调节"),
      emptyLine(),
      ...systemPrompt("【暂停技巧】", "当感到非常生气的时候，我们可以试试一个\"暂停\"的方法：深呼吸三次，然后从1数到10。你想试试吗？"),
      emptyLine(),
      ...systemPrompt("【愤怒重构】", "愤怒通常是因为我们有在乎的东西。你觉得这次让你生气的事情，背后有什么是你在乎的吗？"),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("3.5 孤独与长期低落场景")] }),
      p("适用情境：用户长期感到孤独、寂寞、闷闷不乐，表现出抑郁风险迹象。", true),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("3.5.1 抑郁风险识别指标")] }),
      createTableRed(
        ["风险指标", "具体表现", "评估问题"],
        [
          ["情绪症状", "持续两周以上情绪低落、兴趣减退", "\"这种闷闷不乐的感觉持续多久了？是最近还是已经有一段时间了？\""],
          ["认知症状", "自我价值感低、无助感、注意力下降", "\"你有没有觉得自己没有价值，或者对什么都提不起兴趣？\""],
          ["生理症状", "睡眠问题、食欲改变、精力不足", "\"最近睡眠怎么样？有没有吃不下饭或者特别累的情况？\""],
          ["行为症状", "回避社交、活动减少、学业下降", "\"你最近还会和朋友一起玩吗？有没有减少参加以前喜欢的活动？\""],
          ["自杀风险", "死亡相关想法、自我否定、绝望感", "\"有时候情绪很低落的时候，会有一闪而过\"活着没意思\"的想法吗？\""]
        ],
        [2000, 3000, 4360]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("3.5.2 孤独与低落话题话术")] }),
      ...systemPrompt("【温柔询问】", "你提到有时候会感到孤独，这种感觉确实不好受。孤独的时候你一般会做什么呢？"),
      emptyLine(),
      ...systemPrompt("【兴趣探索】", "有没有什么事情是你以前喜欢做，但现在不太想做的？我们一起来想想有什么小事可以让你感觉好一点。"),
      emptyLine(),
      ...systemPrompt("【社交支持】", "在你认识的人里，有没有让你觉得比较舒服、可以信任的人？哪怕只有一个也好。"),
      emptyLine(),
      ...systemPrompt("【行为激活】", "今天能不能试着做一件小事？比如出门走走、给朋友发个消息、或者做一件让你以前开心的事情。不用很大，就一件小事就好。"),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("3.6 学习困难/厌学场景")] }),
      p("适用情境：用户表现出对学习的抵触、厌学情绪、学习动力缺失或学业压力过大。", true),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("3.6.1 厌学原因分析框架")] }),
      createTable(
        ["原因类别", "具体表现", "引导策略"],
        [
          ["学业挫折", "成绩不理想、作业困难、考试失利", "重塑努力与能力的关系，降低对失败的恐惧"],
          ["社交困难", "被欺负、被孤立、不融入集体", "社交技能训练，提供同伴交往支持"],
          ["师生关系", "被老师批评、不被理解、关系紧张", "换位思考，探索改善关系的方法"],
          ["压力过大", "期望过高、家长施压、竞争焦虑", "压力管理，目标重新设定"],
          ["动机缺失", "不知道为什么要学、对什么都没兴趣", "意义探索，兴趣唤醒"],
          ["情绪问题", "焦虑、抑郁等情绪影响学习", "情绪疏导，可能需要转介"]
        ],
        [2000, 3500, 3860]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("3.6.2 厌学话题话术")] }),
      ...systemPrompt("【情绪确认】", "听起来学习这件事让你感到很压力和疲惫。这种感觉很不好受，我能理解。"),
      emptyLine(),
      ...systemPrompt("【原因探索】", "你对学习感到抵触，是什么时候开始的？发生了什么事情让你变成这样的吗？"),
      emptyLine(),
      ...systemPrompt("【兴趣锚定】", "如果学习让你感到痛苦，我很想了解一下：有没有什么科目或活动是你比较喜欢的？哪怕只有一点点喜欢也行。"),
      emptyLine(),
      ...systemPrompt("【微小目标】", "我们不急着一下子解决所有问题。先从一小步开始，今天能不能只专注学习15分钟？"),
      emptyLine(),
      ...systemPrompt("【意义关联】", "长远来看，你有没有想过以后想做什么？学习和你想要的生活有什么关联？"),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("3.7 家庭冲突/亲子问题场景")] }),
      p("适用情境：用户与父母或其他家庭成员发生冲突，表现出委屈、愤怒、恐惧等情绪。", true),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("3.7.1 家庭冲突分类处理")] }),
      createTable(
        ["冲突类型", "典型表现", "处理原则", "话术要点"],
        [
          ["日常争吵", "与父母意见不合、争执", "情绪疏导，换位思考", "不站队，承认双方感受"],
          ["过度控制", "被父母严格要求、限制自由", "理解父母出发点，沟通技巧", "表达需求而非指责"],
          ["忽视虐待", "情感忽视、身体/心理虐待", "安全评估，转介专业人员", "确认用户安全，保密例外"],
          ["父母离异", "家庭破碎、归属感缺失", "情绪支持，事实澄清", "不评判父母，允许悲伤"],
          ["二胎冲突", "觉得被忽视、偏心", "情感确认，需求表达", "接纳嫉妒情绪，引导沟通"]
        ],
        [2000, 2500, 2500, 3360]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("3.7.2 家庭冲突话术")] }),
      ...systemPrompt("【情绪确认】", "和家人发生冲突真的让人很为难。你现在一定有很多复杂的感受，能告诉我你现在在想什么吗？"),
      emptyLine(),
      ...systemPrompt("【去责备】", "你觉得爸爸妈妈为什么要这样做？他们的出发点可能是什么？", "引导理解父母立场，但不要求认同"),
      emptyLine(),
      ...systemPrompt("【自我表达】", "如果爸爸妈妈能听到你最想说的话，你最想让他们知道的是什么？"),
      emptyLine(),
      ...systemPrompt("【沟通建议】", "有时候用\"我觉得...\"而不是\"你总是...\"来表达，会让对方更容易听进去。你想试试吗？"),
      emptyLine(),
      ...systemPrompt("【安全确认】", "我想确认一下，你说的情况是家里平时的争吵，还是有让你感到害怕或者受伤的地方？", "确保没有虐待情况"),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("3.8 霸凌应对场景")] }),
      p("适用情境：用户正在经历或曾经经历过校园霸凌，表现出恐惧、愤怒、自我否定等情绪。", true),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("3.8.1 霸凌类型识别")] }),
      createTableRed(
        ["霸凌类型", "表现特征", "示例"],
        [
          ["身体霸凌", "推搡、打踢、损坏物品", "被人推倒、东西被抢走"],
          ["言语霸凌", "嘲笑、辱骂、威胁", "\"没人想和你玩\"、起侮辱性外号"],
          ["关系霸凌", "排斥、散布谣言、社会孤立", "故意不邀请参加活动、让其他人不要理你"],
          ["网络霸凌", "网络上的恶意信息、隐私泄露", "被人在群里嘲笑、被PS照片"],
          ["反击型霸凌", "被霸凌者的报复行为", "因为被欺负而攻击他人"]
        ],
        [2000, 3000, 4360]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("3.8.2 霸凌应对话术")] }),
      ...systemPrompt("【安全确认】", "有人对你不好，这不是你的错。你能告诉我具体发生了什么吗？我想知道更多才能帮你。"),
      emptyLine(),
      ...systemPrompt("【情绪确认】", "被这样对待一定让你非常难受和委屈。这种愤怒和难过的感受都是正常的。"),
      emptyLine(),
      ...systemPrompt("【自我保护】", "当你遇到霸凌的时候，你是怎么应对的？有没有试过告诉老师或者家长？"),
      emptyLine(),
      ...systemPrompt("【资源链接】", "你觉得学校里有谁是你可以求助的？比如班主任、辅导员、或者其他信任的老师？"),
      emptyLine(),
      ...systemPrompt("【能力确认】", "你不是一个软弱的人。能一直坚持面对这些已经很不容易了。你身上有很多优点，这些不能定义你的价值。"),
      emptyLine(),
      ...systemPrompt("【行动建议】", "下次如果再遇到这样的情况，你可以：1.远离那些人 2.告诉信任的大人 3.记录发生的事情。你觉得哪个对你来说最容易做到？"),
      emptyLine(),

      // ==================== 第四章 高风险流程 ====================
      new Paragraph({ children: [new PageBreak()] }),
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("四、高风险流程")] }),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("4.1 风险等级定义")] }),
      createTableRed(
        ["风险等级", "风险描述", "典型信号", "系统响应"],
        [
          ["L0-L1", "无风险或极低风险", "正常对话内容", "正常CBT流程"],
          ["L2", "低风险", "轻微负面情绪，无自伤倾向", "加强关注，维持CBT流程"],
          ["L3", "中风险", "持续的负面情绪，间接的自伤暗示", "启动风险复检，通知教师"],
          ["L4", "高风险", "明确的自杀/自残念头，被霸凌经历", "立即升级，通知家长和老师"],
          ["L5", "紧急风险", "明确的自杀计划，正在实施自伤", "立即紧急升级，联系急救"]
        ],
        [1200, 2000, 3500, 2660]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("4.2 L4高风险：自伤表达/轻生暗示完整处理流程")] }),
      p("当用户表达自伤想法或轻生暗示时，必须按照以下流程处理：", true),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("4.2.1 识别信号")] }),
      createTableRed(
        ["信号类别", "具体表现", "示例"],
        [
          ["直接表达", "明确说出想死、自杀的念头", "\"我不想活了\"、\"活着没意思\""],
          ["间接暗示", "用隐晦方式表达自伤意愿", "\"有时候睡着就不用面对了\"、\"我消失了也没人会在意\""],
          ["告别行为", "交代后事、赠送物品、突然平静", "开始整理东西、把珍贵物品送人"],
          ["自我贬低", "极端的自我否定和绝望感", "\"都是我的错，我不该存在\""],
          ["先前史", "之前有过自伤或自杀尝试", "用户主动提及或系统记录显示"]
        ],
        [2000, 3000, 4360]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("4.2.2 L4处理话术模板")] }),
      ...systemPrompt("【危机评估询问】", "谢谢你愿意告诉我这些。我很关心你的安全，我想直接问你：最近你有没有想过伤害自己，或者想过自杀？"),
      emptyLine(),
      ...systemPrompt("【如果用户确认有想法】", "我想了解更多，才能更好地帮助你。你能告诉我这些想法是什么时候开始的？有没有具体的计划？"),
      emptyLine(),
      ...systemPrompt("【安全确认】", "现在你身边有能够保证你安全的大人吗？有没有你信任的家人或者老师可以陪伴你？"),
      emptyLine(),
      ...systemPrompt("【持续支持】", "我想让你知道，无论发生什么，我都会在这里陪着你。你不是一个人。"),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("4.2.3 L4升级流程")] }),
      numbered("完成危机评估询问，确认风险等级"),
      numbered("保持对话，传递支持和希望"),
      numbered("联系最近联系过的教师用户"),
      numbered("在对话中记录完整的危机对话内容"),
      numbered("触发L4风险预警通知"),
      numbered("确保用户身边有监护人陪伴"),
      numbered("提供心理援助热线信息"),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("4.3 L5紧急风险：明确自杀计划应急响应流程")] }),
      p("当用户表现出以下情况时，触发L5紧急响应：", true),
      createTableRed(
        ["L5触发条件", "说明"],
        [
          ["明确的自杀计划", "用户描述了具体的自杀时间、地点、方式"],
          ["正在实施自伤", "用户表示正在或即将进行自伤行为"],
          ["无法保证安全", "用户无法联系到任何可提供安全保障的人"]
        ],
        [4000, 5360]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("4.3.1 L5紧急响应话术")] }),
      ...systemPrompt("【紧急安全询问】", "我听到你说了一些让我非常担心的话。我想直接问你：你现在有具体的计划要伤害自己吗？如果有，请告诉我。"),
      emptyLine(),
      ...systemPrompt("【如果用户有计划】", "我现在需要联系能够立刻帮助你的人。请告诉我你父母的电话，或者学校老师的联系方式。"),
      emptyLine(),
      ...systemPrompt("【紧急联系】", "如果你觉得自己可能会立即伤害自己，请拨打这个电话：全国心理援助热线 400-161-9995，或者立刻去最近的医院急诊。"),
      emptyLine(),
      ...systemPrompt("【持续陪伴】", "我会一直在线上陪着你，直到有人来帮助你。请不要独自一人，如果可能的话，找身边最近的人陪伴你。"),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("4.4 家暴/性侵场景处理流程")] }),
      p("当用户透露或暗示遭受家暴或性侵时，必须谨慎处理：", true),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("4.4.1 识别信号")] }),
      createTableRed(
        ["信号类型", "表现示例", "询问方式"],
        [
          ["身体迹象", "解释不清的伤痕、穿衣遮盖", "\"你身上有伤，能告诉我怎么造成的吗？\""],
          ["行为变化", "突然的恐惧、回避某人、睡眠问题", "\"你看起来有点害怕，是发生了什么事吗？\""],
          ["直接揭露", "儿童主动告知", "\"谢谢你告诉我这些，这需要很大的勇气\""],
          ["间接透露", "通过故事、比喻暗示", "\"有人让我很不舒服，我不能说出是谁\""]
        ],
        [2000, 3500, 3860]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun("4.4.2 家暴/性侵场景话术")] }),
      ...systemPrompt("【确认安全】", "你告诉我这些需要很大的勇气。我在乎你的安全。你现在身边有能保护你的人吗？"),
      emptyLine(),
      ...systemPrompt("【不评判】", "我想让你知道，无论发生了什么，这不是你的错。你没有做错任何事情。"),
      emptyLine(),
      ...systemPrompt("【保密例外】", "为了确保你的安全，我需要让能够帮助你的大人知道这件事。但是我会只告诉必须知道的人。"),
      emptyLine(),
      ...systemPrompt("【专业支持】", "有专门的机构和人员能够帮助受到伤害的儿童。我会帮你联系他们。"),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("4.5 危机对话标准话术模板")] }),
      createTableRed(
        ["场景", "话术模板", "目的"],
        [
          ["开场关怀", "\"我注意到你最近说的话让我有些担心，你还好吗？\"", "温和引入敏感话题"],
          ["情绪确认", "\"听起来你现在非常痛苦，谢谢你愿意告诉我。\"", "建立信任"],
          ["直接询问", "\"有时候当人们非常痛苦的时候，会有伤害自己的想法。你有没有过这样的想法？\"", "明确风险"],
          ["安全计划", "\"当我们情绪不好的时候，能提前想一些能让自己感觉好一点的方法很重要。你有什么办法能让自己感觉好一点吗？\"", "建立安全网"],
          ["资源提供", "\"如果你需要帮助，可以拨打这个热线：全国心理援助热线 400-161-9995\"", "提供紧急资源"],
          ["持续支持", "\"我想让你知道，无论什么时候你想找人聊聊，我都在这里。\"", "建立持续支持"]
        ],
        [2000, 5000, 2360]
      ),
      emptyLine(),

      // ==================== 第五章 风险评估与干预机制 ====================
      new Paragraph({ children: [new PageBreak()] }),
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("五、风险评估与干预机制")] }),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("5.1 风险等级划分标准")] }),
      createTable(
        ["风险等级", "名称", "评估标准", "响应级别"],
        [
          ["L0", "无风险", "正常对话，无负面情绪或轻度正面情绪", "无需干预"],
          ["L1", "极低风险", "存在可识别的负面情绪，但无自伤/自杀风险", "标准CBT流程"],
          ["L2", "低风险", "持续的负面情绪，情绪强度中等", "加强监测，缩短复检周期"],
          ["L3", "中风险", "间接的自伤暗示、严重抑郁症状、急性应激反应", "启动风险复检，48小时内教师跟进"],
          ["L4", "高风险", "明确的自杀/自残念头、被霸凌、严重创伤", "立即升级，24小时内启动家长通知"],
          ["L5", "紧急风险", "明确的自杀计划、正在实施自伤", "立即紧急升级，立即联系急救/专业机构"]
        ],
        [1000, 1500, 4500, 2360]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("5.2 不同风险等级应对策略")] }),
      createTableCyan(
        ["风险等级", "系统响应", "人工介入", "记录要求"],
        [
          ["L0-L1", "正常CBT流程", "无需", "标准记录"],
          ["L2", "增加情绪确认频率", "教师关注", "标记为\"需关注\""],
          ["L3", "启动风险复检，嵌入支持性对话", "48小时内教师电话跟进", "生成风险事件记录"],
          ["L4", "暂停标准CBT，启动危机干预话术", "立即通知教师和家长", "生成紧急事件记录，保留完整对话"],
          ["L5", "启动紧急响应，联系急救", "通知学校管理层和紧急联系人", "生成紧急事件记录，保留完整对话"]
        ],
        [1500, 3000, 3000, 2860]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("5.3 危机干预转介流程")] }),
      p("当AI系统无法有效应对用户风险时，必须启动转介流程：", true),
      emptyLine(),
      createTable(
        ["转介阶段", "触发条件", "转介目标", "操作步骤"],
        [
          ["一级转介", "L3风险持续24小时", "学校心理老师", "系统自动通知心理老师，进行人工跟进"],
          ["二级转介", "L4风险确认", "家长+专业机构", "通知家长，建议寻求专业心理咨询"],
          ["三级转介", "L5风险或二级转介无效", "急救+专业机构", "建议立即就医，联系当地精神卫生中心"],
          ["持续支持", "转介后", "AI系统", "在专业机构介入前保持陪伴，不中断联系"]
        ],
        [1500, 2500, 3000, 2360]
      ),
      emptyLine(),

      // ==================== 第六章 儿童语言规范 ====================
      new Paragraph({ children: [new PageBreak()] }),
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("六、儿童语言规范")] }),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("6.1 禁用词汇表")] }),
      p("以下词汇和表达方式在儿童心理陪伴场景中禁止使用：", true),
      emptyLine(),
      createTableRed(
        ["禁用类别", "禁用词汇/表达", "替代建议"],
        [
          ["诊断性词汇", "抑郁症、焦虑症、精神病、心理疾病", "情绪低落、紧张、心里不舒服"],
          ["绝对化词汇", "永远、总是、一定、必须", "有时候、可能、也许"],
          ["贬低性词汇", "你很笨、你不行、你太差了", "这件事没做好，但我们都有擅长的领域"],
          ["创伤性词汇", "死亡、自杀、杀死、消失", "不开心、难受、想逃避"],
          ["否定情绪", "你不应该生气、不用难过", "生气是正常的、难过是可以的"],
          ["过度承诺", "我保证、一定没事、绝对没问题", "我会尽力、我和你在一起"],
          ["比较性词汇", "别人都行、你看看人家", "每个人都是不一样的"]
        ],
        [2000, 3500, 3860]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("6.2 推荐表达方式")] }),
      createTableCyan(
        ["场景", "禁用表达", "推荐表达"],
        [
          ["共情", "我理解你的感受", "我能感觉到你现在很……（识别情绪），换作是我也会有这样的感受"],
          ["确认", "你做得对", "你这么做需要很大的勇气，我理解这对你来说不容易"],
          ["引导", "你应该……", "你有没有想过……？你觉得……怎么样？"],
          ["建议", "你必须……", "我们可以试试……你觉得这样可以吗？"],
          ["反馈", "你错了", "我有一个不同的想法，想听听吗？"],
          ["安慰", "别哭了/不要难过", "难过是可以的哭出来也没关系，我会陪着你"]
        ],
        [1500, 3500, 4360]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("6.3 年龄段适配规范")] }),
      createTable(
        ["年龄段", "认知特点", "语言要求", "话术示例"],
        [
          ["6-8岁", "具体运算思维，想法具体化", "简短（5-10词），日常词汇，游戏化语言", "\"我们一起来玩个游戏吧！\""],
          ["9-12岁", "抽象思维发展，能够理解隐喻", "中等长度，可使用简单比喻", "\"就像……一样，你的感觉是……\""],
          ["13岁以上", "抽象思维成熟，能够反思", "接近成人长度，可使用复杂概念", "\"这让你对自己有什么新的理解？\""]
        ],
        [1500, 3000, 3000, 2860]
      ),
      emptyLine(),

      // ==================== 第七章 流程控制机制 ====================
      new Paragraph({ children: [new PageBreak()] }),
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("七、流程控制机制")] }),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("7.1 状态机实现设计")] }),
      p("CBT对话流程使用状态机管理，状态转换规则如下：", true),
      emptyLine(),
      createTable(
        ["当前状态", "事件/信号", "目标状态", "动作/输出"],
        [
          ["INIT", "对话启动", "SAFE", "发送欢迎语"],
          ["SAFE", "用户表达情绪", "EMOTION", "确认情绪"],
          ["SAFE", "用户描述问题", "EVENT", "记录事件"],
          ["EMOTION", "情绪已命名", "EVENT", "进入事件确认"],
          ["EVENT", "事件已明确", "THOUGHT", "探索想法"],
          ["THOUGHT", "想法已识别", "COGNITIVE", "认知重构"],
          ["COGNITIVE", "认知已修正", "ACTION", "行动建议"],
          ["ACTION", "行动已确定", "CLOSE", "收尾"],
          ["ANY", "risk_level >= 3", "ESCALATE", "启动升级"],
          ["ESCALATE", "风险已解除", "CLOSE", "安全收尾"],
          ["CLOSE", "对话结束", "INIT", "清理状态"]
        ],
        [2000, 2500, 2000, 2860]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("7.2 最大轮次限制")] }),
      createTable(
        ["控制维度", "限制值", "说明", "超限响应"],
        [
          ["每阶段最大轮次", "5轮", "每个CBT阶段最多对话5次", "提示进入下一阶段或结束"],
          ["单次对话最大轮次", "30轮", "整个对话的总轮数上限", "执行收尾流程"],
          ["同一问题追问次数", "3次", "针对同一信息点最多追问3次", "使用总结确认推进"],
          ["沉默/无响应次数", "2次", "连续2次无有效输入", "发送关心提示或收尾"]
        ],
        [2500, 1500, 3000, 2360]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("7.3 自动收尾机制")] }),
      p("以下情况触发自动收尾机制：", true),
      emptyLine(),
      createTable(
        ["触发条件", "检测方式", "收尾策略", "示例话术"],
        [
          ["达到最大轮次", "轮次计数器", "总结要点+行动确认", "\"今天我们聊了很多，你感觉怎么样？\""],
          ["对话循环检测", "重复内容识别", "引导新话题或收尾", "\"我们好像聊到这个话题好几次了\""],
          ["情绪稳定", "情绪强度持续低于阈值", "正向总结+鼓励", "\"你今天表现得很棒！\""],
          ["用户主动结束", "用户表达再见意图", "标准收尾", "\"随时欢迎再来找我哦！\""],
          ["系统中断", "超时/异常检测", "保护性收尾", "\"今天先聊到这里，下次再见\""]
        ],
        [2000, 2000, 2500, 2860]
      ),
      emptyLine(),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("7.4 流程异常处理")] }),
      createTable(
        ["异常类型", "检测方式", "处理策略", "示例"],
        [
          ["用户回避问题", "连续3次转移话题", "尊重回避但重新引导", "\"好的，那我们聊点别的。不过如果你想说的，我随时都在\""],
          ["用户沉默", "无有效输入超过时间", "发送关心询问", "\"你还好吗？需要我等你一下吗？\""],
          ["情绪激越", "情绪强度突然大幅上升", "暂停当前话题，共情安抚", "\"我感觉到你现在的情绪很强烈，我们先缓一缓\""],
          ["信息矛盾", "前后表述不一致", "温和澄清不质疑", "\"我有点困惑，你刚才说...现在又说...\""],
          ["超时恢复", "用户长时间无响应后恢复", "简短回顾后继续", "\"欢迎回来！我们刚才聊到...\""]
        ],
        [2000, 2500, 2500, 2860]
      ),
      emptyLine(),

      // 文档结束
      emptyLine(),
      new Paragraph({
        alignment: AlignmentType.CENTER,
        children: [new TextRun({ text: "—— 文档结束 ——", italics: true, color: "888888" })]
      })
    ]
  }]
});

Packer.toBuffer(doc).then(buffer => {
  fs.writeFileSync(OUTPUT_PATH, buffer);
  console.log('CBT对话流程树详细设计文档创建成功:', OUTPUT_PATH);
}).catch(err => {
  console.error('创建文档时出错:', err);
});
