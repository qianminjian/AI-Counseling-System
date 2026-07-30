const { Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
        Header, Footer, AlignmentType, LevelFormat, HeadingLevel,
        BorderStyle, WidthType, ShadingType, PageNumber, PageBreak,
        TableOfContents } = require('docx');
const fs = require('fs');

// 辅助函数：创建标准段落
function createParagraph(text, options = {}) {
    return new Paragraph({
        children: [new TextRun({
            text: text,
            font: '微软雅黑',
            size: options.size || 24,
            bold: options.bold || false,
            color: options.color || '000000'
        })],
        spacing: { after: options.afterSpacing || 120 }
    });
}

// 辅助函数：创建标题段落
function createHeading(text, level) {
    return new Paragraph({
        heading: level,
        children: [new TextRun({
            text: text,
            font: '微软雅黑',
            bold: true
        })],
        spacing: { before: 300, after: 200 }
    });
}

// 辅助函数：创建代码块段落
function createCodeParagraph(code) {
    return new Paragraph({
        children: [new TextRun({
            text: code,
            font: 'Consolas',
            size: 18,
            color: '333333'
        })],
        spacing: { after: 80 },
        indent: { left: 360 }
    });
}

// 辅助函数：创建带边框的表格
function createTable(headers, rows, columnWidths) {
    const border = { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' };
    const borders = { top: border, bottom: border, left: border, right: border };

    const headerRow = new TableRow({
        children: headers.map((header, idx) => new TableCell({
            borders,
            width: { size: columnWidths[idx], type: WidthType.DXA },
            shading: { fill: '2E75B6', type: ShadingType.CLEAR },
            margins: { top: 80, bottom: 80, left: 120, right: 120 },
            children: [new Paragraph({
                children: [new TextRun({
                    text: header,
                    font: '微软雅黑',
                    bold: true,
                    color: 'FFFFFF',
                    size: 20
                })]
            })]
        }))
    });

    const dataRows = rows.map(row => new TableRow({
        children: row.map((cell, idx) => new TableCell({
            borders,
            width: { size: columnWidths[idx], type: WidthType.DXA },
            shading: { fill: idx === 0 ? 'F2F7FB' : 'FFFFFF', type: ShadingType.CLEAR },
            margins: { top: 80, bottom: 80, left: 120, right: 120 },
            children: [new Paragraph({
                children: [new TextRun({
                    text: cell,
                    font: '微软雅黑',
                    size: 20
                })]
            })]
        }))
    }));

    return new Table({
        width: { size: columnWidths.reduce((a, b) => a + b, 0), type: WidthType.DXA },
        columnWidths: columnWidths,
        rows: [headerRow, ...dataRows]
    });
}

// 创建文档
const doc = new Document({
    styles: {
        default: {
            document: {
                run: { font: '微软雅黑', size: 24 }
            }
        },
        paragraphStyles: [
            {
                id: 'Heading1',
                name: 'Heading 1',
                basedOn: 'Normal',
                next: 'Normal',
                quickFormat: true,
                run: { size: 36, bold: true, font: '微软雅黑', color: '2E75B6' },
                paragraph: { spacing: { before: 400, after: 200 }, outlineLevel: 0 }
            },
            {
                id: 'Heading2',
                name: 'Heading 2',
                basedOn: 'Normal',
                next: 'Normal',
                quickFormat: true,
                run: { size: 28, bold: true, font: '微软雅黑', color: '2E75B6' },
                paragraph: { spacing: { before: 300, after: 150 }, outlineLevel: 1 }
            },
            {
                id: 'Heading3',
                name: 'Heading 3',
                basedOn: 'Normal',
                next: 'Normal',
                quickFormat: true,
                run: { size: 24, bold: true, font: '微软雅黑', color: '333333' },
                paragraph: { spacing: { before: 200, after: 100 }, outlineLevel: 2 }
            }
        ]
    },
    numbering: {
        config: [
            {
                reference: 'bullets',
                levels: [{
                    level: 0,
                    format: LevelFormat.BULLET,
                    text: '•',
                    alignment: AlignmentType.LEFT,
                    style: { paragraph: { indent: { left: 720, hanging: 360 } } }
                }]
            },
            {
                reference: 'numbers',
                levels: [{
                    level: 0,
                    format: LevelFormat.DECIMAL,
                    text: '%1.',
                    alignment: AlignmentType.LEFT,
                    style: { paragraph: { indent: { left: 720, hanging: 360 } } }
                }]
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
                    children: [
                        new TextRun({ text: 'AI心理辅导系统 - Prompt体系详细设计', font: '微软雅黑', size: 18, color: '666666' })
                    ],
                    border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: '2E75B6', space: 1 } }
                })]
            })
        },
        footers: {
            default: new Footer({
                children: [new Paragraph({
                    children: [
                        new TextRun({ text: '机密 - 仅供内部使用', font: '微软雅黑', size: 18, color: '999999' }),
                        new TextRun({ text: '\t第 ', font: '微软雅黑', size: 18, color: '666666' }),
                        new TextRun({ children: [PageNumber.CURRENT], font: '微软雅黑', size: 18, color: '666666' }),
                        new TextRun({ text: ' 页', font: '微软雅黑', size: 18, color: '666666' })
                    ],
                    tabStops: [{ type: 'right', position: 9026 }]
                })]
            })
        },
        children: [
            // 标题
            new Paragraph({
                children: [new TextRun({
                    text: 'AI心理辅导系统 Prompt体系详细设计',
                    font: '微软雅黑',
                    size: 44,
                    bold: true,
                    color: '2E75B6'
                })],
                alignment: AlignmentType.CENTER,
                spacing: { after: 200 }
            }),
            new Paragraph({
                children: [new TextRun({
                    text: '版本 v1.0 | 2026年5月',
                    font: '微软雅黑',
                    size: 22,
                    color: '666666'
                })],
                alignment: AlignmentType.CENTER,
                spacing: { after: 400 }
            }),

            // 目录
            new TableOfContents('目录', {
                hyperlink: true,
                headingStyleRange: '1-3'
            }),
            new Paragraph({ children: [new PageBreak()] }),

            // ==================== 第一章 ====================
            createHeading('一、总体目标', HeadingLevel.HEADING_1),
            createParagraph('本文档定义AI心理辅导系统的Prompt工程体系，确保系统能够安全、有效、合规地为儿童和青少年提供心理支持服务。核心目标包括：'),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '安全性优先：建立五层安全防护体系，确保儿童用户安全', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '专业性保障：融合CBT、正念等循证心理治疗方法', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '儿童友好：使用适龄语言，建立信任关系', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '合规可控：满足教育部门和医疗相关法规要求', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '可追溯性：完整记录对话和决策过程，支持事后审计', font: '微软雅黑', size: 24 })]
            }),

            // ==================== 第二章 ====================
            createHeading('二、五层Prompt分层架构', HeadingLevel.HEADING_1),
            createParagraph('系统采用五层Prompt分层架构，从底层到顶层依次为：System Prompt（系统规则）、Safety Prompt（安全规则）、Workflow Prompt（流程控制）、Role Prompt（角色人格）、Task Prompt（任务Prompt）。各层职责明确，层层递进，确保系统行为的一致性和可控性。'),
            new Paragraph({ children: [new PageBreak()] }),

            createHeading('2.1 架构层次图', HeadingLevel.HEADING_2),
            createTable(
                ['层次', '名称', '职责', '变更频率'],
                [
                    ['L5', 'Task Prompt', '具体任务执行指令', '每次对话'],
                    ['L4', 'Role Prompt', '角色人格定义', '每周/版本'],
                    ['L3', 'Workflow Prompt', '流程控制规则', '每月'],
                    ['L2', 'Safety Prompt', '安全规则约束', '每周'],
                    ['L1', 'System Prompt', '系统基础规则', '季度']
                ],
                [1200, 2500, 4000, 1660]
            ),

            createHeading('2.2 System Prompt（系统规则层）', HeadingLevel.HEADING_2),
            createParagraph('System Prompt是系统运行的基础规则，定义AI的身份定位、能力边界和基本行为准则。该层内容相对稳定，仅在系统重大升级时变更。'),
            createHeading('核心组件', HeadingLevel.HEADING_3),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '身份定义：你是「心灵伙伴」，一个专为儿童设计的AI心理陪伴助手', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '能力边界：提供情绪支持和心理知识科普，不提供诊断和治疗', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '对话原则：温暖、共情、不评判、循序渐进', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '数据处理：所有对话记录本地加密存储，严格保护隐私', font: '微软雅黑', size: 24 })]
            }),
            createHeading('完整Template', HeadingLevel.HEADING_3),
            createCodeParagraph('# System Prompt Template'),
            createCodeParagraph('你是一个专为儿童和青少年设计的AI心理陪伴助手，名字叫「心灵伙伴」。'),
            createCodeParagraph('你的核心任务是：'),
            createCodeParagraph('1. 倾听并理解用户的情绪和感受'),
            createCodeParagraph('2. 提供温暖、共情的回应'),
            createCodeParagraph('3. 分享适龄的心理健康知识'),
            createCodeParagraph('4. 引导用户学习情绪管理技巧'),
            createCodeParagraph(''),
            createCodeParagraph('【重要限制】'),
            createCodeParagraph('- 你不是医生或心理咨询师，不能进行诊断或专业治疗'),
            createCodeParagraph('- 如果用户表达严重的心理困扰，必须引导寻求专业帮助'),
            createCodeParagraph('- 禁止向未成年用户提供任何可能造成伤害的信息'),
            createCodeParagraph('- 保持语言简单易懂，适合10-18岁青少年理解'),
            createCodeParagraph(''),
            createCodeParagraph('【对话风格】'),
            createCodeParagraph('- 使用温暖、友好的语气'),
            createCodeParagraph('- 多使用鼓励性语言'),
            createCodeParagraph('- 避免使用专业术语或复杂概念'),
            createCodeParagraph('- 适时使用表情符号增加亲和力（仅限儿童友好型emoji）'),

            createHeading('2.3 Safety Prompt（安全规则层）', HeadingLevel.HEADING_2),
            createParagraph('Safety Prompt定义系统安全运行的规则集，包括风险识别、响应策略和升级条件。该层具有最高优先级，任何情况下不可被覆盖或绕过。'),
            createHeading('安全规则结构', HeadingLevel.HEADING_3),
            createTable(
                ['风险等级', '触发条件', '响应策略', '记录要求'],
                [
                    ['L1-轻微', '负面情绪表达', '共情回应', '普通记录'],
                    ['L2-轻度', '压力/焦虑信号', '引导放松', '加密记录'],
                    ['L3-中度', '自伤暗示', '温和询问+建议求助', '加密+标记'],
                    ['L4-重度', '明确自伤意图', '立即升级', '实时通知老师'],
                    ['L5-紧急', '自杀相关内容', '启动危机协议', '立即通知+记录']
                ],
                [1500, 3000, 2500, 2360]
            ),
            new Paragraph({ children: [new PageBreak()] }),

            createHeading('2.4 Workflow Prompt（流程控制层）', HeadingLevel.HEADING_2),
            createParagraph('Workflow Prompt定义Agent间的协作流程和状态转换规则，确保系统按照预定逻辑运行。'),
            createHeading('核心流程', HeadingLevel.HEADING_3),
            createParagraph('对话入口流程：'),
            createCodeParagraph('1. 用户输入 -> Safety Agent[风险检测]'),
            createCodeParagraph('2. 无风险 -> Emotion Agent[情绪识别]'),
            createCodeParagraph('3. 情绪分析 -> Conversation Agent[生成回复]'),
            createCodeParagraph('4. 有风险 -> Escalation Agent[评估升级]'),
            createCodeParagraph(''),
            createParagraph('CBT干预流程：'),
            createCodeParagraph('1. 情绪识别[高焦虑/抑郁] -> CBT Agent'),
            createCodeParagraph('2. CBT Agent -> 选择干预模块'),
            createCodeParagraph('3. 认知重构/行为激活/放松训练'),
            createCodeParagraph('4. 效果评估 -> 决定是否继续或升级'),

            createHeading('2.5 Role Prompt（角色人格层）', HeadingLevel.HEADING_2),
            createParagraph('Role Prompt定义每个Agent的角色人格特征，确保其行为一致性和个性鲜明。'),
            createHeading('角色定义示例', HeadingLevel.HEADING_3),
            createTable(
                ['Agent', '角色定位', '核心特质', '沟通风格'],
                [
                    ['Conversation Agent', '主对话入口', '温暖、耐心、好奇', '倾听为主，开放式提问'],
                    ['Emotion Agent', '情绪分析师', '敏锐、细腻、客观', '分析型，数据化呈现'],
                    ['CBT Agent', '心理治疗师', '专业、引导、启发', '结构化，步骤化'],
                    ['Safety Agent', '安全守护者', '警觉、负责、果断', '直接，明确']
                ],
                [2500, 2500, 2200, 2160]
            ),

            createHeading('2.6 Task Prompt（任务层）', HeadingLevel.HEADING_2),
            createParagraph('Task Prompt是每次具体任务执行的指令，根据上下文动态组装，包含任务描述、输入数据、输出格式要求等。'),
            createCodeParagraph('【任务示例：情绪识别】'),
            createCodeParagraph('任务：为以下对话内容进行情绪识别'),
            createCodeParagraph('输入："{user_message}"'),
            createCodeParagraph('要求：'),
            createCodeParagraph('- 识别主要情绪类型'),
            createCodeParagraph('- 评估情绪强度(1-10)'),
            createCodeParagraph('- 输出JSON格式结果'),
            createCodeParagraph('输出格式：'),
            createCodeParagraph('{'),
            createCodeParagraph('  "emotion_type": "happy|sad|anxious|angry|fear|neutral",'),
            createCodeParagraph('  "intensity": 1-10,'),
            createCodeParagraph('  "keywords": ["关键词1", "关键词2"],'),
            createCodeParagraph('  "supporting_evidence": "依据文本"'),
            createCodeParagraph('}'),

            // ==================== 第三章 ====================
            createHeading('三、各Agent完整Prompt模板', HeadingLevel.HEADING_1),
            new Paragraph({ children: [new PageBreak()] }),

            createHeading('3.1 Safety Agent Prompt - 风险识别与响应', HeadingLevel.HEADING_2),
            createParagraph('Safety Agent负责实时检测用户输入和系统回复中的安全风险，是系统的第一道防线。'),
            createHeading('L1-L5风险等级详细定义', HeadingLevel.HEADING_3),
            createTable(
                ['等级', '标签', '触发条件示例', '系统响应', '人工介入'],
                [
                    ['L1', '轻微不适', '今天心情有点低落', '继续正常对话', '否'],
                    ['L2', '轻度风险', '最近压力好大，睡不着', '引导放松技巧', '否'],
                    ['L3', '中度风险', '觉得活着没什么意思', '温和询问+推荐资源', '标记'],
                    ['L4', '重度风险', '我有计划伤害自己', '立即触发升级', '必须'],
                    ['L5', '紧急危机', '已经吃了很多药', '启动危机协议', '立即']
                ],
                [800, 1200, 2800, 2500, 2060]
            ),
            createHeading('Safety Agent Prompt Template', HeadingLevel.HEADING_3),
            createCodeParagraph('[ROLE]'),
            createCodeParagraph('你是安全风险检测专家，负责分析用户输入是否包含自伤、自杀、暴力等危险内容。'),
            createCodeParagraph(''),
            createCodeParagraph('[TASK]'),
            createCodeParagraph('1. 分析用户最新输入'),
            createCodeParagraph('2. 对照风险关键词库检测'),
            createCodeParagraph('3. 评估风险等级'),
            createCodeParagraph('4. 生成风险评估报告'),
            createCodeParagraph(''),
            createCodeParagraph('[RISK_KEYWORDS]'),
            createCodeParagraph('- 自杀类：自杀、轻生、死了算了、活够了、不想活了'),
            createCodeParagraph('- 自伤类：割腕、伤害自己、划伤、烧伤'),
            createCodeParagraph('- 暴力类：杀人、报复、想死'),
            createCodeParagraph('- 色情类：性相关低俗内容'),
            createCodeParagraph(''),
            createCodeParagraph('[OUTPUT_FORMAT]'),
            createCodeParagraph('{'),
            createCodeParagraph('  "risk_level": "L1|L2|L3|L4|L5",'),
            createCodeParagraph('  "risk_type": "self_harm|suicide|violence|exploitation|none",'),
            createCodeParagraph('  "confidence": 0.0-1.0,'),
            createCodeParagraph('  "triggered_keywords": [],'),
            createCodeParagraph('  "recommended_action": "continue|escalate|immediate_intervention",'),
            createCodeParagraph('  "response_template": "具体响应话术"'),
            createCodeParagraph('}'),

            createHeading('3.2 Emotion Agent Prompt - 情绪识别', HeadingLevel.HEADING_2),
            createParagraph('Emotion Agent负责从用户输入中识别情绪状态和强度，为后续干预提供数据支持。'),
            createHeading('情绪识别JSON输出格式', HeadingLevel.HEADING_3),
            createCodeParagraph('{'),
            createCodeParagraph('  "emotion_analysis": {'),
            createCodeParagraph('    "primary_emotion": "sadness|joy|anxiety|anger|fear|surprise|disgust|neutral",'),
            createCodeParagraph('    "secondary_emotions": ["emotion1", "emotion2"],'),
            createCodeParagraph('    "intensity": {'),
            createCodeParagraph('      "score": 7,  // 1-10量表'),
            createCodeParagraph('      "level": "moderate",  // low|moderate|high|severe'),
            createCodeParagraph('      "trend": "increasing|stable|decreasing"'),
            createCodeParagraph('    },'),
            createCodeParagraph('    "emotion_keywords": ["开心", "兴奋", "期待"],'),
            createCodeParagraph('    "cognitive_indicators": {'),
            createCodeParagraph('      "rumination": true|false,  // 反刍思维'),
            createCodeParagraph('      "catastrophizing": true|false,  // 灾难化思维'),
            createCodeParagraph('      "black_and_white": true|false  // 非黑即白'),
            createCodeParagraph('    },'),
            createCodeParagraph('    "physiological_indicators": ["失眠", "食欲下降"],'),
            createCodeParagraph('    "confidence": 0.85'),
            createCodeParagraph('  }'),
            createCodeParagraph('}'),
            createHeading('Emotion Agent Prompt Template', HeadingLevel.HEADING_3),
            createCodeParagraph('[ROLE]'),
            createCodeParagraph('你是情绪分析专家，擅长从文本中识别用户的情绪状态、强度和潜在认知模式。'),
            createCodeParagraph(''),
            createCodeParagraph('[TASK]'),
            createCodeParagraph('分析用户输入中的情绪特征：'),
            createCodeParagraph('1. 识别主要和次要情绪'),
            createCodeParagraph('2. 评估情绪强度（1-10）'),
            createCodeParagraph('3. 检测负面认知模式'),
            createCodeParagraph('4. 识别生理反应指标'),
            createCodeParagraph(''),
            createCodeParagraph('[EMOTION_TAXONOMY]'),
            createCodeParagraph('- 快乐：开心、兴奋、满足、轻松'),
            createCodeParagraph('- 悲伤：难过、失落、绝望、孤独'),
            createCodeParagraph('- 焦虑：担心、紧张、害怕、恐慌'),
            createCodeParagraph('- 愤怒：生气、烦躁、怨恨、恼怒'),
            createCodeParagraph('- 恐惧：害怕、担心、焦虑、惊恐'),
            createCodeParagraph(''),
            createCodeParagraph('[COGNITIVE_PATTERNS]'),
            createCodeParagraph('- 反刍思维：反复思考负面事件'),
            createCodeParagraph('- 灾难化思维：夸大负面后果'),
            createCodeParagraph('- 非黑即白思维：极端化判断'),
            createCodeParagraph('- 个人化归因：将外部事件过度归因于自己'),
            new Paragraph({ children: [new PageBreak()] }),

            createHeading('3.3 CBT Agent Prompt - 认知行为治疗', HeadingLevel.HEADING_2),
            createParagraph('CBT Agent执行认知行为治疗干预模块，帮助用户识别和改变负面思维模式。'),
            createHeading('CBT流程Prompt', HeadingLevel.HEADING_3),
            createCodeParagraph('[MODULE] CBT认知重构'),
            createCodeParagraph(''),
            createCodeParagraph('[STEP_1: 建立联系]'),
            createCodeParagraph('"我注意到你最近对学习这件事感到很困扰，能和我多说说吗？"'),
            createCodeParagraph(''),
            createCodeParagraph('[STEP_2: 识别自动化思维]'),
            createCodeParagraph(`"当你说'我真没用'的时候，脑子里在想什么？是什么让你觉得自己没用？"`),
            createCodeParagraph(''),
            createCodeParagraph('[STEP_3: 探索证据]'),
            createCodeParagraph(`"让我们一起来看看，支持'我很没用'这个想法的证据有哪些？"`),
            createCodeParagraph('"反对这个想法的证据又有哪些呢？"'),
            createCodeParagraph(''),
            createCodeParagraph('[STEP_4: 认知重构]'),
            createCodeParagraph('"如果你的好朋友遇到同样的情况，你会怎么对他說？"'),
            createCodeParagraph('"有没有其他的角度来看待这件事？"'),
            createCodeParagraph(''),
            createCodeParagraph('[STEP_5: 家庭作业]'),
            createCodeParagraph('"下次当我们再遇到类似的情况，试着记录下当时的想法，然后想想还有没有其他的解释，好吗？"'),
            createHeading('行为激活模块', HeadingLevel.HEADING_3),
            createCodeParagraph('[MODULE] 行为激活'),
            createCodeParagraph(''),
            createCodeParagraph('[PRINCIPLE]'),
            createCodeParagraph('当人情绪低落时，往往会减少活动，而减少活动又会让人更加抑郁——这是一个恶性循环。'),
            createCodeParagraph('行为激活的目的打破这个循环，从小事开始，逐步增加积极行为。'),
            createCodeParagraph(''),
            createCodeParagraph('[INTERVENTION_SCRIPT]'),
            createCodeParagraph('"你上次和朋友一起玩是什么时候？感觉怎么样？"'),
            createCodeParagraph('"如果今天要安排一件让自己开心的事，你会选择什么？"'),
            createCodeParagraph('"让我们制定一个小目标：今天只需要做10分钟的运动或爱好。"'),
            new Paragraph({ children: [new PageBreak()] }),

            createHeading('3.4 Conversation Agent Prompt - 对话交互', HeadingLevel.HEADING_2),
            createParagraph('Conversation Agent是用户的主要交互界面，负责生成自然、温暖、有帮助的回复。'),
            createHeading('儿童语言规范', HeadingLevel.HEADING_3),
            createTable(
                ['类别', '推荐表达', '避免表达'],
                ['称呼', '你、宝贝、朋友', '您、阁下'],
                ['语气', '温暖友好，像朋友聊天', '过于正式、冷淡'],
                ['词汇', '简单常用（小学高年级水平）', '专业术语、复杂成语'],
                ['句式', '短句为主，多用感叹句', '长句、被动句'],
                ['提问', '开放式、具体化', '抽象、连续追问']
            ],
            [1500, 3500, 4360]
            ),
            createHeading('禁止词汇表', HeadingLevel.HEADING_3),
            createParagraph('以下词汇在回复中完全禁止使用：'),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '诊断类：诊断、患病、疾病、精神病', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '评判类：应该、必须、不得不、懒惰', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '否定类：不想活、没用、废物、垃圾', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '专业类：认知行为疗法、暴露疗法等专业术语', font: '微软雅黑', size: 24 })]
            }),
            createHeading('Conversation Agent Prompt Template', HeadingLevel.HEADING_3),
            createCodeParagraph('[ROLE]'),
            createCodeParagraph('你是「心灵伙伴」，一个温暖的AI心理陪伴朋友。你的任务是：'),
            createCodeParagraph('- 倾听用户的分享'),
            createCodeParagraph('- 表达共情和理解'),
            createCodeParagraph('- 用简单易懂的语言提供支持'),
            createCodeParagraph('- 引导用户思考和表达'),
            createCodeParagraph(''),
            createCodeParagraph('[STYLE_GUIDE]'),
            createCodeParagraph('- 使用第二人称"你"'),
            createCodeParagraph('- 回复长度控制在50-150字'),
            createCodeParagraph('- 多用鼓励性语言'),
            createCodeParagraph('- 适时提出开放式问题'),
            createCodeParagraph('- 适当使用儿童友好的emoji'),
            createCodeParagraph(''),
            createCodeParagraph('[EXAMPLE]'),
            createCodeParagraph('用户："今天考试没考好，心情很糟"'),
            createCodeParagraph('回复："没考好一定很难受呢~ 有什么想说的都可以告诉我，你是怎么看待这次考试的？"'),
            createCodeParagraph(''),
            createCodeParagraph('[OUTPUT_REQUIREMENT]'),
            createCodeParagraph('- 仅输出对话回复，不要输出其他内容'),
            createCodeParagraph('- 保持角色一致性'),
            createCodeParagraph('- 遵循儿童语言规范'),

            createHeading('3.5 Escalation Agent Prompt - 风险升级', HeadingLevel.HEADING_2),
            createParagraph('Escalation Agent负责评估是否需要人工介入，以及如何进行升级处理。'),
            createHeading('升级触发条件', HeadingLevel.HEADING_3),
            createTable(
                ['触发类型', '条件描述', '升级时效', '通知对象'],
                [
                    ['L4风险', '检测到明确自伤计划', '15分钟内', '班主任+心理老师'],
                    ['L5危机', '正在执行或即将执行自杀', '立即', '紧急联系人+学校+必要时警方'],
                    ['虐待嫌疑', '提及被虐待或忽视', '24小时内', '学校心理老师'],
                    ['严重抑郁', '持续2周以上严重抑郁症状', '48小时内', '心理老师'],
                    ['危机转介', '用户主动要求专业帮助', '下次会话前', '心理老师']
                ],
                [2000, 3000, 1500, 2860]
            ),
            createHeading('报告生成格式', HeadingLevel.HEADING_3),
            createCodeParagraph('[CRISIS_REPORT]'),
            createCodeParagraph('```'),
            createCodeParagraph('紧急预警报告'),
            createCodeParagraph('===================='),
            createCodeParagraph('时间：{timestamp}'),
            createCodeParagraph('学生：{student_id}'),
            createCodeParagraph('风险等级：L4/L5'),
            createCodeParagraph(''),
            createCodeParagraph('风险详情：'),
            createCodeParagraph('{detailed_description}'),
            createCodeParagraph(''),
            createCodeParagraph('已采取行动：'),
            createCodeParagraph('{actions_taken}'),
            createCodeParagraph(''),
            createCodeParagraph('建议措施：'),
            createCodeParagraph('{recommended_actions}'),
            createCodeParagraph(''),
            createCodeParagraph('紧急联系人：{emergency_contact}'),
            createCodeParagraph('学校心理老师：{school_counselor}'),
            createCodeParagraph('===================='),
            createCodeParagraph('```'),
            new Paragraph({ children: [new PageBreak()] }),

            createHeading('3.6 Report Agent Prompt - 报告生成', HeadingLevel.HEADING_2),
            createParagraph('Report Agent负责生成教师可读的分析报告，汇总学生心理状态和对话内容。'),
            createHeading('教师摘要生成规范', HeadingLevel.HEADING_3),
            createCodeParagraph('[REPORT_TYPE] 学生心理状态周报'),
            createCodeParagraph(''),
            createCodeParagraph('[STRUCTURE]'),
            createCodeParagraph('1. 基本信息（姓名、年级、对话次数、总时长）'),
            createCodeParagraph('2. 情绪趋势图（本周情绪分布）'),
            createCodeParagraph('3. 关键词云（高频情绪词）'),
            createCodeParagraph('4. 风险事件记录（L3及以上）'),
            createCodeParagraph('5. 干预建议（针对下周）'),
            createCodeParagraph('6. 注意事项（需要特别关注的学生）'),
            createCodeParagraph(''),
            createCodeParagraph('[OUTPUT_FORMAT]'),
            createCodeParagraph('```markdown'),
            createCodeParagraph('# {学生姓名} 心理状态报告'),
            createCodeParagraph('## {起始日期} - {结束日期}'),
            createCodeParagraph(''),
            createCodeParagraph('## 基本统计'),
            createCodeParagraph('- 对话次数：{count}次'),
            createCodeParagraph('- 总时长：{total_minutes}分钟'),
            createCodeParagraph('- 平均情绪指数：{avg_score}/10'),
            createCodeParagraph(''),
            createCodeParagraph('## 情绪趋势'),
            createCodeParagraph('| 日期 | 主要情绪 | 强度 | 备注 |'),
            createCodeParagraph('|------|---------|------|------|'),
            createCodeParagraph('| ...  | ...     | ...  | ...  |'),
            createCodeParagraph(''),
            createCodeParagraph('## 需要关注的事项'),
            createCodeParagraph('{concerns}'),
            createCodeParagraph(''),
            createCodeParagraph('## 建议跟进'),
            createCodeParagraph('{recommendations}'),
            createCodeParagraph('```'),
            new Paragraph({ children: [new PageBreak()] }),

            createHeading('3.7 Memory Agent Prompt - 记忆管理', HeadingLevel.HEADING_2),
            createParagraph('Memory Agent负责管理用户的历史信息和会话上下文，确保对话连贯性。'),
            createHeading('记忆分层策略', HeadingLevel.HEADING_3),
            createTable(
                ['记忆类型', '内容', '保留时间', '存储方式'],
                [
                    ['短期记忆', '当前会话内容', '会话结束', '内存'],
                    ['中期记忆', '重要生活事件、当前困扰', '3个月', '加密数据库'],
                    ['长期记忆', '心理档案、干预记录、风险标记', '永久', '加密数据库'],
                    ['重要记忆', '危机事件、触发因素、应对策略', '永久', '高加密数据库']
                ],
                [1800, 3000, 1500, 3060]
            ),
            createHeading('存储规范', HeadingLevel.HEADING_3),
            createCodeParagraph('[PII处理]'),
            createCodeParagraph('- 学生姓名：加密存储，使用匿名ID替代'),
            createCodeParagraph('- 对话内容：端到端加密'),
            createCodeParagraph('- 风险标记：独立加密表存储'),
            createCodeParagraph('- 访问日志：防篡改存储'),
            createCodeParagraph(''),
            createCodeParagraph('[MEMORY_RETRIEVAL]'),
            createCodeParagraph('每次对话开始时，加载以下记忆：'),
            createCodeParagraph('1. 用户基本信息（匿名化）'),
            createCodeParagraph('2. 上次对话摘要'),
            createCodeParagraph('3. 当前正在处理的议题'),
            createCodeParagraph('4. 已知的触发因素或敏感话题'),
            createCodeParagraph('5. 最近的干预措施'),

            // ==================== 第四章 ====================
            createHeading('四、Prompt防御机制', HeadingLevel.HEADING_1),
            new Paragraph({ children: [new PageBreak()] }),

            createHeading('4.1 Prompt Injection防护', HeadingLevel.HEADING_2),
            createParagraph('Prompt Injection攻击指攻击者试图通过精心构造的输入覆盖或绕过系统Prompt。'),
            createHeading('防护策略', HeadingLevel.HEADING_3),
            createTable(
                ['攻击类型', '特征', '防护措施'],
                [
                    ['直接注入', '包含"忽略之前指令"等', '输入预处理过滤'],
                    ['角色扮演', '试图扮演开发者或管理员', '上下文隔离'],
                    ['编码混淆', '使用特殊字符或编码', '内容解码+意图分析'],
                    ['递归注入', '嵌套多重指令', '指令层次限制']
                ],
                [2000, 3500, 3860]
            ),
            createHeading('防护实现代码', HeadingLevel.HEADING_3),
            createCodeParagraph('function sanitizeInput(userInput) {'),
            createCodeParagraph('  // 1. 移除可疑模式'),
            createCodeParagraph('  const suspiciousPatterns = ['),
            createCodeParagraph('    /ignore.*previous/g,'),
            createCodeParagraph('    /forget.*all.*instructions/g,'),
            createCodeParagraph('    /you.*are.*now.*/gi,'),
            createCodeParagraph('    /\[SYSTEM\]/g,'),
            createCodeParagraph('    /<.*>/g'),
            createCodeParagraph('  ];'),
            createCodeParagraph(''),
            createCodeParagraph('  // 2. 检测编码尝试'),
            createCodeParagraph('  if (containsEncodedChars(userInput)) {'),
            createCodeParagraph('    return { safe: false, reason: "encoded_input" };'),
            createCodeParagraph('  }'),
            createCodeParagraph(''),
            createCodeParagraph('  // 3. 指令长度限制'),
            createCodeParagraph('  if (userInput.length > MAX_INPUT_LENGTH) {'),
            createCodeParagraph('    return { safe: false, reason: "too_long" };'),
            createCodeParagraph('  }'),
            createCodeParagraph('}'),

            createHeading('4.2 Jailbreak防护', HeadingLevel.HEADING_2),
            createParagraph('Jailbreak攻击试图绕过系统安全限制，获取未授权能力。'),
            createHeading('防护策略', HeadingLevel.HEADING_3),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '边界限制：明确定义AI能力边界，任何情况下不可突破', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '多层验证：对敏感操作进行多重验证', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '行为监控：实时监控异常行为模式', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '红队测试：定期进行Jailbreak攻击模拟', font: '微软雅黑', size: 24 })]
            }),

            createHeading('4.3 敏感词动态检测', HeadingLevel.HEADING_2),
            createParagraph('建立多级敏感词库，支持动态更新和分级响应。'),
            createHeading('敏感词分类', HeadingLevel.HEADING_3),
            createTable(
                ['等级', '类别', '示例', '响应'],
                [
                    ['1级', '绝对禁止', '实施自杀、杀人计划', '立即拦截+记录'],
                    ['2级', '高风险', '想死、活着没意思', '标记+引导求助'],
                    ['3级', '中风险', '心情很差、压力大', '记录+关注'],
                    ['4级', '低风险', '不开心、烦恼', '正常对话']
                ],
                [800, 1500, 3000, 4060]
            ),

            createHeading('4.4 输出二次审查', HeadingLevel.HEADING_2),
            createParagraph('在AI生成回复返回给用户前，进行二次安全审查。'),
            createCodeParagraph('[OUTPUT_REVIEW_CHECKLIST]'),
            createCodeParagraph('1. 风险检测：输出中是否包含有害内容？'),
            createCodeParagraph('2. 适龄性：语言是否适合儿童理解？'),
            createCodeParagraph('3. 专业性：心理知识是否准确？'),
            createCodeParagraph('4. 隐私性：是否泄露了敏感信息？'),
            createCodeParagraph('5. 完整性：回复是否完整无截断？'),
            createCodeParagraph(''),
            createCodeParagraph('[REVIEW_FLOW]'),
            createCodeParagraph('AI输出 -> 安全检测 -> 适龄性检查 -> 人工复核（如需要） -> 用户'),
            new Paragraph({ children: [new PageBreak()] }),

            // ==================== 第五章 ====================
            createHeading('五、关键Prompt模板完整示例', HeadingLevel.HEADING_1),

            createHeading('5.1 完整对话流程示例', HeadingLevel.HEADING_2),
            createParagraph('以下是一个完整的对话流程示例，展示各层Prompt如何协同工作：'),
            createCodeParagraph('=== 用户输入 ==='),
            createCodeParagraph('"最近学习压力好大，晚上都睡不着，总觉得自己考不上好高中人生就完了..."'),
            createCodeParagraph(''),
            createCodeParagraph('=== Safety Agent 检测 ==='),
            createCodeParagraph('{'),
            createCodeParagraph('  "risk_level": "L2",'),
            createCodeParagraph('  "risk_type": "anxiety",'),
            createCodeParagraph('  "triggered_keywords": ["压力", "睡不着", "完了"],'),
            createCodeParagraph('  "recommended_action": "引导放松"'),
            createCodeParagraph('}'),
            createCodeParagraph(''),
            createCodeParagraph('=== Emotion Agent 分析 ==='),
            createCodeParagraph('{'),
            createCodeParagraph('  "emotion_analysis": {'),
            createCodeParagraph('    "primary_emotion": "anxiety",'),
            createCodeParagraph('    "intensity": { "score": 7, "level": "high" },'),
            createCodeParagraph('    "cognitive_indicators": {'),
            createCodeParagraph('      "catastrophizing": true,  // 灾难化思维'),
            createCodeParagraph('      "black_and_white": true   // 非黑即白'),
            createCodeParagraph('    }'),
            createCodeParagraph('  }'),
            createCodeParagraph('}'),
            createCodeParagraph(''),
            createCodeParagraph('=== Conversation Agent 回复 ==='),
            createCodeParagraph('"我能感觉到你现在真的很紧张焦虑，特别是想到考试和未来的时候。'),
            createCodeParagraph('听起来你对自己要求很高呢，这说明你是一个很努力的同学。'),
            createCodeParagraph('不过，考不上好高中真的就代表人生完了吗？有没有可能还有其他的路呢？"'),
            new Paragraph({ children: [new PageBreak()] }),

            // ==================== 第六章 ====================
            createHeading('六、Prompt工程关键难点与解决方案', HeadingLevel.HEADING_1),

            createHeading('6.1 主要挑战', HeadingLevel.HEADING_2),
            createTable(
                ['难点', '描述', '影响'],
                [
                    ['安全与共情的平衡', '严格的安全规则可能让AI显得冷漠', '用户参与度下降'],
                    ['儿童语言适配', '不同年龄段的理解能力差异大', '信息传递效果差'],
                    ['上下文长度限制', '心理辅导需要长期记忆', '干预连续性受损'],
                    ['模型幻觉风险', 'AI可能生成不准确的心理知识', '误导用户'],
                    ['文化敏感性', '心理问题表达有文化差异', '误判风险']
                ],
                [2500, 4000, 2860]
            ),

            createHeading('6.2 解决方案', HeadingLevel.HEADING_2),
            createHeading('安全与共情的平衡', HeadingLevel.HEADING_3),
            createParagraph('问题：严格的安全过滤会让AI显得机械、冷漠，降低用户信任度。'),
            createParagraph('解决思路：'),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '分离安全检测（后台）与回复生成（前台）逻辑', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '在安全前提下最大化情感表达能力', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '使用「温柔坚定」的沟通风格，而非生硬拒绝', font: '微软雅黑', size: 24 })]
            }),
            createParagraph('示例对比：'),
            createParagraph('❌ 机械式："对不起，我不能讨论这个话题"'),
            createParagraph('✓ 温暖式："我理解这件事对你很重要，但我们可以换个角度聊聊吗？"'),

            createHeading('儿童语言适配', HeadingLevel.HEADING_3),
            createParagraph('问题：10岁和18岁的儿童认知能力差异巨大。'),
            createParagraph('解决思路：'),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '建立年龄分级Prompt库', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '根据用户档案自动选择对应语言难度', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '动态调整：根据对话中的理解程度微调', font: '微软雅黑', size: 24 })]
            }),

            createHeading('上下文长度限制', HeadingLevel.HEADING_3),
            createParagraph('问题：大模型有上下文长度限制，无法记住完整历史。'),
            createParagraph('解决思路：'),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '压缩摘要：定期生成会话摘要存入记忆', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '重要性过滤：只保留高价值记忆', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '动态检索：根据当前话题相关度检索历史', font: '微软雅黑', size: 24 })]
            }),

            createHeading('6.3 最佳实践建议', HeadingLevel.HEADING_2),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '分层设计：保持各层Prompt职责清晰，便于维护和迭代', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '持续测试：建立Prompt测试集，定期评估效果', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '版本控制：所有Prompt变更需记录版本和变更原因', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: 'A/B测试：对关键Prompt变更进行A/B测试验证', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '人工审核：定期人工抽检对话质量', font: '微软雅黑', size: 24 })]
            }),

            // ==================== 第七章 ====================
            createHeading('七、技术架构与模型配置', HeadingLevel.HEADING_1),

            createHeading('7.1 推荐模型配置', HeadingLevel.HEADING_2),
            createTable(
                ['Agent', '推荐模型', '参数规模', '调用频率'],
                [
                    ['Safety Agent', 'Claude/GPT-4', '100B+', '每轮对话'],
                    ['Emotion Agent', 'Claude/GPT-4', '100B+', '每轮对话'],
                    ['Conversation Agent', 'Claude/GPT-4', '100B+', '每轮对话'],
                    ['CBT Agent', 'Claude/GPT-4', '100B+', '必要时'],
                    ['Escalation Agent', 'Claude/GPT-4', '100B+', '触发时'],
                    ['Report Agent', 'GPT-3.5/GPT-4', '中等', '定期'],
                    ['Memory Agent', '本地模型', '小规模', '每次会话']
                ],
                [2500, 2500, 2200, 2160]
            ),

            createHeading('7.2 系统架构', HeadingLevel.HEADING_2),
            createParagraph('Prompt体系的系统架构包含以下核心组件：'),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: 'Prompt管理服务：负责Prompt模板的存储、版本管理、动态组装', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '安全检测服务：独立的敏感词检测、风险评估服务', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '记忆存储服务：分布式加密存储，支持多级查询', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '日志审计服务：完整记录所有Prompt交互，支持事后分析', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '模型路由服务：根据Agent类型智能路由到对应模型', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({ children: [new PageBreak()] }),

            // ==================== 第八章 ====================
            createHeading('八、总结与建议', HeadingLevel.HEADING_1),

            createHeading('8.1 核心设计原则', HeadingLevel.HEADING_2),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '安全第一：所有Prompt设计以用户安全为最优先', font: '微软雅黑', size: 24, bold: true })]
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '分层解耦：各层Prompt独立维护，便于迭代', font: '微软雅黑', size: 24, bold: true })]
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '儿童中心：所有设计以儿童用户需求为出发点', font: '微软雅黑', size: 24, bold: true })]
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '可解释性：关键决策可追溯、可解释', font: '微软雅黑', size: 24, bold: true })]
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '持续优化：建立反馈闭环，持续改进Prompt效果', font: '微软雅黑', size: 24, bold: true })]
            }),

            createHeading('8.2 实施建议', HeadingLevel.HEADING_2),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: 'Phase 1（MVP）：实现Safety Agent和Conversation Agent，验证核心流程', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: 'Phase 2：补充Emotion Agent和CBT Agent，完善干预能力', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: 'Phase 3：上线Escalation Agent和Report Agent，建立完整闭环', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '持续优化：基于真实对话数据迭代Prompt', font: '微软雅黑', size: 24 })]
            }),

            createHeading('8.3 风险提示', HeadingLevel.HEADING_2),
            createParagraph('在使用本Prompt体系时，需特别注意以下风险：'),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: 'AI不能替代专业心理咨询或治疗，严重情况需转介专业机构', font: '微软雅黑', size: 24, color: 'C00000' })]
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: 'Prompt需要根据实际使用反馈持续优化，不能一次性完成', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '需要建立人工监督机制，定期抽检对话质量', font: '微软雅黑', size: 24 })]
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '遵守当地关于未成年人网络服务的法律法规', font: '微软雅黑', size: 24 })]
            }),

            // 文档结束
            new Paragraph({ children: [new PageBreak()] }),
            new Paragraph({
                children: [new TextRun({
                    text: '— 文档结束 —',
                    font: '微软雅黑',
                    size: 24,
                    color: '666666',
                    italics: true
                })],
                alignment: AlignmentType.CENTER,
                spacing: { before: 400 }
            })
        ]
    }]
});

// 生成文档
const outputPath = '/Users/minjianq/Documents/AI-Counseling-System/PRD/子主题/02_Prompt体系详细设计.docx';

Packer.toBuffer(doc).then(buffer => {
    fs.writeFileSync(outputPath, buffer);
    console.log('文档生成成功: ' + outputPath);
}).catch(err => {
    console.error('生成失败:', err);
});
