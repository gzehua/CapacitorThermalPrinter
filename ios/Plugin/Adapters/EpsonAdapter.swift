import Foundation
import UIKit
import ExternalAccessory

// Le module ePOS2 iOS est livré en xcframework manuel (licence Epson, non
// redistribuable). Le nom de module peut varier selon la version du SDK
// (`libepos2`). Si l'import échoue -> stub inerte (aucune casse de build).
// Voir docs/SDK_INTEGRATION.md (§ Epson iOS).
#if canImport(libepos2)
import libepos2
#endif

/// Adapter Epson iOS basé sur le SDK ePOS2.
///
/// ⚠️ API ObjC pilotée en Swift : à VÉRIFIER sur device avec le xcframework réel
/// (la version du SDK peut ajuster les signatures / le nom de module).
/// Chemin principal : impression IMAGE (réception rendue en bitmap).
final class EpsonAdapter: PrinterAdapter {

    let id: AdapterId = .epson
    private var printers: [String: AnyObject] = [:]
    /// Delegate `onPtrReceive` par imprimante (le SDK ne le retient pas : on le garde ici).
    private var receivers: [String: AnyObject] = [:]
    private let lock = NSLock()

    func isAvailable() -> Bool {
        #if canImport(libepos2)
        return true
        #else
        // Détection runtime de secours si le framework est lié sans module Swift.
        return NSClassFromString("Epos2Printer") != nil
        #endif
    }

    func canHandle(_ profile: PrinterProfile) -> Bool { isAvailable() && profile.adapter == .epson }

    func discover(timeoutMs: Int, onFound: @escaping (DiscoveredPrinter) -> Void) async {
        #if canImport(libepos2)
        // Découverte SDK ePOS2 : TCP + Bluetooth (MFi) + BLE + USB. Indispensable pour les
        // imprimantes Epson en Bluetooth (le scan réseau générique ne les voit pas).
        // ⚠️ L'app DOIT déclarer la protocol string MFi Epson `com.epson.escpos` dans
        // `UISupportedExternalAccessoryProtocols` (Info.plist), sinon iOS ne remonte pas
        // l'imprimante appairée — voir docs/SDK_INTEGRATION.md (§ Epson iOS).
        let filter = Epos2FilterOption()
        filter.deviceType = EPOS2_TYPE_ALL.rawValue
        filter.portType = EPOS2_PORTTYPE_ALL.rawValue
        let delegate = EpsonDiscoveryDelegate(onFound: onFound)
        var result = Epos2Discovery.start(filter, delegate: delegate)
        if result != EPOS2_SUCCESS.rawValue {
            _ = Epos2Discovery.stop() // un scan précédent traîne peut-être : on réessaie
            result = Epos2Discovery.start(filter, delegate: delegate)
        }
        guard result == EPOS2_SUCCESS.rawValue else { return }
        // Scan asynchrone (callbacks delegate) : on le laisse courir puis on l'arrête.
        try? await Task.sleep(nanoseconds: UInt64(max(1000, timeoutMs)) * 1_000_000)
        _ = Epos2Discovery.stop()
        withExtendedLifetime(delegate) {} // garder le delegate vivant pendant tout le scan
        #endif
    }

    func connect(_ profile: PrinterProfile, timeoutMs: Int) async throws {
        #if canImport(libepos2)
        if isConnected(profile.id) { return }
        let target = Self.target(for: profile)
        // USB/Bluetooth passent par MFi : le SDK peut nommer l'imprimante « TM Printer ».
        // Le vrai modèle (ex. « TM-m30III ») vient alors de l'accessoire ExternalAccessory.
        let mfiModel = target.hasPrefix("USB:") || target.hasPrefix("BT:") ? Self.mfiEpsonModel() : nil
        let series = Self.series(for: [mfiModel, profile.model, profile.name].compactMap { $0 })
        guard let printer = Epos2Printer(printerSeries: series, lang: EPOS2_MODEL_ANK.rawValue) else {
            throw PrinterError(.SDK_NOT_AVAILABLE, "Init Epos2Printer échouée")
        }
        let result = printer.connect(target, timeout: Int(timeoutMs))
        guard result == EPOS2_SUCCESS.rawValue else {
            throw PrinterError(.CONNECTION_FAILED, "Connexion Epson échouée: \(profile.address)", detail: "\(result)", retryable: true)
        }
        let receiver = EpsonReceiveDelegate()
        printer.setReceiveEventDelegate(receiver)
        lock.lock(); printers[profile.id] = printer; receivers[profile.id] = receiver; lock.unlock()
        Logger.shared.log("epson", "connected", ["id": profile.id, "target": target, "series": Int(series), "mfiModel": mfiModel ?? ""])
        #else
        throw PrinterError(.SDK_NOT_AVAILABLE, "SDK Epson ePOS2 absent")
        #endif
    }

