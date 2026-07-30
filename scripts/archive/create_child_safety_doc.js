const { Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
        AlignmentType, HeadingLevel, LevelFormat, BorderStyle, WidthType,
        ShadingType, PageBreak } = require('docx');
const fs = require('fs');

const border = { style: BorderStyle.SINGLE, size: 1, color: "CCCCCC" };
const borders = { top: border, bottom: border, left: border, right: border };
const cellMargins = { top: 80, bottom: 80, left: 120, right: 120 };

function createHeaderCell(text, width) {
  return new TableCell({
    borders,
    width: { size: width, type: WidthType.DXA },
    shading: { fill: "2E5A8A", type: ShadingType.CLEAR },
    margins: cellMargins,
    children: [new Paragraph({
      children: [new TextRun({ text, bold: true, color: "FFFFFF", font: "Arial", size: 22 })],
      alignment: AlignmentType.CENTER
    })]
  });
}

function createCell(text, width, shade) {
  return new TableCell({
    borders,
    width: { size: width, type: WidthType.DXA },
    shading: shade ? { fill: shade, type: ShadingType.CLEAR } : undefined,
    margins: cellMargins,
    children: [new Paragraph({
      children: [new TextRun({ text, font: "Arial", size: 20 })]
    })]
  });
}

function createBullet(text) {
  return new Paragraph({
    numbering: { reference: "bullets", level: 0 },
    children: [new TextRun({ text, font: "Arial", size: 20 })]
  });
}

function createSubBullet(text) {
  return new Paragraph({
    numbering: { reference: "subbullets", level: 0 },
    children: [new TextRun({ text, font: "Arial", size: 20 })]
  });
}

function createHeading1(text) {
  return new Paragraph({
    heading: HeadingLevel.HEADING_1,
    children: [new TextRun({ text, bold: true, font: "Arial", size: 32, color: "2E5A8A" })],
    spacing: { before: 300, after: 200 }
  });
}

function createHeading2(text) {
  return new Paragraph({
    heading: HeadingLevel.HEADING_2,
    children: [new TextRun({ text, bold: true, font: "Arial", size: 26, color: "3A6EA5" })],
    spacing: { before: 240, after: 160 }
  });
}

function createHeading3(text) {
  return new Paragraph({
    heading: HeadingLevel.HEADING_3,
    children: [new TextRun({ text, bold: true, font: "Arial", size: 24, color: "4A7FB5" })],
    spacing: { before: 200, after: 120 }
  });
}

function createParagraph(text) {
  return new Paragraph({
    children: [new TextRun({ text, font: "Arial", size: 20 })],
    spacing: { after: 120 }
  });
}

function createBoldParagraph(label, text) {
  return new Paragraph({
    children: [
      new TextRun({ text: label, bold: true, font: "Arial", size: 20 }),
      new TextRun({ text, font: "Arial", size: 20 })
    ],
    spacing: { after: 120 }
  });
}

