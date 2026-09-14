use tauri::{Emitter, Manager};
use tauri_plugin_shell::process::{CommandChild, CommandEvent};
use tauri_plugin_shell::ShellExt;
use std::env;
use std::fs;
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::Duration;
use serde::{Deserialize, Serialize};

// Sidecar 启动期内前端不该看到「加载中」无限转。
// 总等待上限 = 启动期 PyInstaller 解压 + uvicorn bind 时间的最坏估计。
const BACKEND_STARTUP_TIMEOUT_SECS: u64 = 60;
const BACKEND_DEFAULT_PORT: u16 = 8483;
const SETTINGS_FILE: &str = "desktop.json";

#[derive(Serialize, Clone, Default)]
struct PathDiagnostics {
    path: String,
    path_has_non_ascii: bool,
    path_has_space: bool,
    parent_writable: bool,
    suggestions: Vec<String>,
}

#[derive(Serialize, Clone)]
struct BackendInfo {
    port: u16,
    base_url: String,
    lan_base_urls: Vec<String>,
    data_dir: String,
    sidecar_mode: bool,
}

#[derive(Serialize, Clone, Deserialize)]
struct DesktopSettings {
    port: Option<u16>,
    data_dir: Option<String>,
}

struct SidecarHandle(Mutex<Option<CommandChild>>);
struct BackendPort(Mutex<u16>);
struct DataRoot(Mutex<PathBuf>);

// ---------- 便携模式 / 桌面端设置持久化 ----------

const PORTABLE_FLAG: &str = "portable.flag";

fn dirs_home() -> PathBuf {
    env::var("USERPROFILE")
        .or_else(|_| env::var("HOME"))
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("."))
}

fn exe_dir() -> PathBuf {
    env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(PathBuf::from))
        .unwrap_or_else(|| PathBuf::from("."))
}

/// 绿色便携模式：exe 同级存在 portable.flag 时启用，
/// 数据与设置全部存放在程序目录内，删目录即卸载、不碰注册表。
fn is_portable() -> bool {
    exe_dir().join(PORTABLE_FLAG).is_file()
}

fn settings_path() -> PathBuf {
    if is_portable() {
        return exe_dir().join("data").join(SETTINGS_FILE);
    }
    let base = env::var("APPDATA")
        .map(PathBuf::from)
        .unwrap_or_else(|_| dirs_home().join("AppData").join("Roaming"));
    base.join("BiliNote").join(SETTINGS_FILE)
}

fn load_settings() -> DesktopSettings {
    fs::read_to_string(settings_path())
        .ok()
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or(DesktopSettings { port: None, data_dir: None })
}

fn save_settings_to_disk(settings: &DesktopSettings) -> Result<(), String> {
    let path = settings_path();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("创建设置目录失败: {}", e))?;
    }
    let text = serde_json::to_string_pretty(settings).map_err(|e| e.to_string())?;
    fs::write(&path, text).map_err(|e| format!("写入设置失败: {}", e))
}

fn default_data_root() -> PathBuf {
    if is_portable() {
        return exe_dir().join("data");
    }
    // 数据目录默认 %LOCALAPPDATA%/BiliNote：安装目录只读也安全，路径保持 ASCII
    let base = env::var("LOCALAPPDATA")
        .map(PathBuf::from)
        .unwrap_or_else(|_| dirs_home().join("AppData").join("Local"));
    base.join("BiliNote")
}

fn resolve_data_root(settings: &DesktopSettings) -> PathBuf {
    settings
        .data_dir
        .as_ref()
        .map(PathBuf::from)
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or_else(default_data_root)
}

// ---------- 端口分配与探测 ----------

fn port_in_use(port: u16) -> bool {
    // 通配与回环都要探测：占用方可能监听 0.0.0.0（uvicorn 默认），也可能只绑
    // 127.0.0.1。Windows 下「绑定特定地址」与「他进程已有的通配绑定」能同时成功，
    // 只探测 127.0.0.1 会漏判，sidecar 要到 uvicorn bind 时才报 10048 退出。
    let wildcard_taken = TcpListener::bind(SocketAddr::from(([0, 0, 0, 0], port))).is_err();
    if wildcard_taken {
        return true;
    }
    TcpListener::bind(SocketAddr::from(([127, 0, 0, 1], port))).is_err()
}

