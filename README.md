# Simple WOL Server - Wake-on-LAN for Android TV

Android app that runs on Android TV to provide Wake-on-LAN functionality via HTTP API and web interface.

> **Disclaimer**: This app was not created or endorsed by Amazon. Compatible with Android TV devices including Fire TV.

## ✨ Features

- HTTP server with web interface (default: `http://FIRE_TV_IP:8085`)
- Wake-on-LAN via API or web UI
- Configurable authentication (default: enabled)
- Auto-start on Fire TV boot
- Background service with notification

## 📱 Screenshots

<img width="2377" height="1284" alt="image" src="https://github.com/user-attachments/assets/9eea48dc-0788-4f29-982d-cc434776da9a" />

Web interface
<img width="1938" height="1267" alt="image" src="https://github.com/user-attachments/assets/0c3dfacd-75fb-479b-9e2b-2381ad44e5ef" />

## 🔧 Quick Setup

1. **Install APK** on Fire TV:
   ```bash
   adb connect <FIRE_TV_IP>:5555
   adb install WOLFireTV-ver.apk
   ```

2. **Configure** in Fire TV app:
   - Web Password: `admin123` (change this)
   - Auth Token: `default_token_change_me` (change this)
   - Target MAC: Your PC's MAC address
   - Start the service

3. **Access** web interface:
   ```
   http://<FIRE_TV_IP>:8085
   ```

## 🌐 Usage

### Web Interface
- Open `http://FIRE_TV_IP:8085` in browser
- Login with web password
- Click "Send Wake Packet"

### API Calls
```bash
# Wake PC (with auth)
curl -X POST "http://FIRE_TV_IP:8085/wake?token=your_token"

# Wake PC (no auth if disabled)
curl -X POST "http://FIRE_TV_IP:8085/wake"

# Health check
curl http://FIRE_TV_IP:8085/health
```

### Home Automation

**Home Assistant**:
```yaml
switch:
  - platform: command_line
    switches:
      pc_wake:
        command_on: 'curl -X POST "http://192.168.1.100:8085/wake?token=your_token"'
        friendly_name: "Wake PC"
```

## 🔧 Build from Source

```bash
git clone https://github.com/vtstv/WOLFireTV.git
cd WOLFireTV
./gradlew assembleRelease
```

## 📄 License

MIT License

---

*Made with ❤️ for the Fire TV community*

---