    func isConnected(_ printerId: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return printers[printerId] != nil
    }

    func disconnect(_ printerId: String) async {
        #if canImport(libepos2)
        lock.lock()
        let p = printers.removeValue(forKey: printerId) as? Epos2Printer
        let r = receivers.removeValue(forKey: printerId) as? EpsonReceiveDelegate
        lock.unlock()
        r?.resolve(.canceled)
        p?.setReceiveEventDelegate(nil)
        p?.disconnect()
        p?.clearCommandBuffer()
        #endif
    }

    func printImage(_ profile: PrinterProfile, image: UIImage, options: RenderOptions) async throws -> Int {
        #if canImport(libepos2)
        lock.lock()
        let p = printers[profile.id] as? Epos2Printer
        let r = receivers[profile.id] as? EpsonReceiveDelegate
        lock.unlock()
        guard let printer = p, let receiver = r else { throw PrinterError(.CONNECTION_FAILED, "Epson non connecté: \(profile.id)") }
        for _ in 0..<max(1, options.copies) {
            printer.beginTransaction()
            printer.add(image, x: 0, y: 0,
                        width: Int(image.size.width), height: Int(image.size.height),
                        color: EPOS2_COLOR_1.rawValue, mode: EPOS2_MODE_MONO.rawValue,
                        halftone: Self.halftone(options.dithering), brightness: 1.0,
                        compress: EPOS2_COMPRESS_AUTO.rawValue)
            if options.cut && profile.capabilities.supportsCut { printer.addCut(EPOS2_CUT_FEED.rawValue) }
            if options.openCashDrawer && profile.capabilities.supportsCashDrawer {
                printer.addPulse(EPOS2_DRAWER_2PIN.rawValue, time: EPOS2_PULSE_100.rawValue)
            }
            defer {
                printer.endTransaction()
                printer.clearCommandBuffer()
            }
            try await Self.sendAndAwait(printer, receiver, printerId: profile.id)
        }
        return Int(image.size.width * image.size.height) / 8
        #else
        throw PrinterError(.SDK_NOT_AVAILABLE, "SDK Epson ePOS2 absent")
        #endif
    }

    func getStatus(_ profile: PrinterProfile) async throws -> PrinterStatus {
        let connected = isConnected(profile.id)
        return PrinterStatus(id: profile.id, connection: connected ? "connected" : "disconnected",
                             online: connected, paper: "unknown",
                             rawStatus: "Epson: statut détaillé via getStatus() à activer si besoin")
    }

    // MARK: Helpers typés

    #if canImport(libepos2)
    private static func target(for profile: PrinterProfile) -> String {
        // Target SSL "TCPS:" (firmwares TLS) : la connexion TCPS exige un certificat
        // provisionné — on se replie sur le canal TCP standard. Sans ça, le fallback
        // fabriquait "TCP:TCPS" (coupé au premier ':') → connexion toujours en échec.
        if profile.address.hasPrefix("TCPS:") {
            return "TCP:" + profile.address.dropFirst("TCPS:".count)
        }
        if profile.address.hasPrefix("TCP:") || profile.address.hasPrefix("BT:")
            || profile.address.hasPrefix("BLE:") || profile.address.hasPrefix("USB:") {
            return profile.address
        }
        switch profile.transport {
        case .wifi, .ethernet: return "TCP:\(profile.address.split(separator: ":").first.map(String.init) ?? profile.address)"
        case .bluetooth, .ble: return "BT:\(profile.address)"
        case .usb: return "USB:\(profile.address)"
        }
    }

