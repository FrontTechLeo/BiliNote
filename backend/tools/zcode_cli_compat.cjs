// node:sqlite 兼容垫片：为 DatabaseSync 补齐 isTransaction 属性。
//
// 背景：zcode CLI（0.17.1）的会话库迁移检查依赖 node:sqlite DatabaseSync 的
// isTransaction 属性（`e.isTransaction !== !!transactionOpen`），而本机 Node 23.x
// 的 node:sqlite 并未暴露该属性（恒为 undefined），导致独立进程 `zcode -p` 启动时
// 必然抛出 "Migration transaction ownership mismatch"。
// 桌面端不受影响：其库 schema 已是最新，迁移短路不经过该检查。
//
// 用法（由 backend/app/gpt/agent_cli/zcode_cli.py 在 node 运行 .cjs 入口时自动注入）：
//   node --require backend/tools/zcode_cli_compat.cjs <zcode.cjs> -p "..." --json
// 若未来 Node 版本原生提供 isTransaction，垫片自动跳过（无副作用）。
const { DatabaseSync } = require("node:sqlite");

if (!("isTransaction" in DatabaseSync.prototype)) {
  // 通过拦截 exec 跟踪 begin/commit/rollback 事务边界（node:sqlite 无原生暴露）
  const openTxns = new WeakSet();
  const origExec = DatabaseSync.prototype.exec;
  DatabaseSync.prototype.exec = function (sql) {
    const statement = String(sql).trim().toLowerCase();
    if (statement.startsWith("begin")) {
      openTxns.add(this);
    } else if (
      statement.startsWith("commit") ||
      statement.startsWith("rollback") ||
      statement.startsWith("end")
    ) {
      openTxns.delete(this);
    }
    return origExec.call(this, sql);
  };
  Object.defineProperty(DatabaseSync.prototype, "isTransaction", {
    get() {
      return openTxns.has(this);
    },
    configurable: true,
  });
}
