// 全局类型扩展
interface Window {
  SpeechRecognition: any
  webkitSpeechRecognition: any
  webkitAudioContext: typeof AudioContext
}
