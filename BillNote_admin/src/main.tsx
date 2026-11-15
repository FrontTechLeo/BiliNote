import React from 'react'
import { createRoot } from 'react-dom/client'
import { ConfigProvider } from '@arco-design/web-react'
// arco 的 Message/Notification 函数式 API 在 React 19 下需要手动注入 createRoot：
// React 19 的 react-dom 主包不再导出 createRoot，arco 内部会回退到已移除的
// ReactDOM.render，导致 Message.success/error 静默抛错（toast 永不渲染）。
import { setCreateRoot } from '@arco-design/web-react/es/_util/react-dom'
import zhCN from '@arco-design/web-react/es/locale/zh-CN'
import '@arco-design/web-react/dist/css/arco.css'
import './styles.css'
import App from './App'
import MobileApp from './MobileApp'

setCreateRoot(createRoot)

const isMobileRoute = window.location.pathname === '/mobile' || window.location.pathname.startsWith('/mobile/')

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN} componentConfig={{ Card: { bordered: false }, Table: { border: false } }}>
      {isMobileRoute ? <MobileApp /> : <App />}
    </ConfigProvider>
  </React.StrictMode>,
)