fn pick_port(preferred: Option<u16>) -> u16 {
    // 优先用户设置端口；默认端口被占时向上找空闲
    let start = preferred.unwrap_or(BACKEND_DEFAULT_PORT);
    if !port_in_use(start) {
        return start;
    }
    for port in start + 1..start + 21 {
        if !port_in_use(port) {
            return port;
        }
    }
    // 顺延 20 个仍失败就交给系统分配
    TcpListener::bind(SocketAddr::from(([127, 0, 0, 1], 0)))
        .and_then(|listener| listener.local_addr())
        .map(|addr| addr.port())
        .unwrap_or(BACKEND_DEFAULT_PORT)
}

fn backend_ready(port: u16) -> bool {
    // 就绪判定打 /api/sys_health，和 Android 端的探活逻辑一致
    let mut stream = match TcpStream::connect_timeout(
        &SocketAddr::from(([127, 0, 0, 1], port)),
        Duration::from_millis(800),
    ) {
        Ok(stream) => stream,
        Err(_) => return false,
    };
    let request = format!(
        "GET /api/sys_health HTTP/1.1\r\nHost: 127.0.0.1:{}\r\nConnection: close\r\n\r\n",
        port
    );
    if stream.write_all(request.as_bytes()).is_err() {
        return false;
    }
    let _ = stream.set_read_timeout(Some(Duration::from_millis(800)));
    let mut buffer = [0u8; 128];
    let mut response = Vec::new();
    if stream.read(&mut buffer).is_ok() {
        response.extend_from_slice(&buffer);
    }
    let text = String::from_utf8_lossy(&response);
    // 只认 200：宽容 404 会把「恰好占着端口的其他 HTTP 服务」误判成后端就绪，
    // 前端随后所有 API 都会打到错误进程上
    text.starts_with("HTTP/") && text.contains(" 200")
}

fn wait_for_backend(port: u16, app_handle: tauri::AppHandle) {
    let deadline = std::time::Instant::now() + Duration::from_secs(BACKEND_STARTUP_TIMEOUT_SECS);
    while std::time::Instant::now() < deadline {
        if backend_ready(port) {
            let _ = app_handle.emit("backend-ready", port);
            return;
        }
        std::thread::sleep(Duration::from_millis(500));
    }
    let _ = app_handle.emit("backend-failed", "后端服务启动超时，请查看日志或尝试重启服务");
}

// ---------- 局域网地址 ----------

fn primary_route_ip() -> Option<IpAddr> {
    // UDP connect 不发包，仅让协议栈选出口路由，得到主用局域网 IP
    let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("223.5.5.5:80").ok()?;
    socket.local_addr().ok().map(|addr| addr.ip())
}

fn lan_ipv4_addresses() -> Vec<Ipv4Addr> {
    let mut result: Vec<Ipv4Addr> = Vec::new();
    if let Ok(addrs) = if_addrs::get_if_addrs() {
        for iface in addrs {
            if let if_addrs::IfAddr::V4(v4) = iface.addr {
                if !v4.ip.is_loopback() && !result.contains(&v4.ip) {
                    result.push(v4.ip);
                }
            }
        }
    }
    if result.is_empty() {
        if let Some(IpAddr::V4(ip)) = primary_route_ip() {
            result.push(ip);
        }
    }
    // 主路由 IP 排最前，方便用户直接复制
    if let Some(IpAddr::V4(primary)) = primary_route_ip() {
        result.sort_by_key(|ip| *ip != primary);
    }
    result
}

// ---------- 安装目录诊断 ----------

fn analyze_install_path(exe_path: &Path) -> PathDiagnostics {
    let path_text = exe_path.to_string_lossy().to_string();
    let parent_writable = exe_path
        .parent()
        .map(|dir| fs::write(dir.join(".bili_write_probe"), b"ok").is_ok())
        .unwrap_or(false);
    if parent_writable {
        let _ = fs::remove_file(exe_path.parent().unwrap().join(".bili_write_probe"));
    }
    PathDiagnostics {
        path_has_non_ascii: path_text.chars().any(|c| !c.is_ascii()),
        path_has_space: path_text.contains(' '),
        parent_writable,
        path: path_text.clone(),
        suggestions: vec![
            "把应用安装到纯英文且不含空格的路径（如 D:\\BiliNote）".to_string(),
            "数据与笔记产物已存放在用户数据目录，不受安装路径影响".to_string(),
        ],
    }
}

fn check_ffmpeg_availability(app_handle: &tauri::AppHandle) {
    let available = env::var_os("PATH")
        .map(|paths| {
            env::split_paths(&paths).any(|dir| {
                let candidate = dir.join(if cfg!(windows) { "ffmpeg.exe" } else { "ffmpeg" });
                candidate.is_file()
            })
        })
        .unwrap_or(false);
    if !available {
        let _ = app_handle.emit("backend-warning", serde_json::json!({
            "missing_ffmpeg": true,
            "suggestions": ["未在 PATH 中找到 ffmpeg，视频截图/音频转换将失败。安装 ffmpeg 后重启应用。"]
        }));
    }
}

