import React from 'react'
import ReactDOM from 'react-dom/client'
import { ConfigProvider } from '@arco-design/web-react'
import zhCN from '@arco-design/web-react/es/locale/zh-CN'
import '@arco-design/web-react/dist/css/arco.css'
import './styles.css'
import App from './App'
import MobileApp from './MobileApp'

const isMobileRoute = window.location.pathname === '/mobile' || window.location.pathname.startsWith('/mobile/')

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN} componentConfig={{ Card: { bordered: false }, Table: { border: false } }}>
      {isMobileRoute ? <MobileApp /> : <App />}
    </ConfigProvider>
  </React.StrictMode>,
)
