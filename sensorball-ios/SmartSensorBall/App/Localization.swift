import Foundation

enum AppLanguage: String, CaseIterable, Identifiable {
    case chinese = "zh"
    case english = "en"
    case french = "fr"
    case thai = "th"

    var id: String { rawValue }

    static var current: AppLanguage {
        let stored = UserDefaults.standard.string(forKey: "app_language")
        return AppLanguage(rawValue: stored ?? "") ?? .english
    }

    var displayName: String {
        switch self {
        case .chinese: return "简体中文"
        case .english: return "English"
        case .french: return "Français"
        case .thai: return "ไทย"
        }
    }

    var legalSuffix: String {
        switch self {
        case .chinese: return "zh"
        case .english: return "en"
        case .french: return "fr"
        case .thai: return "th"
        }
    }

    func save() {
        UserDefaults.standard.set(rawValue, forKey: "app_language")
    }
}

enum L10n {
    static func text(_ key: String, _ language: AppLanguage) -> String {
        table[language]?[key] ?? table[.english]?[key] ?? key
    }

    private static let table: [AppLanguage: [String: String]] = [
        .chinese: [
            "first_use_title": "连接蓝牙设备",
            "first_use_message": "首次使用前，请进入设置界面扫描并连接 Smart sensor ball 蓝牙设备。",
            "open_settings": "去设置",
            "later": "稍后",
            "settings": "设置",
            "settings_title": "蓝牙连接及语言选择",
            "bluetooth_connection": "蓝牙连接",
            "bluetooth_hint": "请先扫描 SENBALL# 设备，连接成功后即可开始训练。",
            "scan": "扫描",
            "connect": "连接",
            "disconnect": "断开",
            "no_devices": "未扫描到 SENBALL# 设备",
            "language": "APP 语言",
            "done": "完成",
            "training_center": "训练中心",
            "start": "开始",
            "end": "结束",
            "punch_count": "拳击次数",
            "bluetooth": "蓝牙",
            "battery": "电量",
            "api_status": "云端接口",
            "privacy_policy": "隐私政策",
            "user_agreement": "用户协议",
            "document_unavailable": "当前文档暂不可用，请稍后重试。",
            "ready": "准备开始训练",
            "connect_first": "请先连接蓝牙设备",
            "countdown": "即将开始",
            "running": "正在训练",
            "complete": "训练完成",
            "stopped": "训练已停止",
        ],
        .english: [
            "first_use_title": "Connect Bluetooth Device",
            "first_use_message": "Before your first session, open Settings to scan and connect your Smart sensor ball device.",
            "open_settings": "Open Settings",
            "later": "Later",
            "settings": "Settings",
            "settings_title": "Bluetooth Connection and Language",
            "bluetooth_connection": "Bluetooth Connection",
            "bluetooth_hint": "Scan for a SENBALL# device first. Training is available after connection.",
            "scan": "Scan",
            "connect": "Connect",
            "disconnect": "Disconnect",
            "no_devices": "No SENBALL# devices found",
            "language": "App Language",
            "done": "Done",
            "training_center": "Training Center",
            "start": "Start",
            "end": "End",
            "punch_count": "Punch Count",
            "bluetooth": "Bluetooth",
            "battery": "Battery",
            "api_status": "Cloud API",
            "privacy_policy": "Privacy Policy",
            "user_agreement": "User Agreement",
            "document_unavailable": "This document is currently unavailable.",
            "ready": "Ready to train",
            "connect_first": "Connect the Bluetooth device first",
            "countdown": "Starting soon",
            "running": "Training live",
            "complete": "Training complete",
            "stopped": "Training stopped",
        ],
        .french: [
            "first_use_title": "Connecter l'appareil Bluetooth",
            "first_use_message": "Avant la première séance, ouvrez les réglages pour rechercher et connecter votre appareil Smart sensor ball.",
            "open_settings": "Ouvrir les réglages",
            "later": "Plus tard",
            "settings": "Réglages",
            "settings_title": "Connexion Bluetooth et langue",
            "bluetooth_connection": "Connexion Bluetooth",
            "bluetooth_hint": "Recherchez d'abord un appareil SENBALL#. L'entraînement sera disponible après la connexion.",
            "scan": "Rechercher",
            "connect": "Connecter",
            "disconnect": "Déconnecter",
            "no_devices": "Aucun appareil SENBALL# trouvé",
            "language": "Langue de l'app",
            "done": "Terminé",
            "training_center": "Centre d'entraînement",
            "start": "Démarrer",
            "end": "Terminer",
            "punch_count": "Nombre de coups",
            "bluetooth": "Bluetooth",
            "battery": "Batterie",
            "api_status": "API cloud",
            "privacy_policy": "Politique de confidentialité",
            "user_agreement": "Accord utilisateur",
            "document_unavailable": "Ce document n'est pas disponible pour le moment.",
            "ready": "Prêt à s'entraîner",
            "connect_first": "Connectez d'abord l'appareil Bluetooth",
            "countdown": "Départ imminent",
            "running": "En entraînement",
            "complete": "Entraînement terminé",
            "stopped": "Entraînement arrêté",
        ],
        .thai: [
            "first_use_title": "เชื่อมต่ออุปกรณ์ Bluetooth",
            "first_use_message": "ก่อนใช้งานครั้งแรก โปรดเปิดหน้าตั้งค่าเพื่อสแกนและเชื่อมต่ออุปกรณ์ Smart sensor ball",
            "open_settings": "เปิดการตั้งค่า",
            "later": "ภายหลัง",
            "settings": "ตั้งค่า",
            "settings_title": "การเชื่อมต่อ Bluetooth และภาษา",
            "bluetooth_connection": "การเชื่อมต่อ Bluetooth",
            "bluetooth_hint": "โปรดสแกนอุปกรณ์ SENBALL# ก่อน เมื่อเชื่อมต่อแล้วจึงเริ่มฝึกได้",
            "scan": "สแกน",
            "connect": "เชื่อมต่อ",
            "disconnect": "ยกเลิกการเชื่อมต่อ",
            "no_devices": "ไม่พบอุปกรณ์ SENBALL#",
            "language": "ภาษาแอป",
            "done": "เสร็จสิ้น",
            "training_center": "ศูนย์ฝึก",
            "start": "เริ่ม",
            "end": "สิ้นสุด",
            "punch_count": "จำนวนหมัด",
            "bluetooth": "Bluetooth",
            "battery": "แบตเตอรี่",
            "api_status": "Cloud API",
            "privacy_policy": "นโยบายความเป็นส่วนตัว",
            "user_agreement": "ข้อตกลงผู้ใช้",
            "document_unavailable": "เอกสารนี้ยังไม่พร้อมใช้งาน",
            "ready": "พร้อมเริ่มฝึก",
            "connect_first": "โปรดเชื่อมต่ออุปกรณ์ Bluetooth ก่อน",
            "countdown": "กำลังจะเริ่ม",
            "running": "กำลังฝึก",
            "complete": "ฝึกเสร็จแล้ว",
            "stopped": "หยุดการฝึกแล้ว",
        ],
    ]
}