// ---------- Sidecar 生命周期 ----------

fn build_backend_env(port: u16, data_root: &Path) -> Vec<(String, String)> {
    vec![
        ("BACKEND_HOST".to_string(), "0.0.0.0".to_string()),
        ("BACKEND_PORT".to_string(), port.to_string()),
        ("BILINOTE_DATA_DIR".to_string(), data_root.to_string_lossy().to_string()),
        // PC 客户端专用：允许本地路径直读（磁盘文件免上传）
        ("ALLOW_LOCAL_PATH_INPUT".to_string(), "1".to_string()),
        // 显式注入数据根下的产物目录，压过 .env 里可能存在的相对路径
        ("OUT_DIR".to_string(), data_root.join("static").join("screenshots").to_string_lossy().to_string()),
        ("NOTE_OUTPUT_DIR".to_string(), data_root.join("note_results").to_string_lossy().to_string()),
        ("STATIC".to_string(), "/static".to_string()),
    ]
}

fn spawn_backend_sidecar(app_handle: &tauri::AppHandle) -> Result<CommandChild, String> {
    let settings = load_settings();
    let port = pick_port(settings.port);
    let data_root = resolve_data_root(&settings);
    fs::create_dir_all(&data_root).map_err(|e| format!("创建数据目录失败: {}", e))?;

    *app_handle.state::<BackendPort>().0.lock().unwrap() = port;
    *app_handle.state::<DataRoot>().0.lock().unwrap() = data_root.clone();

    let shell = app_handle.shell();
    let envs = build_backend_env(port, &data_root);

    let (rx, child) = if cfg!(debug_assertions) {
        // 开发模式：直接拉起本机 Python，跑仓库里的 backend 源码
        let backend_dir = env::var("BILINOTE_BACKEND_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|_| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../backend"));
        let python = env::var("BILINOTE_DEV_PYTHON").unwrap_or_else(|_| "python".to_string());
        eprintln!("[dev] backend dir: {:?}, python: {}", backend_dir, python);
        shell
            .command(&python)
            .args(["main.py"])
            .current_dir(backend_dir)
            .envs(envs.iter().cloned())
            .spawn()
            .map_err(|e| format!("启动后端失败: {}", e))?
    } else {
        shell
            .sidecar("BiliNoteBackend")
            .map_err(|e| format!("定位后端程序失败: {}", e))?
            .envs(envs.iter().cloned())
            .spawn()
            .map_err(|e| format!("启动后端失败: {}", e))?
    };

    // 转发 sidecar 输出到控制台，便于诊断
    tauri::async_runtime::spawn(async move {
        let mut rx = rx;
        while let Some(event) = rx.recv().await {
            match event {
                CommandEvent::Stdout(line) => eprintln!("[backend] {}", String::from_utf8_lossy(&line)),
                CommandEvent::Stderr(line) => eprintln!("[backend:err] {}", String::from_utf8_lossy(&line)),
                CommandEvent::Terminated(payload) => {
                    eprintln!("[backend] exited: {:?}", payload.code);
                    break;
                }
                CommandEvent::Error(err) => eprintln!("[backend:error] {}", err),
                _ => {}
            }
        }
    });

    // 就绪探测放独立线程，避免阻塞主线程
    {
        let handle = app_handle.clone();
        std::thread::spawn(move || wait_for_backend(port, handle));
    }

    Ok(child)
}

fn kill_backend_sidecar(app_handle: &tauri::AppHandle) {
    if let Some(state) = app_handle.try_state::<SidecarHandle>() {
        if let Ok(mut guard) = state.0.lock() {
            if let Some(child) = guard.take() {
                eprintln!("[shutdown] killing backend sidecar before app exit");
                let _ = child.kill();
            }
        }
    }
}

// ---------- Tauri 命令 ----------

#[tauri::command]
fn get_backend_info(state: tauri::State<BackendPort>, data_root: tauri::State<DataRoot>) -> BackendInfo {
    let port = *state.0.lock().unwrap();
    let lan_urls = lan_ipv4_addresses()
        .into_iter()
        .map(|ip| format!("http://{}:{}/", ip, port))
        .collect();
    BackendInfo {
        port,
        base_url: format!("http://127.0.0.1:{}/", port),
        lan_base_urls: lan_urls,
        data_dir: data_root.0.lock().unwrap().to_string_lossy().to_string(),
        sidecar_mode: !cfg!(debug_assertions),
    }
}