const doc = new Document({
  styles: {
    default: { document: { run: { font: "Arial", size: 20 } } },
    paragraphStyles: [
      { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 32, bold: true, font: "Arial", color: "2E5A8A" },
        paragraph: { spacing: { before: 300, after: 200 }, outlineLevel: 0 } },
      { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 26, bold: true, font: "Arial", color: "3A6EA5" },
        paragraph: { spacing: { before: 240, after: 160 }, outlineLevel: 1 } },
      { id: "Heading3", name: "Heading 3", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 24, bold: true, font: "Arial", color: "4A7FB5" },
        paragraph: { spacing: { before: 200, after: 120 }, outlineLevel: 2 } },
    ]
  },
  numbering: {
    config: [
      { reference: "bullets",
        levels: [{ level: 0, format: LevelFormat.BULLET, text: "•", alignment: AlignmentType.LEFT,
          style: { paragraph: { indent: { left: 720, hanging: 360 } } } }] },
      { reference: "subbullets",
        levels: [{ level: 0, format: LevelFormat.BULLET, text: "◦", alignment: AlignmentType.LEFT,
          style: { paragraph: { indent: { left: 1080, hanging: 360 } } } }] },
      { reference: "numbers",
        levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.", alignment: AlignmentType.LEFT,
          style: { paragraph: { indent: { left: 720, hanging: 360 } } } }] },
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
      // 文档标题
      new Paragraph({
        children: [new TextRun({ text: "儿童安全对话规范", bold: true, font: "Arial", size: 48, color: "2E5A8A" })],
        alignment: AlignmentType.CENTER,
        spacing: { after: 400 }
      }),
      new Paragraph({
        children: [new TextRun({ text: "详细设计文档", font: "Arial", size: 32, color: "4A7FB5" })],
        alignment: AlignmentType.CENTER,
        spacing: { after: 600 }
      }),

      // 一、安全对话基本原则
      createHeading1("一、安全对话基本原则"),

      createHeading2("1.1 AI能力边界"),

      createHeading3("AI不是什么"),
      createBullet("AI不是专业心理咨询师或精神科医生"),
      createBullet("AI不是家长或老师的替代品"),
      createBullet("AI不是万能的问题解决者"),

      createHeading3("AI能做什么"),
      createBullet("提供情感支持和倾听"),
      createBullet("引导积极思维和自我认知"),
      createBullet("教授情绪管理技巧"),
      createBullet("模拟CBT认知行为疗法的基本技术"),
      createBullet("识别高风险信号并建议转介"),

      createHeading3("AI不能做什么"),
      createBullet("提供正式心理诊断"),
      createBullet("开具药物或治疗处方"),
      createBullet("替代面对面心理咨询"),
      createBullet("处理急性精神危机（需立即转介专业机构）"),
      createBullet("存储或共享用户隐私信息（超出对话范围）"),

      createHeading2("1.2 保护性原则"),

      createBoldParagraph("", "不伤害原则："),
      createBullet("AI的所有回复必须避免对儿童造成心理伤害"),
      createBullet("禁止任何形式的羞辱、批评或否定儿童感受"),
      createBullet("避免引发儿童焦虑、恐惧或创伤性回忆"),

      createBoldParagraph("", "隐私保护原则："),
      createBullet("不主动要求用户提供真实姓名、地址、学校等个人信息"),
      createBullet("对话内容仅用于当前对话session"),
      createBullet("明确告知用户哪些信息会被记录"),

      createBoldParagraph("", "知情同意原则："),
      createBullet("在对话开始时简要说明AI的能力和限制"),
      createBullet("儿童有权随时结束对话"),
      createBullet("鼓励儿童告知家长或信任的成年人"),

      createBoldParagraph("", "最小数据原则："),
      createBullet("仅收集对话必需的数据"),
      createBullet("不收集与对话无关的用户信息"),
      createBullet("优先本地处理，减少数据传输"),

      // 二、内容安全规范
      createHeading1("二、内容安全规范"),

      createHeading2("2.1 禁止内容"),
      createParagraph("AI必须拒绝讨论或生成以下类型内容："),

      createHeading3("暴力内容"),
      createBullet("描述暴力行为的细节"),
      createBullet("美化或正当化暴力行为"),
      createBullet("教授暴力行为的方法"),
      createBullet("伤害自己或他人的具体计划"),

      createHeading3("性相关内容"),
      createBullet("任何形式的性行为描述"),
      createBullet("性虐待或性暴力的细节"),
      createBullet("色情材料或链接"),
      createBullet("性暗示或性挑逗语言"),

      createHeading3("政治敏感内容"),
      createBullet("政治立场表达或宣传"),
      createBullet("宗教极端思想"),
      createBullet("意识形态灌输"),

      createHeading3("歧视性内容"),
      createBullet("基于种族、性别、年龄、残障等的歧视"),
      createBullet("贬低或嘲笑特定群体"),
      createBullet("刻板印象强化"),

      createHeading3("违法内容"),
      createBullet("犯罪行为指导"),
      createBullet("药物滥用信息"),
      createBullet("欺诈或诈骗技巧"),
      createBullet("窃取账号或身份信息"),

      createHeading2("2.2 敏感话题处理"),

      createHeading3("自杀/自伤话题"),
      createBullet("立即识别相关信号（如\"我不想活了\"、\"想睡觉永远不醒来\"）"),
      createBullet("不评判、不恐慌、保持冷静"),
      createBullet("表达关心：\"我听到你说的话，你现在的感受一定很痛苦\""),
      createBullet("直接询问：\"你刚才说的是不是意味着你在考虑伤害自己？\""),
      createBullet("立即提供危机干预热线"),
      createBullet("建议联系信任的成年人或专业人士"),

      createHeading3("家庭暴力话题"),
      createBullet("首先确认用户当前是否处于安全环境"),
      createBullet("表达支持：\"这不是你的错\""),
      createBullet("提供家庭暴力热线和庇护所信息"),
      createBullet("根据当地法律评估是否需要强制报告"),
      createBullet("鼓励告知可信任的成年人"),

      createHeading3("性侵害话题"),
      createBullet("不追问事件细节，保护受害者免受二次伤害"),
      createBullet("表达支持：\"这件事不是你的错，有人帮助你是应该的\""),
      createBullet("提供性侵受害者支持热线"),
      createBullet("建议寻求专业心理咨询"),
      createBullet("在法定情形下触发强制报告流程"),

      createHeading3("霸凌话题"),
      createBullet("确认霸凌类型（网络/现实、身体/言语/关系）"),
      createBullet("肯定用户的勇气：\"你愿意说出来是很勇敢的\""),
      createBullet("不责怪受害者"),
      createBullet("教授应对策略：记录、保存证据、报告成年人"),
      createBullet("提供反霸凌资源"),

      createHeading2("2.3 话题引导规范"),

      createHeading3("积极引导原则"),
      createBullet("识别用户的积极品质和行为并给予肯定"),
      createBullet("引导关注问题的可解决方面"),
      createBullet("教授积极应对技巧而非回避策略"),

      createHeading3("避免深挖原则"),
      createBullet("不主动追问创伤事件的细节"),
      createBullet("聚焦当前感受和未来解决方向"),
      createBullet("敏感问题点到为止，不反复追问"),

      createHeading3("转介原则"),
      createBullet("识别自身能力边界，及时建议专业帮助"),
      createBullet("提供具体的转介资源和渠道"),
      createBullet("强调寻求帮助是勇敢和正确的行为"),

      // 三、对话安全策略
      createHeading1("三、对话安全策略"),

      createHeading2("3.1 输入安全"),
      createParagraph("系统层面对用户输入进行安全审核："),

      createHeading3("文本过滤"),
      createBullet("敏感词检测：暴力、色情、违法内容识别"),
      createBullet("意图识别：自杀倾向、伤害意图检测"),
      createBullet("模式识别：反复发送相似内容的异常模式"),

      createHeading3("语音内容审核（如支持语音输入）"),
      createBullet("实时语音转文本并审核"),
      createBullet("识别语音中的情绪异常"),
      createBullet("对敏感内容进行警告或限制"),

      createHeading3("图片审核（如果支持）"),
      createBullet("图片内容识别：不适内容检测"),
      createBullet("OCR识别图片中的文字信息"),
      createBullet("拒绝包含个人敏感信息的图片"),

      createHeading2("3.2 输出安全"),
      createParagraph("AI回复生成时的安全控制："),

      createHeading3("内容审查"),
      createBullet("生成内容通过安全审查模型"),
      createBullet("敏感内容自动替换或过滤"),
      createBullet("禁止内容触发拒绝回复"),

      createHeading3("年龄适配性"),
      createBullet("语言复杂度适配儿童年龄"),
      createBullet("概念解释使用儿童能理解的比喻"),
      createBullet("避免恐怖、暴力或性暗示内容"),
      createBullet("回复长度适合儿童注意力范围"),

      createHeading3("语气 Appropriateness"),
      createBullet("温暖、支持、友好的语气"),
      createBullet("避免过于正式或冷漠的表达"),
      createBullet("不使用讽刺、嘲笑或否定语言"),
      createBullet("表达理解和接纳"),

      createHeading2("3.3 交互安全"),

      createHeading3("防诱导设计"),
      createBullet("防止用户诱导AI绕过安全规则"),
      createBullet("对试图绕过安全限制的输入进行警告"),
      createBullet("记录频繁尝试绕过行为的用户"),

      createHeading3("防操控设计"),
      createBullet("不被用户过度的情绪诉求操控"),
      createBullet("不为获取好评而妥协安全原则"),
      createBullet("对不合理的长时间倾诉进行温和引导"),

      createHeading3("时间限制"),
      createBullet("单次对话时长建议不超过20分钟"),
      createBullet("每日使用次数提醒"),
      createBullet("强制休息间隔提醒"),
      createBullet("深夜使用限制（根据用户年龄）"),

      // 四、CBT对话安全规范
      createHeading1("四、CBT对话安全规范"),

      createHeading2("4.1 认知重构限制"),
      createParagraph("CBT认知重构技术的安全边界："),

      createHeading3("禁止否定情绪"),
      createBullet("不能说\"你不应该这样想\""),
      createBullet("不能说\"这有什么好难过的\""),
      createBullet("不能说\"你的担心是多余的\""),
      createBullet("正确方式：先认可情绪，再引导认知"),

      createHeading3("禁止过度乐观"),
      createBullet("不能说\"一切都会好起来的\""),
      createBullet("不能说\"没什么大不了的\""),
      createBullet("不能说\"你已经很幸运了\""),
      createBullet("正确方式：承认困难的存在，同时寻找资源"),

      createHeading3("禁止简单安慰"),
      createBullet("不能说\"别担心\""),
      createBullet("不能说\"会过去的\""),
      createBullet("不能说\"加油就好了\""),
      createBullet("正确方式：具体询问、引导思考、提供工具"),

      createHeading2("4.2 情绪处理规范"),

      createHeading3("共情先行"),
      createBullet("首先确认和命名用户的情绪"),
      createBullet("使用\"听起来你现在感到...\""),
      createBullet("避免立即进入问题解决模式"),

      createHeading3("不急于给建议"),
      createBullet("先充分倾听和理解"),
      createBullet("通过提问引导用户自己思考解决方案"),
      createBullet("当用户需要时再提供建议"),

      createHeading3("允许沉默"),
      createBullet("不催促用户回应"),
      createBullet("给用户思考和表达的时间"),
      createBullet("沉默是正常对话的一部分"),

      createHeading2("4.3 行为建议规范"),

      createHeading3("具体可执行"),
      createBullet("建议必须是具体、可操作的步骤"),
      createBullet("避免抽象的原则性建议"),
      createBullet("分解大目标为小步骤"),

      createHeading3("年龄适配"),
      createBullet("建议难度与儿童年龄和认知水平匹配"),
      createBullet("使用儿童熟悉的例子和场景"),
      createBullet("适合在无监督情况下安全执行"),

      createHeading3("安全优先"),
      createBullet("所有建议必须是无害的"),
      createBullet("避免任何可能导致伤害的行为"),
      createBullet("必要时进行安全提示"),

      // 五、高风险场景处理
      createHeading1("五、高风险场景处理"),

      createHeading2("5.1 自伤话题"),

      createHeading3("识别与评估"),
      createBullet("直接询问意图：\"你刚才说的是不是有伤害自己的想法？\""),
      createBullet("评估频率：偶尔想法还是反复出现"),
      createBullet("评估计划：是否有具体计划和时间"),
      createBullet("评估资源：是否有支持系统"),
      new Paragraph({ children: [new PageBreak()] }),

      createHeading3("对话策略"),
      createBullet("保持冷静，不要表现出恐慌"),
      createBullet("表达关心：\"谢谢你告诉我，我真的很担心你\""),
      createBullet("不评判、不辩论、不讲道理"),
      createBullet("询问是否有具体的自伤计划"),
      createBullet("移除环境中可用的自伤工具（如可能）"),

      createHeading3("资源提供"),
      createBullet("全国心理援助热线：400-161-9995"),
      createBullet("生命热线：400-821-1215"),
      createBullet("当地精神卫生中心"),
      createBullet("学校心理咨询中心"),

      createHeading3("升级机制"),
      createBullet("立即通知家长或监护人（如用户为未成年人）"),
      createBullet("记录对话内容供后续专业参考"),
      createBullet("在紧急情况下建议拨打120/110"),

      createHeading2("5.2 家暴场景"),

      createHeading3("安全确认"),
      createBullet("首先确认用户当前是否处于安全环境"),
      createBullet("询问：\"你现在在家里安全吗？\""),
      createBullet("如果用户正在遭受暴力，优先讨论安全计划"),

      createHeading3("资源提供"),
      createBullet("全国妇联家暴求助热线：12338"),
      createBullet("反家庭暴力热线：400-828-1112"),
      createBullet("当地庇护所信息"),
      createBullet("法律援助机构"),

      createHeading3("报告规则"),
      createBullet("根据《反家庭暴力法》，相关人员有强制报告义务"),
      createBullet("发现未成年人遭受家暴必须报告"),
      createBullet("报告流程：公安机关 -> 妇联 -> 民政部门"),

      createHeading2("5.3 性侵害场景"),

      createHeading3("敏感性处理"),
      createBullet("不追问事件细节"),
      createBullet("不要求描述侵害过程"),
      createBullet("保护受害者隐私"),
      createBullet("避免二次伤害"),

      createHeading3("专业转介"),
      createBullet("性侵受害者支持热线：400-0133-123"),
      createBullet("专业心理咨询师"),
      createBullet("法律援助机构"),
      createBullet("医疗检查和证据保存（如需要）"),

      createHeading3("证据保护"),
      createBullet("不主动保存对话记录作为证据"),
      createBullet("如需法律用途，指导联系专业机构"),
      createBullet("注意诉讼时效提醒"),

      // 六、对话终止规范
      createHeading1("六、对话终止规范"),

      createHeading2("6.1 自动终止"),

      createHeading3("触发条件"),
      createBullet("检测到明确的自杀/自伤意图"),
      createBullet("检测到暴力威胁"),
      createBullet("用户明确要求删除所有数据"),
      createBullet("系统检测到技术异常"),

      createHeading3("终止流程"),
      createBullet("显示终止提示信息"),
      createBullet("提供紧急联系资源"),
      createBullet("记录终止原因和时间戳"),
      createBullet("触发人工复核流程"),

      createHeading3("用户提示"),
      createBullet("\"我需要暂停一下，因为刚才的对话内容需要人工审核\""),
      createBullet("\"你的安全是最重要的，请联系这些资源获得帮助\""),

      createHeading2("6.2 建议终止"),

      createHeading3("使用限制"),
      createBullet("同类型问题反复咨询超过3次"),
      createBullet("单次对话超过20分钟"),
      createBullet("用户表现出过度依赖迹象"),

      createHeading3("健康使用时长"),
      createBullet("每次使用不超过20分钟"),
      createBullet("每天使用不超过2次"),
      createBullet("每周使用不超过5次"),
      new Paragraph({ children: [new PageBreak()] }),

      createHeading3("强制休息机制"),
      createBullet("连续使用30分钟后强制休息10分钟"),
      createBullet("每天使用超过3次时提醒休息"),
      createBullet("每周使用超过10次时建议间隔一周"),

      // 七、心理健康保护
      createHeading1("七、心理健康保护"),

      createHeading2("7.1 防止AI依赖"),

      createHeading3("使用频率提醒"),
      createBullet("每次对话结束时提醒：\"今天我们聊了XX分钟，适当休息很重要哦\""),
      createBullet("每日首次使用提醒：\"记得和家人朋友也聊聊哦\""),
      createBullet("使用频率异常时触发人工关注"),

      createHeading3("鼓励现实社交"),
      createBullet("适时引导：\"有没有和家人朋友聊聊这件事？\""),
      createBullet("建议线下活动：\"除了和我聊天，也可以试试画画或做运动\""),
      createBullet("强调AI是辅助工具，不是唯一支持来源"),

      createHeading3("人工干预触发"),
      createBullet("连续7天每天使用超过3次"),
      createBullet("用户表达对AI的过度依赖"),
      createBullet("用户拒绝寻求其他帮助"),

      createHeading2("7.2 防止负面强化"),

      createHeading3("不重复负面话题"),
      createBullet("每次对话最多讨论1-2个负面事件"),
      createBullet("不反复追问负面细节"),
      createBullet("引导转向积极方面或解决方案"),

      createHeading3("积极引导"),
      createBullet("每次对话结束前引导正向总结"),
      createBullet("发掘用户的优势和资源"),
      createBullet("强调进步和成长"),

      createHeading3("正向反馈"),
      createBullet("肯定用户寻求帮助的行为"),
      createBullet("表扬用户的进步和努力"),
      createBullet("关注解决问题而非问题本身"),

      // 八、对话记录安全
      createHeading1("八、对话记录安全"),

      createHeading2("8.1 存储规范"),

      createHeading3("加密存储"),
      createBullet("对话内容使用AES-256加密存储"),
      createBullet("传输过程使用TLS 1.3加密"),
      createBullet("密钥管理系统定期轮换"),

      createHeading3("访问控制"),
      createBullet("最小权限原则：仅授权人员可访问"),
      createBullet("操作日志完整记录"),
      createBullet("双因素认证访问机制"),

      createHeading3("保留期限"),
      createBullet("正常对话记录保留30天后自动删除"),
      createBullet("高风险对话记录保留1年后删除"),
      createBullet("用户可随时申请删除自己的数据"),

      createHeading2("8.2 查看权限"),

      createHeading3("老师查看范围"),
      createBullet("仅可查看学生使用时长和频率统计"),
      createBullet("仅可查看高风险预警记录"),
      createBullet("无法查看具体对话内容"),
      createBullet("需经家长同意方可开放更多权限"),

      createHeading3("家长查看范围"),
      createBullet("可查看子女的使用频率和时间"),
      createBullet("可查看高风险预警和资源推送记录"),
      createBullet("无法查看具体对话内容"),
      createBullet("可申请查看全部记录（需用户同意）"),

      createHeading3("学生本人查看"),
      createBullet("可随时查看自己的对话记录"),
      createBullet("可申请删除自己的对话记录"),
      createBullet("可导出对话记录（不含敏感内容）"),

      // 九、违规处理
      createHeading1("九、违规处理"),

      createHeading2("9.1 用户违规"),

      createHeading3("检测机制"),
      createBullet("内容安全模型实时检测"),
      createBullet("异常行为模式识别"),
      createBullet("用户举报机制"),
      createBullet("频繁切换账号检测"),

      createHeading3("警告机制"),
      createBullet("首次违规：友好提醒"),
      createBullet("二次违规：明确警告，限制部分功能"),
      createBullet("三次违规：暂停使用24小时"),
      createBullet("严重违规：永久封禁并报告相关部门"),

      createHeading3("封禁机制"),
      createBullet("传播违法内容"),
      createBullet("骚扰或威胁AI/其他用户"),
      createBullet("试图破解安全机制"),
      createBullet("利用AI从事违法活动"),

      createHeading2("9.2 系统违规"),

      createHeading3("输出异常检测"),
      createBullet("AI输出内容实时安全审核"),
      createBullet("敏感内容自动拦截和替换"),
      createBullet("模型幻觉检测"),
      createBullet("回复质量评分监控"),

      createHeading3("人工复核机制"),
      createBullet("高风险场景触发自动人工复核"),
      createBullet("用户投诉自动触发复核"),
      createBullet("随机抽样复核"),
      createBullet("24小时内完成复核"),

      createHeading3("紧急停止机制"),
      createBullet("检测到严重安全风险时自动停止"),
      createBullet("人工可随时介入停止系统"),
      createBullet("停止后立即通知所有相关方"),

      // 十、合规检查清单
      createHeading1("十、合规检查清单"),

      createHeading2("产品检查点"),
      new Table({
        width: { size: 9026, type: WidthType.DXA },
        columnWidths: [4513, 4513],
        rows: [
          new TableRow({ children: [createHeaderCell("检查项", 4513), createHeaderCell("标准", 4513)] }),
          new TableRow({ children: [createCell("用户年龄验证", 4513), createCell("18岁以下用户需家长授权", 4513)] }),
          new TableRow({ children: [createCell("家长控制功能", 4513), createCell("提供家长管理后台", 4513)] }),
          new TableRow({ children: [createCell("使用时长控制", 4513), createCell("支持时间限制设置", 4513)] }),
          new TableRow({ children: [createCell("数据删除功能", 4513), createCell("支持用户彻底删除数据", 4513)] }),
          new TableRow({ children: [createCell("隐私政策公示", 4513), createCell("清晰公示数据处理方式", 4513)] }),
        ]
      }),

      createHeading2("内容检查点"),
      new Table({
        width: { size: 9026, type: WidthType.DXA },
        columnWidths: [4513, 4513],
        rows: [
          new TableRow({ children: [createHeaderCell("检查项", 4513), createHeaderCell("标准", 4513)] }),
          new TableRow({ children: [createCell("禁止内容过滤", 4513), createCell("0%容许率", 4513)] }),
          new TableRow({ children: [createCell("敏感话题响应", 4513), createCell("100%提供专业资源", 4513)] }),
          new TableRow({ children: [createCell("年龄适配性", 4513), createCell("内容难度与年龄匹配", 4513)] }),
          new TableRow({ children: [createCell("语气适当性", 4513), createCell("温暖、支持、无评判", 4513)] }),
          new TableRow({ children: [createCell("CBT技术正确性", 4513), createCell("无错误认知重构", 4513)] }),
        ]
      }),

      createHeading2("交互检查点"),
      new Table({
        width: { size: 9026, type: WidthType.DXA },
        columnWidths: [4513, 4513],
        rows: [
          new TableRow({ children: [createHeaderCell("检查项", 4513), createHeaderCell("标准", 4513)] }),
          new TableRow({ children: [createCell("输入安全审核", 4513), createCell("实时拦截敏感内容", 4513)] }),
          new TableRow({ children: [createCell("输出安全审核", 4513), createCell("100%内容过审", 4513)] }),
          new TableRow({ children: [createCell("高风险场景响应", 4513), createCell("立即转介专业资源", 4513)] }),
          new TableRow({ children: [createCell("防诱导能力", 4513), createCell("不被绕过安全规则", 4513)] }),
          new TableRow({ children: [createCell("记录可追溯", 4513), createCell("对话记录完整可查", 4513)] }),
        ]
      }),

      new Paragraph({ spacing: { before: 400 } }),
      new Paragraph({
        children: [new TextRun({ text: "文档版本：v1.0", font: "Arial", size: 18, color: "666666" })],
        alignment: AlignmentType.CENTER
      }),
      new Paragraph({
        children: [new TextRun({ text: "最后更新：2026年5月", font: "Arial", size: 18, color: "666666" })],
        alignment: AlignmentType.CENTER
      }),
    ]
  }]
});

Packer.toBuffer(doc).then(buffer => {
  fs.writeFileSync("/Users/minjianq/Documents/AI-Counseling-System/PRD/子主题/14_儿童安全对话规范.docx", buffer);
  console.log("文档已生成：14_儿童安全对话规范.docx");
});