    /// `sendData` est ASYNCHRONE : il met le job en file, le verdict de l'imprimante arrive
    /// ensuite dans `onPtrReceive`. Sans l'attendre, un job refusé/perdu était annoncé réussi
    /// (miroir de `sendAndAwait` Android).
    private static func sendAndAwait(_ printer: Epos2Printer, _ receiver: EpsonReceiveDelegate, printerId: String) async throws {
        let verdict: EpsonReceiveDelegate.Verdict = await withCheckedContinuation { cont in
            let token = receiver.arm(cont)
            let result = printer.sendData(Int(EPOS2_PARAM_DEFAULT))
            if result != EPOS2_SUCCESS.rawValue {
                receiver.resolve(.sendFailed(result), token: token)
                return
            }
            // Filet : le SDK rappelle toujours (CODE_ERR_TIMEOUT au pire), mais on ne laisse
            // jamais un job suspendu pour toujours (« Impression en cours… » infini).
            DispatchQueue.global().asyncAfter(deadline: .now() + receiveTimeout) {
                receiver.resolve(.timedOut, token: token)
            }
        }
        switch verdict {
        case .code(let code) where code == EPOS2_CODE_SUCCESS.rawValue:
            return
        case .code(let code):
            Logger.shared.log("epson", "print-refused", ["id": printerId, "code": Int(code)])
            throw PrinterError(errorCode(forCallback: code), "Impression Epson refusée par l'imprimante", detail: "CODE_\(code)", retryable: true)
        case .sendFailed(let result):
            throw PrinterError(.PRINT_FAILED, "Impression Epson échouée", detail: "\(result)", retryable: true)
        case .timedOut:
            throw PrinterError(.TIMEOUT, "Pas de réponse de l'imprimante Epson", retryable: true)
        case .canceled:
            throw PrinterError(.CONNECTION_FAILED, "Imprimante Epson déconnectée pendant l'impression", retryable: true)
        }
    }

    private static let receiveTimeout: TimeInterval = 30

    private static func errorCode(forCallback code: Int32) -> ErrorCode {
        switch code {
        case EPOS2_CODE_ERR_EMPTY.rawValue: return .PAPER_EMPTY
        case EPOS2_CODE_ERR_COVER_OPEN.rawValue: return .COVER_OPEN
        case EPOS2_CODE_ERR_TIMEOUT.rawValue: return .TIMEOUT
        case EPOS2_CODE_ERR_NOT_FOUND.rawValue, EPOS2_CODE_ERR_PORT.rawValue,
             EPOS2_CODE_ERR_CONNECT.rawValue, EPOS2_CODE_ERR_DISCONNECT.rawValue: return .PRINTER_OFFLINE
        default: return .PRINT_FAILED
        }
    }

    /// Série ePOS2 déduite des noms connus (miroir Android), repli TM-m30.
    private static func series(for names: [String]) -> Int32 {
        for candidate in names.flatMap(epsonSeriesCandidates) {
            if let s = seriesByName[candidate] { return s.rawValue }
        }
        return EPOS2_TM_M30.rawValue
    }

    /// Swift ne peut pas lire les noms d'un enum C par réflexion : table explicite.
    private static let seriesByName: [String: Epos2PrinterSeries] = [
        "TM_M10": EPOS2_TM_M10, "TM_M30": EPOS2_TM_M30, "TM_M30II": EPOS2_TM_M30II,
        "TM_M30III": EPOS2_TM_M30III, "TM_M50": EPOS2_TM_M50, "TM_M50II": EPOS2_TM_M50II,
        "TM_M55": EPOS2_TM_M55, "TM_P20": EPOS2_TM_P20, "TM_P20II": EPOS2_TM_P20II,
        "TM_P60": EPOS2_TM_P60, "TM_P60II": EPOS2_TM_P60II, "TM_P80": EPOS2_TM_P80,
        "TM_P80II": EPOS2_TM_P80II, "TM_T20": EPOS2_TM_T20, "TM_T60": EPOS2_TM_T60,
        "TM_T70": EPOS2_TM_T70, "TM_T81": EPOS2_TM_T81, "TM_T82": EPOS2_TM_T82,
        "TM_T83": EPOS2_TM_T83, "TM_T83III": EPOS2_TM_T83III, "TM_T88": EPOS2_TM_T88,
        "TM_T88VII": EPOS2_TM_T88VII, "TM_T90": EPOS2_TM_T90, "TM_T90KP": EPOS2_TM_T90KP,
        "TM_T100": EPOS2_TM_T100, "TM_U220": EPOS2_TM_U220, "TM_U220II": EPOS2_TM_U220II,
        "TM_U330": EPOS2_TM_U330, "TM_L90": EPOS2_TM_L90, "TM_L100": EPOS2_TM_L100,
        "TM_H6000": EPOS2_TM_H6000,
    ]

    /// Modèle de la seule imprimante Epson MFi (USB ou Bluetooth) branchée, sinon nil.
    private static func mfiEpsonModel() -> String? {
        let epsons = EAAccessoryManager.shared().connectedAccessories
            .filter { $0.protocolStrings.contains("com.epson.escpos") }
        guard epsons.count == 1, let a = epsons.first else { return nil }
        return a.modelNumber.isEmpty ? a.name : a.modelNumber
    }