#[tauri::command]
fn get_desktop_settings() -> DesktopSettings {
    load_settings()
}

#[tauri::command]
fn save_desktop_settings(settings: DesktopSettings) -> Result<(), String> {
    save_settings_to_disk(&settings)
}

#[tauri::command]
fn open_data_dir(data_root: tauri::State<DataRoot>) -> Result<(), String> {
    let path = data_root.0.lock().unwrap().clone();
    open_in_file_manager(&path)
}

/// 打开任意本地目录（当前仅限后端回报的模型目录等已知位置）。
#[tauri::command]
fn open_path(path: String) -> Result<(), String> {
    open_in_file_manager(std::path::Path::new(&path))
}

fn open_in_file_manager(path: &std::path::Path) -> Result<(), String> {
    let program = if cfg!(target_os = "windows") {
        "explorer"
    } else if cfg!(target_os = "macos") {
        "open"
    } else {
        "xdg-open"
    };
    std::process::Command::new(program)
        .arg(path)
        .spawn()
        .map(|_| ())
        .map_err(|e| format!("打开目录失败: {}", e))
}

#[tauri::command]
fn get_install_path_diagnostics() -> PathDiagnostics {
    let exe_path = env::current_exe().unwrap_or_else(|_| PathBuf::from("unknown"));
    analyze_install_path(&exe_path)
}

/// 把导出的笔记文件（md / 含截图的 zip）写入用户在原生另存为对话框里选择的路径。
/// WebView 的 <a download> 下载在 Tauri 里不可靠，统一走这条通道落盘。
#[tauri::command]
fn write_export_file(path: String, data_base64: String) -> Result<String, String> {
    use base64::Engine as _;
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(data_base64.as_bytes())
        .map_err(|e| format!("导出数据解码失败: {}", e))?;
    let target = Path::new(&path);
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("创建导出目录失败: {}", e))?;
    }
    fs::write(target, bytes).map_err(|e| format!("写入文件失败: {}", e))?;
    Ok(path)
}

#[tauri::command]
fn restart_backend_sidecar(app_handle: tauri::AppHandle) -> Result<(), String> {
    {
        let state = app_handle.state::<SidecarHandle>();
        let mut guard = state.0.lock().unwrap();
        if let Some(child) = guard.take() {
            let _ = child.kill();
        }
    }
    let child = spawn_backend_sidecar(&app_handle)?;
    *app_handle.state::<SidecarHandle>().0.lock().unwrap() = Some(child);
    Ok(())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_shell::init())
        .setup(|app| {
            if cfg!(debug_assertions) {
                app.handle().plugin(
                    tauri_plugin_log::Builder::default()
                        .level(log::LevelFilter::Info)
                        .build(),
                )?;
            }

            // 先管理状态，spawn 里要写入端口与数据目录
            app.manage(BackendPort(Mutex::new(BACKEND_DEFAULT_PORT)));
            app.manage(DataRoot(Mutex::new(resolve_data_root(&load_settings()))));

            let exe_path = env::current_exe().unwrap_or_else(|_| PathBuf::from("unknown"));
            let diag = analyze_install_path(&exe_path);
            if diag.path_has_non_ascii || diag.path_has_space || !diag.parent_writable {
                let app_handle = app.handle().clone();
                std::thread::spawn(move || {
                    std::thread::sleep(Duration::from_millis(1500));
                    if let Some(window) = app_handle.get_webview_window("main") {
                        let _ = window.emit("backend-warning", &diag);
                    }
                });
            }

            check_ffmpeg_availability(app.handle());

            let child = spawn_backend_sidecar(app.handle()).map_err(|e| {
                let message = e.clone();
                eprintln!("Sidecar 启动失败: {}", message);
                let handle = app.handle().clone();
                std::thread::spawn(move || {
                    std::thread::sleep(Duration::from_millis(800));
                    let _ = handle.emit("backend-failed", message);
                });
                e
            })?;
            app.manage(SidecarHandle(Mutex::new(Some(child))));

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_backend_info,
            get_desktop_settings,
            save_desktop_settings,
            open_data_dir,
            open_path,
            get_install_path_diagnostics,
            restart_backend_sidecar,
            write_export_file
        ])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        // 退出前必须杀掉 sidecar，否则端口被孤儿进程占住，下次启动 bind 失败
        .run(|app_handle, event| {
            match event {
                tauri::RunEvent::ExitRequested { .. } | tauri::RunEvent::Exit => {
                    kill_backend_sidecar(app_handle);
                }
                _ => {}
            }
        });
}
