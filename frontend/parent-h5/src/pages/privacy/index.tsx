import { View, Text } from '@tarojs/components'
import { navigateTo } from '../../utils/nav'

/**
 * 个人信息保护告知页（F-5，2026-07-28）
 * 对齐 design/22（告知同意条款）：登录/注册前必须可见的 PIPL 告知，
 * 家长端公开路由（无需登录），登录页底部提供入口。
 */
export default function PrivacyPage() {
  return (
    <View className="container verify-page">
      <View className="logo-area">
        <Text className="logo-emoji">🔒</Text>
        <Text className="page-title">个人信息保护告知</Text>
        <Text className="page-subtitle">MindSafe 小学生心理辅导系统 · 家长端</Text>
      </View>

      <View className="card privacy-card">
        <Text className="card-title">我们收集什么</Text>
        <Text className="privacy-text">
          为提供心理辅导服务，我们收集必要的孩子信息（如昵称、年龄、年级）与您在注册时提交的联系方式（手机号），以及孩子与 AI 辅导员对话中产生的情绪与状态信息。
        </Text>

        <Text className="card-title">信息如何被使用</Text>
        <Text className="privacy-text">
          信息仅用于心理健康服务、风险识别与干预、效果评估。孩子对话内容经加密存储，仅以脱敏后的摘要形式向学校心理老师展示。
        </Text>

        <Text className="card-title">未成年人信息保护</Text>
        <Text className="privacy-text">
          本系统面向未成年人，我们严格遵循《中华人民共和国个人信息保护法》关于不满十四周岁未成年人个人信息处理的特别规定，收集使用前取得您（监护人）的同意。
        </Text>

        <Text className="card-title">您的权利</Text>
        <Text className="privacy-text">
          您有权查询、复制、更正、删除孩子的个人信息，有权撤回同意。如有任何问题，请联系学校心理老师或拨打客服热线。
        </Text>

        <Text className="tip-text">
          {/* AUD-045：customRoutes 公共路径形态（'/' 映射 /pages/verify/index），避免重复前缀 */}
          <Text className="privacy-link" onClick={() => navigateTo('/')}>← 返回登录</Text>
        </Text>
      </View>
    </View>
  )
}
