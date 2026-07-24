import { useState } from 'react'
import { ThemeProvider } from './theme/ThemeProvider'
import EmotionSelect from './components/EmotionSelect'
import ChatRoom from './components/ChatRoom'

export default function App() {
  const [session, setSession] = useState(null)

  return (
    <ThemeProvider>
      {!session ? (
        <EmotionSelect onStart={setSession} />
      ) : (
        <ChatRoom
          session={session}
          onEnd={() => setSession(null)}
        />
      )}
    </ThemeProvider>
  )
}
