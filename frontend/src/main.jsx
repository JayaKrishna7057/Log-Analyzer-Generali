import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { applyTheme, resolveInitialTheme } from './utils/theme.js'
import './index.css'
import App from './App.jsx'

// Applied before the first render so the page never flashes the wrong theme.
applyTheme(resolveInitialTheme())

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