    private static func halftone(_ dithering: String) -> Int32 {
        switch dithering {
        case "none": return EPOS2_HALFTONE_THRESHOLD.rawValue
        case "atkinson", "floyd_steinberg": return EPOS2_HALFTONE_ERROR_DIFFUSION.rawValue
        default: return EPOS2_HALFTONE_DITHER.rawValue
        }
    }
    #endif
}

/// "TM-T20II" -> [TM_T20II, TM_T20I, TM_T20] ; noms génériques ("TM Printer") -> [].
/// Le SDK n'a pas de série par révision : on retire les lettres finales jusqu'à en trouver
/// une connue (miroir de `EpsonAdapter.seriesCandidates` Android). Hors `#if` pour être
/// testable sans le SDK.
func epsonSeriesCandidates(_ name: String) -> [String] {
    let m = name.uppercased().replacingOccurrences(of: " ", with: "")
        .replacingOccurrences(of: "-", with: "").replacingOccurrences(of: "_", with: "")
    guard let range = m.range(of: "TM[A-Z]+[0-9]+[A-Z]*", options: .regularExpression) else { return [] }
    let core = Array(m[range].dropFirst(2))
    let digitsEnd = (core.lastIndex(where: { $0.isNumber }) ?? -1) + 1
    return stride(from: core.count, through: digitsEnd, by: -1).map { "TM_" + String(core[0..<$0]) }
}

#if canImport(libepos2)
/// Verdict `onPtrReceive` d'un job. `arm` renvoie un jeton : un `resolve` tardif (filet de
/// timeout d'un job précédent) ne peut pas conclure le job suivant.
private final class EpsonReceiveDelegate: NSObject, Epos2PtrReceiveDelegate {
    enum Verdict { case code(Int32), sendFailed(Int32), timedOut, canceled }

    private let lock = NSLock()
    private var pending: CheckedContinuation<Verdict, Never>?
    private var token = 0

    func arm(_ cont: CheckedContinuation<Verdict, Never>) -> Int {
        lock.lock(); defer { lock.unlock() }
        pending?.resume(returning: .canceled)
        token += 1
        pending = cont
        return token
    }

    /// `token` nil = le job en cours, quel qu'il soit (callback SDK, déconnexion).
    func resolve(_ verdict: Verdict, token expected: Int? = nil) {
        lock.lock()
        guard expected == nil || expected == token, let cont = pending else { lock.unlock(); return }
        pending = nil
        lock.unlock()
        cont.resume(returning: verdict)
    }

    func onPtrReceive(_ printerObj: Epos2Printer!, code: Int32, status: Epos2PrinterStatusInfo!, printJobId: String!) {
        resolve(.code(code))
    }
}

/// Delegate de découverte ePOS2 : relaie chaque appareil trouvé vers `onFound`.
/// L'`address` est le `target` exact attendu par `Epos2Printer.connect` (ex.
/// `BT:xx:xx:xx`, `TCP:192.168.x.x`, `BLE:xxxx`), donc une imprimante découverte
/// peut être connectée telle quelle.
private final class EpsonDiscoveryDelegate: NSObject, Epos2DiscoveryDelegate {
    private let onFound: (DiscoveredPrinter) -> Void
    init(onFound: @escaping (DiscoveredPrinter) -> Void) { self.onFound = onFound }

    func onDiscovery(_ deviceInfo: Epos2DeviceInfo!) {
        guard let info = deviceInfo else { return }
        let target = info.target ?? ""
        if target.isEmpty { return }
        let transport: Transport
        if target.hasPrefix("BT:") { transport = .bluetooth }
        else if target.hasPrefix("BLE:") { transport = .ble }
        else if target.hasPrefix("USB:") { transport = .usb }
        else { transport = .wifi }
        let name = info.deviceName ?? ""
        // Cibles réseau : le target peut être "TCP(S):<MAC>" (firmwares TLS) — illisible
        // pour l'utilisateur et impossible à dédoublonner avec la découverte Bonjour.
        // DeviceInfo.ipAddress fournit l'IP réelle : on l'utilise comme adresse et comme
        // base de l'id (identique à l'ancien schéma pour les targets "TCP:<ip>" classiques).
        let ip = transport == .wifi ? (info.ipAddress ?? "").trimmingCharacters(in: .whitespaces) : ""
        onFound(DiscoveredPrinter(
            id: ip.isEmpty ? "epson:\(target)" : "epson:TCP:\(ip)",
            name: name.isEmpty ? "Epson" : name,
            brand: "Epson",
            transport: transport,
            adapter: .epson,
            address: ip.isEmpty ? target : ip,
            discoveredBy: ["epson"]))
    }
}
#endif
