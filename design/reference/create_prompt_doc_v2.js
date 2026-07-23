const { Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
        Header, Footer, AlignmentType, LevelFormat, HeadingLevel,
        BorderStyle, WidthType, ShadingType, PageNumber, PageBreak,
        TableOfContents } = require('docx');
const fs = require('fs');

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
            new Paragraph({
                heading: HeadingLevel.HEADING_1,
                children: [new TextRun({ text: '一、总体目标', font: '微软雅黑', bold: true })],
                spacing: { before: 300, after: 200 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '本文档定义AI心理辅导系统的Prompt工程体系，确保系统能够安全、有效、合规地为儿童和青少年提供心理支持服务。核心目标包括：', font: '微软雅黑', size: 24 })],
                spacing: { after: 120 }
            }),
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
            new Paragraph({
                heading: HeadingLevel.HEADING_1,
                children: [new TextRun({ text: '二、五层Prompt分层架构', font: '微软雅黑', bold: true })],
                spacing: { before: 300, after: 200 }
            }),
            new Paragraph({
                children: [new TextRun({ text: '系统采用五层Prompt分层架构，从底层到顶层依次为：System Prompt（系统规则）、Safety Prompt（安全规则）、Workflow Prompt（流程控制）、Role Prompt（角色人格）、Task Prompt（任务Prompt）。各层职责明确，层层递进，确保系统行为的一致性和可控性。', font: '微软雅黑', size: 24 })],
                spacing: { after: 200 }
            }),
            new Paragraph({ children: [new PageBreak()] }),

            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '2.1 架构层次图', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Table({
                width: { size: 9360, type: WidthType.DXA },
                columnWidths: [1200, 2500, 4000, 1660],
                rows: [
                    new TableRow({
                        children: [
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 1200, type: WidthType.DXA }, shading: { fill: '2E75B6', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: '层次', font: '微软雅黑', bold: true, color: 'FFFFFF', size: 20 })] })] }),
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 2500, type: WidthType.DXA }, shading: { fill: '2E75B6', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: '名称', font: '微软雅黑', bold: true, color: 'FFFFFF', size: 20 })] })] }),
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 4000, type: WidthType.DXA }, shading: { fill: '2E75B6', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: '职责', font: '微软雅黑', bold: true, color: 'FFFFFF', size: 20 })] })] }),
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 1660, type: WidthType.DXA }, shading: { fill: '2E75B6', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: '变更频率', font: '微软雅黑', bold: true, color: 'FFFFFF', size: 20 })] })] })
                        ]
                    }),
                    new TableRow({
                        children: [
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 1200, type: WidthType.DXA }, shading: { fill: 'F2F7FB', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: 'L5', font: '微软雅黑', size: 20 })] })] }),
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 2500, type: WidthType.DXA }, shading: { fill: 'F2F7FB', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: 'Task Prompt', font: '微软雅黑', size: 20 })] })] }),
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 4000, type: WidthType.DXA }, shading: { fill: 'F2F7FB', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: '具体任务执行指令', font: '微软雅黑', size: 20 })] })] }),
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 1660, type: WidthType.DXA }, shading: { fill: 'F2F7FB', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: '每次对话', font: '微软雅黑', size: 20 })] })] })
                        ]
                    }),
                    new TableRow({
                        children: [
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 1200, type: WidthType.DXA }, shading: { fill: 'FFFFFF', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: 'L4', font: '微软雅黑', size: 20 })] })] }),
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 2500, type: WidthType.DXA }, shading: { fill: 'FFFFFF', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: 'Role Prompt', font: '微软雅黑', size: 20 })] })] }),
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 4000, type: WidthType.DXA }, shading: { fill: 'FFFFFF', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: '角色人格定义', font: '微软雅黑', size: 20 })] })] }),
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 1660, type: WidthType.DXA }, shading: { fill: 'FFFFFF', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: '每周/版本', font: '微软雅黑', size: 20 })] })] })
                        ]
                    }),
                    new TableRow({
                        children: [
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 1200, type: WidthType.DXA }, shading: { fill: 'F2F7FB', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: 'L3', font: '微软雅黑', size: 20 })] })] }),
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 2500, type: WidthType.DXA }, shading: { fill: 'F2F7FB', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: 'Workflow Prompt', font: '微软雅黑', size: 20 })] })] }),
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 4000, type: WidthType.DXA }, shading: { fill: 'F2F7FB', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: '流程控制规则', font: '微软雅黑', size: 20 })] })] }),
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 1660, type: WidthType.DXA }, shading: { fill: 'F2F7FB', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: '每月', font: '微软雅黑', size: 20 })] })] })
                        ]
                    }),
                    new TableRow({
                        children: [
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 1200, type: WidthType.DXA }, shading: { fill: 'FFFFFF', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: 'L2', font: '微软雅黑', size: 20 })] })] }),
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 2500, type: WidthType.DXA }, shading: { fill: 'FFFFFF', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: 'Safety Prompt', font: '微软雅黑', size: 20 })] })] }),
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 4000, type: WidthType.DXA }, shading: { fill: 'FFFFFF', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: '安全规则约束', font: '微软雅黑', size: 20 })] })] }),
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 1660, type: WidthType.DXA }, shading: { fill: 'FFFFFF', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: '每周', font: '微软雅黑', size: 20 })] })] })
                        ]
                    }),
                    new TableRow({
                        children: [
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 1200, type: WidthType.DXA }, shading: { fill: 'F2F7FB', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: 'L1', font: '微软雅黑', size: 20 })] })] }),
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 2500, type: WidthType.DXA }, shading: { fill: 'F2F7FB', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: 'System Prompt', font: '微软雅黑', size: 20 })] })] }),
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 4000, type: WidthType.DXA }, shading: { fill: 'F2F7FB', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: '系统基础规则', font: '微软雅黑', size: 20 })] })] }),
                            new TableCell({ borders: { top: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' } }, width: { size: 1660, type: WidthType.DXA }, shading: { fill: 'F2F7FB', type: ShadingType.CLEAR }, margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: '季度', font: '微软雅黑', size: 20 })] })] })
                        ]
                    })
                ]
            }),
            new Paragraph({ children: [new PageBreak()] }),

            // ==================== 其他章节内容 ====================
            new Paragraph({
                heading: HeadingLevel.HEADING_2,
                children: [new TextRun({ text: '2.2 System Prompt（系统规则层）', font: '微软雅黑', bold: true })],
                spacing: { before: 200, after: 150 }
            }),
            new Paragraph({
                children: [new TextRun({ text: 'System Prompt是系统运行的基础规则，定义AI的身份定位、能力边界和基本行为准则。该层内容相对稳定，仅在系统重大升级时变更。', font: '微软雅黑', size: 24 })],
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
