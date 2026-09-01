/**
 * Copyright (c) 2025-2026 Murr
 * https://github.com/vtstv/wolserver
 */
package com.vtstv.wolserver.server.web

/**
 * Modern Glassmorphic CSS stylesheet for Simple WOL Server Web Control Dashboard.
 */
object WebDashboardCss {

    fun getCss(): String {
        return """
        :root {
            --bg-base: #0A0E17;
            --bg-card: rgba(21, 27, 43, 0.75);
            --bg-card-hover: rgba(28, 36, 56, 0.9);
            --border-glass: rgba(255, 255, 255, 0.08);
            --border-glow: rgba(0, 229, 255, 0.4);
            --accent-cyan: #00E5FF;
            --accent-orange: #FF9900;
            --accent-green: #00E676;
            --accent-red: #FF5252;
            --text-primary: #FFFFFF;
            --text-secondary: #94A3B8;
            --text-muted: #64748B;
        }

        * { box-sizing: border-box; margin: 0; padding: 0; font-family: 'Plus Jakarta Sans', sans-serif; -webkit-tap-highlight-color: transparent; }

        body {
            background-color: var(--bg-base);
            background-image: 
                radial-gradient(at 0% 0%, rgba(0, 229, 255, 0.12) 0px, transparent 50%),
                radial-gradient(at 100% 100%, rgba(255, 153, 0, 0.08) 0px, transparent 50%);
            background-attachment: fixed;
            color: var(--text-primary);
            min-height: 100vh;
            padding: 24px 16px;
        }

        .container { max-width: 1200px; margin: 0 auto; width: 100%; }

        /* Navbar */
        .navbar {
            display: flex;
            align-items: center;
            justify-content: space-between;
            flex-wrap: wrap;
            gap: 16px;
            padding: 16px 20px;
            background: var(--bg-card);
            backdrop-filter: blur(16px);
            border: 1px solid var(--border-glass);
            border-radius: 20px;
            margin-bottom: 24px;
            box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
        }

        .brand { display: flex; align-items: center; gap: 12px; }
        .brand-img {
            width: 42px;
            height: 42px;
            border-radius: 12px;
            box-shadow: 0 0 16px rgba(0, 229, 255, 0.4);
            object-fit: contain;
            flex-shrink: 0;
        }
        .brand-text h1 { font-size: 18px; font-weight: 800; letter-spacing: -0.5px; }
        .brand-text p { font-size: 11px; color: var(--accent-cyan); font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px; }

        .nav-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }

        .server-badge {
            display: flex;
            align-items: center;
            gap: 8px;
            background: rgba(0, 230, 118, 0.1);
            border: 1px solid rgba(0, 230, 118, 0.3);
            color: var(--accent-green);
            padding: 6px 12px;
            border-radius: 30px;
            font-size: 12px;
            font-weight: 600;
            font-family: 'JetBrains Mono', monospace;
        }
        .pulse-dot {
            width: 8px; height: 8px;
            background: var(--accent-green);
            border-radius: 50%;
            box-shadow: 0 0 10px var(--accent-green);
            animation: pulse 1.5s infinite;
        }
        @keyframes pulse {
            0% { opacity: 0.4; transform: scale(0.9); }
            50% { opacity: 1; transform: scale(1.1); }
            100% { opacity: 0.4; transform: scale(0.9); }
        }

        /* Buttons */
        .btn {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            gap: 8px;
            padding: 10px 16px;
            min-height: 42px;
            border-radius: 12px;
            font-size: 14px;
            font-weight: 600;
            cursor: pointer;
            border: none;
            transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
            text-decoration: none;
            white-space: nowrap;
        }
        .btn-primary { background: var(--accent-cyan); color: #000; font-weight: 700; box-shadow: 0 0 20px rgba(0, 229, 255, 0.3); }
        .btn-primary:hover { background: #33ebff; transform: translateY(-2px); box-shadow: 0 4px 24px rgba(0, 229, 255, 0.5); }
        .btn-secondary { background: rgba(255, 255, 255, 0.06); color: #fff; border: 1px solid var(--border-glass); }
        .btn-secondary:hover { background: rgba(255, 255, 255, 0.12); border-color: var(--border-glow); }
        .btn-wake {
            background: linear-gradient(135deg, #FF9900 0%, #FF5500 100%);
            color: #fff;
            font-weight: 700;
            box-shadow: 0 0 20px rgba(255, 153, 0, 0.35);
        }
        .btn-wake:hover {
            background: linear-gradient(135deg, #FFAA22 0%, #FF6611 100%);
            transform: translateY(-2px);
            box-shadow: 0 4px 25px rgba(255, 153, 0, 0.6);
        }

        /* Hero */
        .quick-bar {
            display: flex;
            justify-content: space-between;
            align-items: center;
            flex-wrap: wrap;
            gap: 16px;
            background: var(--bg-card);
            backdrop-filter: blur(16px);
            border: 1px solid var(--border-glass);
            border-radius: 18px;
            padding: 20px 24px;
            margin-bottom: 28px;
        }
        .quick-info h2 { font-size: 19px; font-weight: 700; margin-bottom: 4px; }
        .quick-info p { font-size: 13px; color: var(--text-secondary); }
        .quick-actions { display: flex; flex-wrap: wrap; gap: 10px; }

        /* Grid */
        .section-title {
            font-size: 17px;
            font-weight: 700;
            letter-spacing: -0.3px;
            margin-bottom: 16px;
            display: flex;
            align-items: center;
            justify-content: space-between;
        }

        .device-grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
            gap: 18px;
            margin-bottom: 36px;
        }

        /* Device Card */
        .device-card {
            background: var(--bg-card);
            backdrop-filter: blur(12px);
            border: 1px solid var(--border-glass);
            border-radius: 18px;
            padding: 20px;
            position: relative;
            transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
            overflow: hidden;
        }
        .device-card::before {
            content: '';
            position: absolute;
            top: 0; left: 0; right: 0; height: 3px;
            background: linear-gradient(90deg, var(--accent-cyan), var(--accent-orange));
            opacity: 0;
            transition: opacity 0.25s;
        }
        .device-card:hover {
            background: var(--bg-card-hover);
            border-color: var(--border-glow);
            transform: translateY(-4px);
            box-shadow: 0 14px 30px rgba(0, 0, 0, 0.5), 0 0 20px rgba(0, 229, 255, 0.15);
        }
        .device-card:hover::before { opacity: 1; }

        .card-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
        .device-icon-box {
            width: 44px; height: 44px;
            border-radius: 12px;
            background: rgba(0, 229, 255, 0.1);
            border: 1px solid rgba(0, 229, 255, 0.25);
            display: flex; align-items: center; justify-content: center;
            font-size: 22px;
        }
        .card-actions { display: flex; gap: 6px; }
        .icon-btn {
            background: rgba(255, 255, 255, 0.05);
            border: 1px solid var(--border-glass);
            color: var(--text-secondary);
            border-radius: 8px;
            width: 36px; height: 36px;
            display: flex; align-items: center; justify-content: center;
            cursor: pointer;
            transition: all 0.2s;
        }
        .icon-btn:hover { color: #fff; background: rgba(255, 255, 255, 0.15); border-color: var(--border-glow); }

        /* Ping Status Badge */
        .status-pill {
            display: inline-flex;
            align-items: center;
            gap: 6px;
            padding: 3px 8px;
            border-radius: 20px;
            font-size: 11px;
            font-weight: 600;
            margin-bottom: 6px;
        }
        .status-pill.online {
            background: rgba(0, 230, 118, 0.15);
            border: 1px solid rgba(0, 230, 118, 0.4);
            color: var(--accent-green);
        }
        .status-pill.offline {
            background: rgba(255, 255, 255, 0.05);
            border: 1px solid var(--border-glass);
            color: var(--text-muted);
        }
        .status-dot {
            width: 6px; height: 6px; border-radius: 50%;
        }
        .status-pill.online .status-dot { background: var(--accent-green); box-shadow: 0 0 8px var(--accent-green); }
        .status-pill.offline .status-dot { background: var(--text-muted); }

        .device-name { font-size: 17px; font-weight: 700; margin-bottom: 4px; word-break: break-word; }
        .device-meta { display: flex; flex-direction: column; gap: 5px; margin-bottom: 16px; }
        .meta-row { display: flex; justify-content: space-between; font-size: 12.5px; }
        .meta-label { color: var(--text-muted); }
        .meta-val { font-family: 'JetBrains Mono', monospace; color: var(--text-secondary); font-weight: 500; font-size: 12px; }

        .card-footer { display: flex; gap: 10px; }
        .btn-card-wake { width: 100%; justify-content: center; padding: 12px; font-size: 15px; }

        /* Add Device Card */
        .add-card {
            border: 2px dashed var(--border-glass);
            background: rgba(22, 28, 45, 0.35);
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            gap: 12px;
            min-height: 200px;
            cursor: pointer;
            border-radius: 18px;
            transition: all 0.2s;
            color: var(--text-secondary);
            padding: 20px;
        }
        .add-card:hover {
            border-color: var(--accent-cyan);
            color: var(--accent-cyan);
            background: rgba(0, 229, 255, 0.05);
            transform: translateY(-4px);
        }
        .add-icon { font-size: 32px; }

        /* Settings Card & Form Layout */
        .settings-card {
            background: var(--bg-card);
            backdrop-filter: blur(16px);
            border: 1px solid var(--border-glass);
            border-radius: 18px;
            padding: 24px;
            margin-bottom: 36px;
        }
        .form-grid {
            display: grid;
            grid-template-columns: repeat(2, 1fr);
            gap: 18px;
            margin-bottom: 20px;
        }
        .form-group { display: flex; flex-direction: column; gap: 8px; }
        .form-group-full { grid-column: 1 / -1; }
        .form-group label { font-size: 13px; font-weight: 600; color: var(--text-secondary); }
        .form-control {
            background: rgba(10, 14, 23, 0.85);
            border: 1px solid var(--border-glass);
            color: #fff;
            padding: 12px 14px;
            border-radius: 10px;
            font-size: 14px;
            outline: none;
            transition: border-color 0.2s, box-shadow 0.2s;
            width: 100%;
        }
        .form-control:focus { border-color: var(--accent-cyan); box-shadow: 0 0 10px rgba(0, 229, 255, 0.2); }

        /* Token Input Group */
        .token-card {
            background: rgba(10, 14, 23, 0.85);
            border: 1px solid var(--border-glass);
            border-radius: 14px;
            padding: 14px 16px;
            display: flex;
            flex-direction: column;
            gap: 10px;
            transition: border-color 0.2s, box-shadow 0.2s;
        }
        .token-card:focus-within {
            border-color: var(--accent-cyan);
            box-shadow: 0 0 16px rgba(0, 229, 255, 0.2);
        }
        .token-input-row {
            display: flex;
            align-items: center;
            gap: 10px;
            flex-wrap: wrap;
        }
        .token-display {
            flex: 1;
            min-width: 240px;
            background: rgba(0, 0, 0, 0.35);
            border: 1px solid rgba(255, 255, 255, 0.05);
            border-radius: 8px;
            padding: 10px 14px;
            font-family: 'JetBrains Mono', monospace;
            font-size: 13.5px;
            letter-spacing: 0.5px;
            color: var(--accent-cyan);
            outline: none;
        }
        .token-actions {
            display: flex;
            gap: 8px;
            flex-shrink: 0;
        }
        .btn-token-action {
            background: rgba(255, 255, 255, 0.08);
            border: 1px solid var(--border-glass);
            color: #fff;
            padding: 9px 14px;
            font-size: 13px;
            font-weight: 600;
            border-radius: 8px;
            cursor: pointer;
            transition: all 0.2s;
            display: inline-flex;
            align-items: center;
            gap: 6px;
        }
        .btn-token-action:hover {
            background: rgba(0, 229, 255, 0.15);
            border-color: var(--accent-cyan);
            color: #fff;
        }
        .field-hint {
            font-size: 12px;
            color: var(--text-muted);
            line-height: 1.4;
        }

        .checkbox-group {
            display: flex; align-items: center; gap: 10px;
            background: rgba(10, 14, 23, 0.6);
            padding: 14px;
            border-radius: 10px;
            border: 1px solid var(--border-glass);
        }
        .checkbox-group input { width: 18px; height: 18px; accent-color: var(--accent-cyan); cursor: pointer; flex-shrink: 0; }
        .checkbox-group label { font-size: 13px; cursor: pointer; color: #fff; }

        /* Modals */
        .modal-overlay {
            position: fixed; inset: 0;
            background: rgba(0, 0, 0, 0.75);
            backdrop-filter: blur(8px);
            display: flex; align-items: center; justify-content: center;
            z-index: 1000;
            opacity: 0; pointer-events: none;
            transition: all 0.25s ease;
            padding: 16px;
        }
        .modal-overlay.active { opacity: 1; pointer-events: auto; }
        .modal {
            background: #151B2B;
            border: 1px solid var(--border-glass);
            border-radius: 20px;
            width: 100%; max-width: 500px;
            max-height: 90vh;
            overflow-y: auto;
            padding: 24px;
            transform: scale(0.95);
            transition: all 0.25s ease;
            box-shadow: 0 20px 50px rgba(0,0,0,0.6);
        }
        .modal-large { max-width: 820px; }
        .modal-overlay.active .modal { transform: scale(1); }
        .modal-header { margin-bottom: 18px; display: flex; align-items: center; justify-content: space-between; }
        .modal-header h3 { font-size: 19px; font-weight: 700; color: #fff; }
        .modal-footer { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 10px; margin-top: 20px; }

        /* Scan Results Table */
        .scan-table { width: 100%; border-collapse: collapse; margin-top: 12px; font-size: 13px; }
        .scan-table th { text-align: left; padding: 10px; color: var(--text-muted); border-bottom: 1px solid var(--border-glass); }
        .scan-table td { padding: 12px 10px; border-bottom: 1px solid rgba(255, 255, 255, 0.04); vertical-align: middle; }
        .scan-table tr:hover { background: rgba(255, 255, 255, 0.03); }

        /* Integration Code Box */
        .code-tabs { display: flex; gap: 6px; margin-bottom: 12px; border-bottom: 1px solid var(--border-glass); padding-bottom: 8px; overflow-x: auto; }
        .code-tab-btn {
            background: rgba(255, 255, 255, 0.05); border: 1px solid transparent; color: var(--text-secondary);
            padding: 6px 12px; border-radius: 8px; font-size: 12px; font-weight: 600; cursor: pointer;
        }
        .code-tab-btn.active { background: rgba(0, 229, 255, 0.15); border-color: var(--accent-cyan); color: var(--accent-cyan); }
        .code-snippet-box {
            background: #080C14; border: 1px solid var(--border-glass); border-radius: 12px; padding: 14px;
            font-family: 'JetBrains Mono', monospace; font-size: 12px; color: #38BDF8; max-height: 280px; overflow-y: auto; white-space: pre-wrap; word-break: break-all;
        }

        /* Schedule Item Card */
        .schedule-card {
            background: rgba(10, 14, 23, 0.7); border: 1px solid var(--border-glass); border-radius: 14px; padding: 14px 16px;
            display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; gap: 12px;
        }

        /* Logs Console */
        .logs-console {
            background: #080C14;
            border: 1px solid var(--border-glass);
            border-radius: 12px;
            padding: 14px;
            font-family: 'JetBrains Mono', monospace;
            font-size: 12px;
            color: #E2E8F0;
            max-height: 380px;
            overflow-y: auto;
            white-space: pre-wrap;
            word-break: break-all;
            line-height: 1.5;
            user-select: text;
        }

        /* Toast */
        .toast-container { position: fixed; bottom: 20px; right: 20px; z-index: 2000; display: flex; flex-direction: column; gap: 8px; max-width: 90vw; }
        .toast {
            background: #1E293B;
            border: 1px solid var(--border-glass);
            color: #fff;
            padding: 12px 18px;
            border-radius: 12px;
            box-shadow: 0 10px 30px rgba(0,0,0,0.5);
            animation: slideIn 0.3s forwards;
            font-size: 13px;
            font-weight: 500;
            display: flex; align-items: center; gap: 10px;
        }
        .toast.success { border-left: 4px solid var(--accent-green); }
        .toast.error { border-left: 4px solid var(--accent-red); }

        @keyframes slideIn {
            from { transform: translateX(100%); opacity: 0; }
            to { transform: translateX(0); opacity: 1; }
        }

        .login-box {
            max-width: 400px;
            width: 100%;
            margin: 60px auto;
            background: var(--bg-card);
            backdrop-filter: blur(16px);
            border: 1px solid var(--border-glass);
            border-radius: 20px;
            padding: 32px 24px;
            text-align: center;
        }
        .login-logo {
            width: 68px;
            height: 68px;
            border-radius: 18px;
            box-shadow: 0 0 24px rgba(0, 229, 255, 0.5);
            margin-bottom: 16px;
            object-fit: contain;
        }
        .about-logo {
            width: 64px;
            height: 64px;
            border-radius: 16px;
            box-shadow: 0 0 24px rgba(0, 229, 255, 0.6);
            object-fit: contain;
        }

        .hidden { display: none !important; }

        /* Mobile Responsiveness (< 768px) */
        @media (max-width: 768px) {
            body { padding: 16px 12px; }
            .navbar { padding: 14px 16px; flex-direction: column; align-items: stretch; gap: 12px; }
            .brand { justify-content: space-between; }
            .nav-actions { justify-content: space-between; width: 100%; }
            .nav-actions .btn { flex: 1; min-width: auto; padding: 8px 10px; font-size: 12.5px; }
            .quick-bar { flex-direction: column; align-items: stretch; padding: 18px; }
            .quick-actions { flex-direction: column; width: 100%; }
            .quick-actions .btn { width: 100%; }
            .device-grid { grid-template-columns: 1fr; }
            .form-grid { grid-template-columns: 1fr; }
            .token-input-row { flex-direction: column; align-items: stretch; }
            .token-display { width: 100%; }
            .token-actions { width: 100%; }
            .token-actions .btn-token-action { flex: 1; justify-content: center; }
            .modal { padding: 20px 16px; }
            .toast-container { left: 16px; right: 16px; bottom: 16px; }
            .toast { width: 100%; }
        }
        """.trimIndent()
    }
}
