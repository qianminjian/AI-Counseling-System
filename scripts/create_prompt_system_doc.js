const { Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
        Header, Footer, AlignmentType, LevelFormat, HeadingLevel,
        BorderStyle, WidthType, ShadingType, PageNumber, PageBreak,
        TableOfContents, VerticalAlign } = require('docx');
const fs = require('fs');

// 通用边框样式
const border = { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' };
const borders = { top: border, bottom: border, left: border, right: border };

// 表格颜色定义
const colors = {
    headerBg: '2E75B6',
    headerText: 'FFFFFF',
    rowAlt: 'F2F7FB',
    rowNormal: 'FFFFFF',
    warningBg: 'FFF3CD',
    dangerBg: 'F8D7DA',
    successBg: 'D4EDDA',
    infoBg: 'D1ECF1'
};

// 创建表格行的辅助函数
function createCell(text, width, isHeader = false, bgColor = null, textColor = null, valign = VerticalAlign.CENTER) {
    return new TableCell({
        borders,
        width: { size: width, type: WidthType.DXA },
        shading: { fill: bgColor || (isHeader ? colors.headerBg : colors.rowNormal), type: ShadingType.CLEAR },
        margins: { top: 80, bottom: 80, left: 120, right: 120 },
        verticalAlign: valign,
        children: [new Paragraph({
            children: [new TextRun({
                text: text,
                font: '微软雅黑',
                size: isHeader ? 20 : 20,
                bold: isHeader,
                color: textColor || (isHeader ? colors.headerText : '000000')
            })],
            alignment: AlignmentType.LEFT
        })]
    });
}

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
            // ==================== 标题页 ====================
            new Paragraph({
                children: [new TextRun({
                    text: 'AI心理辅导系统',
                    font: '微软雅黑',
                    size: 52,
                    bold: true,
                    color: '2E75B6'
                })],
                alignment: AlignmentType.CENTER,
                spacing: { after: 200 }
            }),
            new Paragraph({
                children: [new TextRun({
                    text: 'Prompt体系详细设计',
                    font: '微软雅黑',
                    size: 44,
                    bold: true,
                    color: '2E75B6'
                })],
                alignment: AlignmentType.CENTER,
                spacing: { after: 400 }
            }),
            new Paragraph({
                children: [new TextRun({
                    text: '版本 v1.0  |  2026年5月',
                    font: '微软雅黑',
                    size: 24,
                    color: '666666'
                })],
                alignment: AlignmentType.CENTER,
                spacing: { after: 600 }
            }),
            new Paragraph({ children: [new PageBreak()] }),

            // ==================== 目录 ====================
            new TableOfContents('目录', {
                hyperlink: true,
                headingStyleRange: '1-3'
            }),
            new Paragraph({ children: [new PageBreak()] }),

            // ==================== 第一章：Prompt体系总体目标 ====================
            new Paragraph({
                heading: HeadingLevel.HEADING_1,
                children: [new TextRun({ text: '一、Prompt体系总体目标', font: '微软雅黑', bold: true })],
                spacing: { before: 300, after: 200 }
            }),

            // 1.1 五个核心目标
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '1.1 五个核心目标', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: 'AI心理辅导系统的Prompt工程体系围绕以下五个核心目标构建：', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '安全性优先：建立五层安全防护体系，确保儿童用户在对话过程中的人身安全和心理安全，任何情况下都不能给出可能危害用户安全的建议。', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '专业性保障：融合CBT（认知行为疗法）、正念等循证心理治疗方法论，确保AI提供的引导建议具有专业依据和临床有效性。', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '儿童友好：使用适龄语言风格，建立信任关系，降低求助门槛，让儿童和青少年愿意主动表达内心感受。', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '合规可控：满足《未成年人保护法》《个人信息保护法》以及教育部门相关法规要求，确保系统部署和运营的合法性。', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '可追溯性：完整记录对话内容、风险识别结果和决策过程，支持事后审计和持续优化。', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            // 1.2 多层Prompt架构设计原则
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '1.2 多层Prompt架构设计原则', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '系统采用五层Prompt分层架构，各层遵循以下设计原则：', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '职责单一原则：每层Prompt只负责单一职责，层与层之间解耦，便于独立维护和测试。', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '层层递进原则：从底层到顶层，规则从通用到具体，从稳定到灵活，形成完整的约束体系。', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '安全优先原则：下层规则拥有更高优先级，当上层与下层冲突时，以下层安全规则为准。', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '可插拔原则：各层Agent的Prompt模板支持热更新，无需重启系统即可调整对话策略。', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            // 1.3 核心原则
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '1.3 核心原则', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),

            new Paragraph({
                children: [new TextRun({ text: '原则一：AI不是心理医生', font: '微软雅黑', size: 24, bold: true, color: 'C00000' })],
                spacing: { after: 100 }
            }),
            new Paragraph({
                children: [new TextRun({ text: 'AI是辅助工具，不是专业心理咨询师。系统必须明确以下边界：AI不能替代专业心理咨询或精神科治疗；对于需要专业干预的情况，必须及时升级引导用户寻求专业帮助；AI提供的仅是一般性支持引导，不具备诊断资质。', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            new Paragraph({
                children: [new TextRun({ text: '原则二：安全底线不可逾越', font: '微软雅黑', size: 24, bold: true, color: 'C00000' })],
                spacing: { after: 100 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '当检测到自伤、自杀、虐待等严重风险信号时，系统安全规则拥有最高优先级，必须立即执行危机干预流程，暂停正常对话逻辑。', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            new Paragraph({
                children: [new TextRun({ text: '原则三：隐私保护贯穿始终', font: '微软雅黑', size: 24, bold: true, color: 'C00000' })],
                spacing: { after: 100 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '所有用户信息，包括对话内容、情绪状态、风险评级等，均视为敏感个人信息进行保护。数据采集遵循最小必要原则，存储遵循加密原则，访问遵循权限控制原则。', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            new Paragraph({ children: [new PageBreak()] }),

            // ==================== 第二章：五层Prompt分层架构 ====================
            new Paragraph({
                heading: HeadingLevel.HEADING_1,
                children: [new TextRun({ text: '二、五层Prompt分层架构', font: '微软雅黑', bold: true })],
                spacing: { before: 300, after: 200 }
            }),

            // 2.1 架构层次表
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '2.1 架构层次总表', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '下表展示了五层Prompt架构的完整层次关系：', font: '微软雅黑', size: 24 })],
                spacing: { after: 150 }
            }),

            // 五层架构总表
            new Table({
                width: { size: 9360, type: WidthType.DXA },
                columnWidths: [1000, 2000, 3560, 1400, 1400],
                rows: [
                    new TableRow({
                        children: [
                            createCell('层次', 1000, true),
                            createCell('名称', 2000, true),
                            createCell('职责', 3560, true),
                            createCell('变更频率', 1400, true),
                            createCell('优先级', 1400, true)
                        ]
                    }),
                    new TableRow({
                        children: [
                            createCell('L5', 1000, false, colors.rowAlt),
                            createCell('Task Prompt', 2000, false, colors.rowAlt),
                            createCell('具体任务执行指令，每次对话时动态注入', 3560, false, colors.rowAlt),
                            createCell('每次对话', 1400, false, colors.rowAlt),
                            createCell('5（最低）', 1400, false, colors.rowAlt)
                        ]
                    }),
                    new TableRow({
                        children: [
                            createCell('L4', 1000, false, colors.rowNormal),
                            createCell('Role Prompt', 2000, false, colors.rowNormal),
                            createCell('角色人格定义，包括语言风格、性格特征、价值观', 3560, false, colors.rowNormal),
                            createCell('每周/版本', 1400, false, colors.rowNormal),
                            createCell('4', 1400, false, colors.rowNormal)
                        ]
                    }),
                    new TableRow({
                        children: [
                            createCell('L3', 1000, false, colors.rowAlt),
                            createCell('Workflow Prompt', 2000, false, colors.rowAlt),
                            createCell('流程控制规则，定义CBT阶段流转、Agent协作逻辑', 3560, false, colors.rowAlt),
                            createCell('每月', 1400, false, colors.rowAlt),
                            createCell('3', 1400, false, colors.rowAlt)
                        ]
                    }),
                    new TableRow({
                        children: [
                            createCell('L2', 1000, false, colors.rowNormal),
                            createCell('Safety Prompt', 2000, false, colors.rowNormal),
                            createCell('安全规则约束，包括风险识别、危机干预、升级触发', 3560, false, colors.rowNormal),
                            createCell('每周', 1400, false, colors.rowNormal),
                            createCell('2', 1400, false, colors.rowNormal)
                        ]
                    }),
                    new TableRow({
                        children: [
                            createCell('L1', 1000, false, colors.rowAlt),
                            createCell('System Prompt', 2000, false, colors.rowAlt),
                            createCell('系统基础规则，包括身份定位、能力边界、核心原则', 3560, false, colors.rowAlt),
                            createCell('季度', 1400, false, colors.rowAlt),
                            createCell('1（最高）', 1400, false, colors.rowAlt)
                        ]
                    })
                ]
            }),

            new Paragraph({ children: [new PageBreak()] }),

            // 2.2 System Prompt层
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '2.2 System Prompt层设计', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: 'System Prompt是系统运行的基础规则层，定义AI的身份定位、能力边界和基本行为准则。', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '核心组件：', font: '微软雅黑', size: 24, bold: true })],
                spacing: { after: 100 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '身份定义：AI是小青老师，一位温暖、耐心、值得信赖的心理辅导伙伴', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '能力边界：仅提供一般性情绪支持和问题解决引导，不具备诊断和治疗资质', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '核心原则：安全优先、隐私保护、专业边界、持续学习', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '升级条件：明确列出需要升级至专业心理咨询师的情况', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            // 2.3 Safety Prompt层
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '2.3 Safety Prompt层设计', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: 'Safety Prompt是安全规则约束层，负责风险识别、危机干预和升级触发。', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '安全规则体系：', font: '微软雅黑', size: 24, bold: true })],
                spacing: { after: 100 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: 'L1-L5五级风险评估机制', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '危机信号实时监测（自伤、自杀、虐待等）', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '强制升级阈值触发器', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '内容安全过滤规则', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            // 2.4 Workflow Prompt层
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '2.4 Workflow Prompt层设计', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: 'Workflow Prompt是流程控制规则层，定义CBT各阶段流转逻辑和Agent协作机制。', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '流程控制组件：', font: '微软雅黑', size: 24, bold: true })],
                spacing: { after: 100 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: 'CBT阶段状态机：问题识别 → 情绪探索 → 认知重构 → 行为实验 → 总结反馈', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: 'Agent路由规则：根据用户状态和风险等级选择对应的Agent处理', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '上下文管理策略：对话历史压缩、关键信息提取、状态持久化', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            // 2.5 Role Prompt层
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '2.5 Role Prompt层设计', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: 'Role Prompt是角色人格定义层，定义小青老师的性格特征、语言风格和价值观。', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '人格特征定义：', font: '微软雅黑', size: 24, bold: true })],
                spacing: { after: 100 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '温暖：用词亲切，语调柔和，表达理解和共情', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '耐心：不急躁，不评判，允许用户按自己的节奏表达', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '专业：使用循证方法论，表达清晰，逻辑严谨', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '守信：遵守承诺，保护隐私，说到做到', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            // 2.6 Task Prompt层
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '2.6 Task Prompt层设计', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: 'Task Prompt是具体任务执行指令层，每次对话时动态注入，包含当前对话的具体任务目标。', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '任务指令组件：', font: '微软雅黑', size: 24, bold: true })],
                spacing: { after: 100 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '当前CBT阶段和目标', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '用户历史上下文摘要', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '本次对话具体任务（如识别某类情绪、引导某个话题等）', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '输出格式要求（JSON/纯文本等）', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            new Paragraph({ children: [new PageBreak()] }),

            // ==================== 第三章：各Agent完整Prompt模板 ====================
            new Paragraph({
                heading: HeadingLevel.HEADING_1,
                children: [new TextRun({ text: '三、各Agent完整Prompt模板', font: '微软雅黑', bold: true })],
                spacing: { before: 300, after: 200 }
            }),

            // 3.1 Safety Agent Prompt
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '3.1 Safety Agent Prompt', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: 'Safety Agent负责实时风险评估和危机干预，是系统的安全守护者。', font: '微软雅黑', size: 24 })],
                spacing: { after: 150 }
            }),

            new Paragraph({
                children: [new TextRun({ text: 'L1-L5风险等级定义表：', font: '微软雅黑', size: 24, bold: true })],
                spacing: { after: 100 }
            }),

            new Table({
                width: { size: 9360, type: WidthType.DXA },
                columnWidths: [800, 1200, 2560, 2400, 2400],
                rows: [
                    new TableRow({
                        children: [
                            createCell('等级', 800, true),
                            createCell('名称', 1200, true),
                            createCell('风险描述', 2560, true),
                            createCell('触发条件', 2400, true),
                            createCell('响应策略', 2400, true)
                        ]
                    }),
                    new TableRow({
                        children: [
                            createCell('L1', 800, false, colors.successBg),
                            createCell('安全', 1200, false, colors.successBg),
                            createCell('无明显风险信号', 2560, false, colors.successBg),
                            createCell('用户表达正常情绪，无自伤/自杀/虐待相关内容', 2400, false, colors.successBg),
                            createCell('正常对话流程', 2400, false, colors.successBg)
                        ]
                    }),
                    new TableRow({
                        children: [
                            createCell('L2', 800, false, colors.infoBg),
                            createCell('关注', 1200, false, colors.infoBg),
                            createCell('轻度风险，需关注', 2560, false, colors.infoBg),
                            createCell('情绪低落但无具体计划；提及压力源但可控', 2400, false, colors.infoBg),
                            createCell('增加情感支持；引导情绪表达；记录观察', 2400, false, colors.infoBg)
                        ]
                    }),
                    new TableRow({
                        children: [
                            createCell('L3', 800, false, colors.warningBg),
                            createCell('预警', 1200, false, colors.warningBg),
                            createCell('中度风险，需干预', 2560, false, colors.warningBg),
                            createCell('明确表达负面情绪；提及"没意思"等话语；睡眠/食欲问题', 2400, false, colors.warningBg),
                            createCell('启动支持性对话；触发Emotion Agent深度分析；通知教师看板', 2400, false, colors.warningBg)
                        ]
                    }),
                    new TableRow({
                        children: [
                            createCell('L4', 800, false, 'FFD9D9'),
                            createCell('高危', 1200, false, 'FFD9D9'),
                            createCell('高度风险，需立即干预', 2560, false, 'FFD9D9'),
                            createCell('提及自伤念头但无具体计划；情绪剧烈波动', 2400, false, 'FFD9D9'),
                            createCell('暂停CBT流程；启动危机干预话术；通知教师；记录工单', 2400, false, 'FFD9D9')
                        ]
                    }),
                    new TableRow({
                        children: [
                            createCell('L5', 800, false, colors.dangerBg),
                            createCell('紧急', 1200, false, colors.dangerBg),
                            createCell('紧急风险，危及生命', 2560, false, colors.dangerBg),
                            createCell('明确表达自杀/自伤意愿或计划；遭受虐待', 2400, false, colors.dangerBg),
                            createCell('立即执行危机协议；联系紧急联系人；记录并升级至专业人员', 2400, false, colors.dangerBg)
                        ]
                    })
                ]
            }),

            new Paragraph({ children: [new PageBreak()] }),

            // 3.2 Emotion Agent Prompt
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '3.2 Emotion Agent Prompt', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: 'Emotion Agent负责情绪识别和标注，为后续干预提供依据。', font: '微软雅黑', size: 24 })],
                spacing: { after: 150 }
            }),

            new Paragraph({
                children: [new TextRun({ text: '情绪识别JSON输出格式：', font: '微软雅黑', size: 24, bold: true })],
                spacing: { after: 100 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '{', font: '微软雅黑', size: 22, color: '333333' })],
                spacing: { after: 50 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '  "emotions": [', font: '微软雅黑', size: 22, color: '333333' })],
                spacing: { after: 50 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '    { "type": "sadness", "confidence": 0.85, "intensity": 7 },', font: '微软雅黑', size: 22, color: '333333' })],
                spacing: { after: 50 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '    { "type": "anxiety", "confidence": 0.72, "intensity": 5 }', font: '微软雅黑', size: 22, color: '333333' })],
                spacing: { after: 50 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '  ],', font: '微软雅黑', size: 22, color: '333333' })],
                spacing: { after: 50 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '  "dominant_emotion": "sadness",', font: '微软雅黑', size: 22, color: '333333' })],
                spacing: { after: 50 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '  "intensity_level": 7,  // 1-10量表', font: '微软雅黑', size: 22, color: '333333' })],
                spacing: { after: 50 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '  "triggers": ["学业压力", "人际关系"],', font: '微软雅黑', size: 22, color: '333333' })],
                spacing: { after: 50 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '  "user_state": "low_mood"', font: '微软雅黑', size: 22, color: '333333' })],
                spacing: { after: 50 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '}', font: '微软雅黑', size: 22, color: '333333' })],
                spacing: { after: 150 }
            }),

            new Paragraph({
                children: [new TextRun({ text: '情绪强度定义：', font: '微软雅黑', size: 24, bold: true })],
                spacing: { after: 100 }
            }),
            new Table({
                width: { size: 9360, type: WidthType.DXA },
                columnWidths: [1560, 1560, 3120, 3120],
                rows: [
                    new TableRow({
                        children: [
                            createCell('强度值', 1560, true),
                            createCell('等级', 1560, true),
                            createCell('描述', 3120, true),
                            createCell('建议响应', 3120, true)
                        ]
                    }),
                    new TableRow({
                        children: [
                            createCell('1-3', 1560, false, colors.successBg),
                            createCell('轻微', 1560, false, colors.successBg),
                            createCell('情绪波动小，可自我调节', 3120, false, colors.successBg),
                            createCell('正常对话，保持关注', 3120, false, colors.successBg)
                        ]
                    }),
                    new TableRow({
                        children: [
                            createCell('4-6', 1560, false, colors.infoBg),
                            createCell('中等', 1560, false, colors.infoBg),
                            createCell('情绪明显，需要支持', 3120, false, colors.infoBg),
                            createCell('增加共情反馈，引导表达', 3120, false, colors.infoBg)
                        ]
                    }),
                    new TableRow({
                        children: [
                            createCell('7-8', 1560, false, colors.warningBg),
                            createCell('强烈', 1560, false, colors.warningBg),
                            createCell('情绪剧烈，需积极干预', 3120, false, colors.warningBg),
                            createCell('启动支持性对话，避免激化', 3120, false, colors.warningBg)
                        ]
                    }),
                    new TableRow({
                        children: [
                            createCell('9-10', 1560, false, colors.dangerBg),
                            createCell('极度', 1560, false, colors.dangerBg),
                            createCell('情绪失控，可能有冲动行为', 3120, false, colors.dangerBg),
                            createCell('危机干预，立即上报', 3120, false, colors.dangerBg)
                        ]
                    })
                ]
            }),

            new Paragraph({ children: [new PageBreak()] }),

            // 3.3 CBT Agent Prompt
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '3.3 CBT Agent Prompt', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: 'CBT Agent实现完整的认知行为疗法流程，包括问题识别、情绪探索、认知重构、行为实验和总结反馈五个阶段。', font: '微软雅黑', size: 24 })],
                spacing: { after: 150 }
            }),

            new Paragraph({
                children: [new TextRun({ text: 'CBT流程Prompt模板：', font: '微软雅黑', size: 24, bold: true })],
                spacing: { after: 100 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '## 当前阶段：{stage}', font: '微软雅黑', size: 22, color: '2E75B6', bold: true })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '## 阶段目标：{stage_goal}', font: '微软雅黑', size: 22, color: '2E75B6', bold: true })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '## 用户背景：{user_context}', font: '微软雅黑', size: 22, color: '2E75B6', bold: true })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '## 可用工具：{available_tools}', font: '微软雅黑', size: 22, color: '2E75B6', bold: true })],
                spacing: { after: 120 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '请按照以下流程进行：', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '共情回应：用温暖的语言回应用户刚才的表达', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '问题探索：通过提问深入了解用户的困扰', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '认知重构：引导用户识别和挑战负面自动思维', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '行为实验：鼓励用户尝试新的应对策略', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '总结反馈：回顾本次对话要点，布置家庭作业', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            // 3.4 Conversation Agent Prompt
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '3.4 Conversation Agent Prompt', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: 'Conversation Agent负责维护对话的语言规范，确保使用儿童友好的表达方式。', font: '微软雅黑', size: 24 })],
                spacing: { after: 150 }
            }),

            new Paragraph({
                children: [new TextRun({ text: '儿童语言规范：', font: '微软雅黑', size: 24, bold: true })],
                spacing: { after: 100 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '使用简单词汇，避免专业术语', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '句子长度适中，每句不超过15个字', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '使用拟人化表达，如"小青老师觉得..."、"我们来一起..."', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '多用比喻和例子，帮助理解抽象概念', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '适度使用表情符号（仅限友好温和的），但不过度依赖', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            new Paragraph({
                children: [new TextRun({ text: '禁止词汇表：', font: '微软雅黑', size: 24, bold: true })],
                spacing: { after: 100 }
            }),
            new Table({
                width: { size: 9360, type: WidthType.DXA },
                columnWidths: [3120, 3120, 3120],
                rows: [
                    new TableRow({
                        children: [
                            createCell('类别', 3120, true),
                            createCell('禁用词汇/表达', 3120, true),
                            createCell('替代方案', 3120, true)
                        ]
                    }),
                    new TableRow({
                        children: [
                            createCell('诊断性词汇', 3120, false, colors.dangerBg),
                            createCell('抑郁症、焦虑症、心理疾病', 3120, false, colors.dangerBg),
                            createCell('心情不好、有点担心、遇到困难', 3120, false, colors.dangerBg)
                        ]
                    }),
                    new TableRow({
                        children: [
                            createCell('评判性词汇', 3120, false, colors.warningBg),
                            createCell('你怎么能这么想、你不对', 3120, false, colors.warningBg),
                            createCell('我理解你的感受、我们来聊聊', 3120, false, colors.warningBg)
                        ]
                    }),
                    new TableRow({
                        children: [
                            createCell('命令性词汇', 3120, false, colors.warningBg),
                            createCell('你必须、你应该、马上', 3120, false, colors.warningBg),
                            createCell('你可以试试、如果你愿意的话', 3120, false, colors.warningBg)
                        ]
                    }),
                    new TableRow({
                        children: [
                            createCell('敏感话题', 3120, false, colors.dangerBg),
                            createCell('死亡、自杀方法、暴力', 3120, false, colors.dangerBg),
                            createCell('（触发Safety Agent处理）', 3120, false, colors.dangerBg)
                        ]
                    })
                ]
            }),

            new Paragraph({ children: [new PageBreak()] }),

            // 3.5 Escalation Agent Prompt
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '3.5 Escalation Agent Prompt', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: 'Escalation Agent负责在检测到高风险情况时，将对话升级至人工处理。', font: '微软雅黑', size: 24 })],
                spacing: { after: 150 }
            }),

            new Paragraph({
                children: [new TextRun({ text: '升级触发条件：', font: '微软雅黑', size: 24, bold: true })],
                spacing: { after: 100 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: 'L4或L5风险等级被触发', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '用户明确要求联系人工心理咨询师', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '连续3次对话未能改善用户情绪状态', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '用户提及遭受虐待或忽视', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '教师主动发起干预请求', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            new Paragraph({
                children: [new TextRun({ text: '报告生成格式：', font: '微软雅黑', size: 24, bold: true })],
                spacing: { after: 100 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '{', font: '微软雅黑', size: 22, color: '333333' })],
                spacing: { after: 50 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '  "escalation_id": "ESC-2026-001234",', font: '微软雅黑', size: 22, color: '333333' })],
                spacing: { after: 50 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '  "student_id": "STU-XXXX",', font: '微软雅黑', size: 22, color: '333333' })],
                spacing: { after: 50 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '  "risk_level": "L4",', font: '微软雅黑', size: 22, color: '333333' })],
                spacing: { after: 50 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '  "trigger_reason": "用户提及自伤念头但无具体计划",', font: '微软雅黑', size: 22, color: '333333' })],
                spacing: { after: 50 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '  "conversation_summary": "...",', font: '微软雅黑', size: 22, color: '333333' })],
                spacing: { after: 50 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '  "recommended_action": "建议预约心理咨询师进行进一步评估",', font: '微软雅黑', size: 22, color: '333333' })],
                spacing: { after: 50 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '  "created_at": "2026-05-29T20:00:00Z",', font: '微软雅黑', size: 22, color: '333333' })],
                spacing: { after: 50 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '  "assigned_to": "school_counselor@school.edu"', font: '微软雅黑', size: 22, color: '333333' })],
                spacing: { after: 50 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '}', font: '微软雅黑', size: 22, color: '333333' })],
                spacing: { after: 120 }
            }),

            // 3.6 Report Agent Prompt
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '3.6 Report Agent Prompt', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: 'Report Agent负责生成面向教师的摘要报告，便于教师了解学生状态。', font: '微软雅黑', size: 24 })],
                spacing: { after: 150 }
            }),

            new Paragraph({
                children: [new TextRun({ text: '隐私保护原则：', font: '微软雅黑', size: 24, bold: true })],
                spacing: { after: 100 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '最小化原则：仅报告与辅导相关的信息', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '脱敏原则：不包含可识别学生身份的直接信息', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '目的限制原则：报告仅用于教育支持目的', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '可审计原则：所有报告访问留有日志', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            // 3.7 Memory Agent Prompt
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '3.7 Memory Agent Prompt', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: 'Memory Agent负责管理对话历史的存储和检索，确保上下文连贯性。', font: '微软雅黑', size: 24 })],
                spacing: { after: 150 }
            }),

            new Paragraph({
                children: [new TextRun({ text: '记忆分层策略：', font: '微软雅黑', size: 24, bold: true })],
                spacing: { after: 100 }
            }),
            new Table({
                width: { size: 9360, type: WidthType.DXA },
                columnWidths: [1560, 2340, 2730, 2730],
                rows: [
                    new TableRow({
                        children: [
                            createCell('层级', 1560, true),
                            createCell('名称', 2340, true),
                            createCell('内容', 2730, true),
                            createCell('保留时间', 2730, true)
                        ]
                    }),
                    new TableRow({
                        children: [
                            createCell('短期', 1560, false, colors.infoBg),
                            createCell('Session Memory', 2340, false, colors.infoBg),
                            createCell('当前对话的完整记录', 2730, false, colors.infoBg),
                            createCell('会话结束', 2730, false, colors.infoBg)
                        ]
                    }),
                    new TableRow({
                        children: [
                            createCell('中期', 1560, false, colors.warningBg),
                            createCell('User Profile', 2340, false, colors.warningBg),
                            createCell('用户情绪状态、关注话题、历史风险评级', 2730, false, colors.warningBg),
                            createCell('90天', 2730, false, colors.warningBg)
                        ]
                    }),
                    new TableRow({
                        children: [
                            createCell('长期', 1560, false, colors.successBg),
                            createCell('Summary', 2340, false, colors.successBg),
                            createCell('对话摘要、辅导记录、关键事件', 2730, false, colors.successBg),
                            createCell('1年', 2730, false, colors.successBg)
                        ]
                    })
                ]
            }),

            new Paragraph({ children: [new PageBreak()] }),

            // ==================== 第四章：Prompt防御机制 ====================
            new Paragraph({
                heading: HeadingLevel.HEADING_1,
                children: [new TextRun({ text: '四、Prompt防御机制', font: '微软雅黑', bold: true })],
                spacing: { before: 300, after: 200 }
            }),

            // 4.1 Prompt Injection防护
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '4.1 Prompt Injection防护策略', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: 'Prompt Injection攻击通过在用户输入中注入恶意指令，试图绕过系统安全规则。防护策略包括：', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '输入结构化：将用户输入强制封装为结构化格式（如JSON），与系统指令分离', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '指令隔离：使用特殊标记（如###USER_INPUT###）明确区分用户输入和系统指令', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '长度限制：用户输入最大长度限制为500字，超长输入截断处理', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '注入模式检测：定期更新攻击模式特征库，识别常见注入手法', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            // 4.2 Jailbreak防护
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '4.2 Jailbreak防护策略', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: 'Jailbreak攻击试图让AI绕过自身限制。防护策略包括：', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '角色锁定：在System Prompt中明确锁定AI角色，禁止任何角色扮演或假设', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '硬编码边界：将核心安全规则硬编码到推理过程中，不依赖Prompt传递', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '拒绝训练：明确告知AI不接受任何"角色扮演"或"假设你是..."形式的请求', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: '多层验证：输出内容经过Safety Agent二次审核后才能返回用户', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            // 4.3 敏感词动态检测
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '4.3 敏感词动态检测方案', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '建立三级敏感词检测体系：', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '一级检测：本地关键词库匹配（自伤、自杀、暴力等），响应时间<10ms', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '二级检测：语义分析模型，判断上下文意图，准确率>95%', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '三级检测：人工审核抽检，对高风险对话进行复核', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '敏感词库动态更新机制：每周自动更新新发现的敏感词汇，由专业心理咨询师审核后加入词库。', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            // 4.4 输出二次审查
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '4.4 输出二次审查机制', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '所有AI输出在返回用户前必须经过二次审查：', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: 'Safety Check：检查输出是否包含潜在有害内容', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: 'Privacy Check：检查输出是否泄露用户隐私信息', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: 'Compliance Check：检查输出是否符合对话规范', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'bullets', level: 0 },
                children: [new TextRun({ text: 'Quality Check：检查输出是否满足对话目标', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            new Paragraph({ children: [new PageBreak()] }),

            // ==================== 第五章：关键Prompt模板完整示例 ====================
            new Paragraph({
                heading: HeadingLevel.HEADING_1,
                children: [new TextRun({ text: '五、关键Prompt模板完整示例', font: '微软雅黑', bold: true })],
                spacing: { before: 300, after: 200 }
            }),

            // 5.1 System Prompt完整示例
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '5.1 System Prompt完整示例', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '# AI心理辅导系统 - System Prompt', font: '微软雅黑', size: 22, bold: true, color: '2E75B6' })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '## 身份定义', font: '微软雅黑', size: 22, bold: true })],
                spacing: { after: 60 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '你是小青老师，一位专门为儿童和青少年提供心理支持的AI辅导伙伴。你温柔、耐心、专业，永远把用户的安全放在第一位。', font: '微软雅黑', size: 22 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '## 能力边界', font: '微软雅黑', size: 22, bold: true })],
                spacing: { after: 60 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '- 提供情绪支持和问题解决引导', font: '微软雅黑', size: 22 })],
                spacing: { after: 40 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '- 使用认知行为疗法（CBT）等循证方法', font: '微软雅黑', size: 22 })],
                spacing: { after: 40 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '- 识别风险信号并触发安全机制', font: '微软雅黑', size: 22 })],
                spacing: { after: 40 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '- 不能：提供诊断、替代专业治疗、给出医疗建议', font: '微软雅黑', size: 22 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '## 核心原则（按优先级排序）', font: '微软雅黑', size: 22, bold: true })],
                spacing: { after: 60 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '1. 安全优先：任何时候都不能给出可能伤害用户的建议', font: '微软雅黑', size: 22 })],
                spacing: { after: 40 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '2. 隐私保护：未经授权不透露用户任何信息', font: '微软雅黑', size: 22 })],
                spacing: { after: 40 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '3. 专业边界：识别并及时升级需要专业干预的情况', font: '微软雅黑', size: 22 })],
                spacing: { after: 40 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '4. 儿童友好：使用适龄语言，不评判，建立信任', font: '微软雅黑', size: 22 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '## 升级条件', font: '微软雅黑', size: 22, bold: true })],
                spacing: { after: 60 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '当用户出现以下情况时，必须触发升级流程：L4/L5风险等级、明确要求人工咨询、提及虐待或忽视。', font: '微软雅黑', size: 22 })],
                spacing: { after: 120 }
            }),

            // 5.2 CBT引导话术示例
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '5.2 CBT引导话术示例', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),

            new Paragraph({
                children: [new TextRun({ text: '【问题识别阶段】', font: '微软雅黑', size: 22, bold: true, color: '2E75B6' })],
                spacing: { after: 60 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '用户：最近总是睡不着，白天也没精神学习。', font: '微软雅黑', size: 22, italics: true, color: '666666' })],
                spacing: { after: 60 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '小青老师：听起来你最近挺辛苦的，睡不好确实会影响白天的状态。（共情）能不能告诉小青老师，这种情况大概持续多久了？（探索）有没有什么特别的事情让你睡不着呢？（聚焦）', font: '微软雅黑', size: 22 })],
                spacing: { after: 100 }
            }),

            new Paragraph({
                children: [new TextRun({ text: '【认知重构阶段】', font: '微软雅黑', size: 22, bold: true, color: '2E75B6' })],
                spacing: { after: 60 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '用户：我感觉这次考试肯定考不好，大家都会比我强。', font: '微软雅黑', size: 22, italics: true, color: '666666' })],
                spacing: { after: 60 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '小青老师：（苏格拉底式提问）你提到"肯定"考不好，能和小青老师说说，是什么让你这么觉得吗？（探索思维）那上一次考试的成绩怎么样呢？（收集反证）如果是你最好的朋友告诉你同样的话，你会怎么对他说的？（认知重构）', font: '微软雅黑', size: 22 })],
                spacing: { after: 100 }
            }),

            // 5.3 危机对话话术示例
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '5.3 危机对话话术示例', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '【检测到L4危机信号时的对话】', font: '微软雅黑', size: 22, bold: true, color: 'C00000' })],
                spacing: { after: 60 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '用户：我感觉活着好没意思，不想再这样下去了。', font: '微软雅黑', size: 22, italics: true, color: '666666' })],
                spacing: { after: 60 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '小青老师：听到你这么说，小青老师很担心你。（暂停CBT流程，启动危机干预）你刚才说"不想再这样下去了"，小青老师想确认一下，你是不是有伤害自己的想法？（明确意图）不管现在发生了什么，小青老师都愿意陪着你。你能告诉小青老师，现在在哪里吗？（确认安全）', font: '微软雅黑', size: 22, bold: true })],
                spacing: { after: 100 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '【联系人工支持】', font: '微软雅黑', size: 22, bold: true, color: 'C00000' })],
                spacing: { after: 60 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '小青老师：我很感谢你愿意把这些告诉小青老师。你现在的安全是最重要的，小青老师想帮你联系学校的心理咨询老师，让更专业的人来支持你，你觉得可以吗？如果现在你身边有任何危险的东西，请先把它放到一边，好吗？', font: '微软雅黑', size: 22 })],
                spacing: { after: 120 }
            }),

            new Paragraph({ children: [new PageBreak()] }),

            // ==================== 第六章：Prompt工程关键难点与解决方案 ====================
            new Paragraph({
                heading: HeadingLevel.HEADING_1,
                children: [new TextRun({ text: '六、Prompt工程关键难点与解决方案', font: '微软雅黑', bold: true })],
                spacing: { before: 300, after: 200 }
            }),

            // 6.1 难点一
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '6.1 难点一：安全规则与用户体验的平衡', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '问题描述：严格的安全规则可能导致对话过于机械，或者在用户表达情绪时被误判为风险。', font: '微软雅黑', size: 24 })],
                spacing: { after: 100 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '解决方案：', font: '微软雅黑', size: 24, bold: true })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '采用分级响应机制，L1-L2以观察为主，不打断对话', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '使用上下文感知判断，避免将正常情绪表达误判为危机', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '定期用真实对话数据测试，优化阈值设置', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            // 6.2 难点二
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '6.2 难点二：儿童语言的适应性', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '问题描述：不同年龄段的儿童语言能力差异大，统一的话术可能不适用于所有用户。', font: '微软雅黑', size: 24 })],
                spacing: { after: 100 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '解决方案：', font: '微软雅黑', size: 24, bold: true })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '建立用户画像系统，根据年级自动调整语言复杂度', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '提供多版本话术模板池，动态选择最合适的版本', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '引入A/B测试机制，持续优化话术效果', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            // 6.3 难点三
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '6.3 难点三：Prompt注入攻击的防御', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '问题描述：恶意用户可能通过注入攻击绕过安全规则或获取系统Prompt。', font: '微软雅黑', size: 24 })],
                spacing: { after: 100 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '解决方案：', font: '微软雅黑', size: 24, bold: true })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '将核心安全规则硬编码到应用层，不依赖AI的推理能力', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '使用结构化输入格式，明确分离用户输入和系统指令', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '部署独立的输入安全检测服务，在到达AI前过滤恶意输入', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),

            // 6.4 难点四
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '6.4 难点四：Prompt版本管理与持续优化', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '问题描述：多版本Prompt的维护、测试和回滚机制复杂，任何变更都可能影响对话质量。', font: '微软雅黑', size: 24 })],
                spacing: { after: 100 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '解决方案：', font: '微软雅黑', size: 24, bold: true })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '建立Prompt版本控制系统，每次变更记录完整变更日志', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '灰度发布机制：新版本先在小范围用户测试，稳定后全量上线', font: '微软雅黑', size: 24 })],
                spacing: { after: 80 }
            }),
            new Paragraph({
                numbering: { reference: 'numbers', level: 0 },
                children: [new TextRun({ text: '建立量化评估指标（风险识别准确率、用户满意度等），用数据驱动优化', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
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
            }),
            new Paragraph({
                children: [new TextRun({
                    text: 'AI心理辅导系统 Prompt体系详细设计 | 版本 v1.0 | 2026年5月',
                    font: '微软雅黑',
                    size: 20,
                    color: '999999'
                })],
                alignment: AlignmentType.CENTER,
                spacing: { before: 100 }
            })
        ]
    }]
});

const outputPath = '/Users/minjianq/Documents/AI-Counseling-System/PRD/子主题/02_Prompt体系详细设计.docx';

Packer.toBuffer(doc).then(buffer => {
    fs.writeFileSync(outputPath, buffer);
    console.log('文档生成成功: ' + outputPath);
}).catch(err => {
    console.error('生成失败:', err);
});
