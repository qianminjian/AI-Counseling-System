import { useState } from 'react'
import EmotionSelect from './components/EmotionSelect'
import ChatRoom from './components/ChatRoom'

export default function App() {
  const [session, setSession] = useState(null)

  if (!session) {
    return <EmotionSelect onStart={setSession} />
  }

  return (
    <ChatRoom
      session={session}
      onEnd={() => setSession(null)}
    />
  )
}
