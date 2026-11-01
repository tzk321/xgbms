# XGBMS

自制的电池监控 App：Android WebView 壳 + 直连星光（XinHao）BMS 蓝牙。

## 结构

| 路径 | 说明 |
|---|---|
| `app/src/com/tzk/xgbms/MainActivity.java` | WebView 壳、权限、前后台 |
| `app/src/com/tzk/xgbms/Keep.java` | 保活 |
| `app/AndroidManifest.xml` | 蓝牙权限、组件声明 |
| `web/index.html` | 全部界面，单文件 |
| `out/XGBMS.apk` | 构建产物 |
| `out/*.png` | 界面截图 |

## 要点

- 蓝牙走 BLE 直连 BMS 服务，自己解析帧，不依赖厂商 App
- 界面全在 `web/` 里改，改完塞回 assets 重新打包
- **签名 keystore 未入库**，自己打包要另配

## 已知

- 只对星光 BMS 的协议做过适配，别的 BMS 要改解析
